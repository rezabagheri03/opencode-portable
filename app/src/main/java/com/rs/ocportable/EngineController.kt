package com.rs.ocportable

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Owns the engine process, the SSE subscription, the watch bridge, and the derived status.
 *
 * The service owns one controller. The Activity observes it; when the Activity is gone the service
 * keeps running and the watch/notifications keep working.
 */
class EngineController(
    context: Context,
    private val onStateChanged: (State) -> Unit = {},
) {
    sealed class State {
        data object Idle : State()
        data class Preparing(val message: String, val written: Long = 0, val total: Long = 0) : State()
        data class Starting(val message: String) : State()
        data class Running(val url: String) : State()
        data class Failed(val message: String) : State()
    }

    interface Listener {
        fun onState(state: State)
        fun onStatus(status: StatusTracker.AgentStatus)
        fun onPrompt(text: String)
    }

    fun interface StatusListener {
        fun onStatus(status: StatusTracker.AgentStatus)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val layout = EngineLauncher.appFiles(app)
    private val tracker = StatusTracker()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()
    private val output = ArrayDeque<String>()

    private val rootfs = RootfsManager.forApp(app) { progress ->
        setState(State.Preparing("Unpacking runtime…", progress.written, progress.total))
    }

    @Volatile private var process: Process? = null
    @Volatile private var client: EngineClient? = null
    @Volatile private var sseThread: Thread? = null
    @Volatile private var sseGeneration = 0
    @Volatile private var watch: WearBridge? = null
    @Volatile private var dnsCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var stopping = false
    @Volatile private var starting = false
    @Volatile private var autoRestarted = false
    @Volatile private var decisionTimeout: Runnable? = null
    @Volatile private var lastPublished: StatusTracker.AgentStatus? = null

    @Volatile var state: State = State.Idle
        private set

    @Volatile var pendingPrompt: String? = null
        private set

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners += listener
        listener.onState(state)
        listener.onStatus(tracker.status())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun addStatusListener(listener: StatusListener) {
        if (listener !in statusListeners) statusListeners += listener
        listener.onStatus(tracker.status())
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners -= listener
    }

    fun start() {
        if (starting || state is State.Running || state is State.Preparing || state is State.Starting) return
        starting = true
        worker.execute {
            try {
                startInternal()
            } finally {
                starting = false
            }
        }
    }

    fun restart() {
        if (starting) return
        stopping = true
        starting = true
        autoRestarted = false
        worker.execute {
            try {
                stopInternal()
                stopping = false
                startInternal()
            } finally {
                starting = false
            }
        }
    }

    fun stop() {
        stopping = true
        worker.execute { stopInternal() }
    }

    /** Synchronous teardown for service destruction; no worker can be relied on after this. */
    fun stopNow() {
        stopping = true
        stopInternal()
    }

    fun replyPermission(sessionID: String, requestID: String, decision: String) {
        worker.execute {
            runCatching {
                client?.replyPermission(sessionID, requestID, decision)
            }.onSuccess {
                tracker.clearPending(requestID)
                cancelDecisionTimeout()
                Notifier.cancelDecision(app)
                publishStatus()
            }.onFailure {
                Log.w(TAG, "permission reply failed", it)
            }
        }
    }

    fun interruptCurrent() {
        val sessionID = tracker.status().sessionID ?: return
        worker.execute { runCatching { client?.interrupt(sessionID) } }
    }

    fun refreshWatch() {
        watch?.refresh()
    }

    fun takePendingPrompt(): String? = pendingPrompt?.also { pendingPrompt = null }

    private fun startInternal() {
        try {
            stopSse()
            stopWatch()
            setState(State.Preparing("Unpacking runtime…"))
            rootfs.ensure { app.assets.open(RootfsManager.ASSET) }
            if (stopping) return

            setState(State.Preparing("Preparing proot…"))
            ProotDeps.ensure(app, layout)
            if (stopping) return

            val password = PasswordStore.getOrCreate(app)
            val engine = EngineClient(password = password)
            client = engine
            val dns = Dns.servers(app)
            if (stopping) return

            setState(State.Starting("Starting engine…"))
            val child = EngineLauncher.start(layout, dns, password)
            process = child
            readOutput(child)
            if (!waitForHealth(engine)) {
                if (stopping) return
                fail("Engine did not answer on port ${EngineLauncher.PORT}.\n${outputTail()}")
                return
            }

            registerDns()
            tracker.reset()
            lastPublished = null
            tracker.setActive(runCatching { engine.activeSessions() }.getOrDefault(emptySet()))
            if (stopping) {
                child.destroy()
                return
            }
            setState(
                State.Running(
                    "http://${EngineLauncher.HOSTNAME}:${EngineLauncher.PORT}/?auth_token=" +
                        PasswordStore.tokenUrl(password),
                ),
            )
            startSse(engine)
            startWatch()
            publishStatus()
        } catch (t: Throwable) {
            if (!stopping) fail("${t::class.simpleName}: ${t.message}")
        }
    }

    private fun stopInternal() {
        cancelDecisionTimeout()
        stopSse()
        stopWatch()
        Dns.unregister(app, dnsCallback)
        dnsCallback = null
        Notifier.cancelDecision(app)
        val child = process
        process = null
        client = null
        if (child != null) {
            child.destroy()
            runCatching { child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
            if (child.isAlive) child.destroyForcibly()
        }
        setState(State.Idle)
    }

    private fun waitForHealth(engine: EngineClient): Boolean {
        repeat(60) {
            if (stopping) return false
            if (engine.healthy()) return true
            if (process?.isAlive == false) return false
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun readOutput(child: Process) {
        Thread {
            try {
                child.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        if (output.size >= 40) output.removeFirst()
                        output.addLast(line)
                    }
                    Log.d(TAG, line)
                }
            } catch (_: Exception) {
                // The process went away; the exit path below owns the state change.
            }
            if (!stopping && process === child) onProcessExit()
        }.start()
    }

    private fun onProcessExit() {
        if (stopping) return
        if (state !is State.Running) return
        stopSse()
        if (!autoRestarted) {
            autoRestarted = true
            starting = true
            setState(State.Starting("Engine stopped; restarting…"))
            main.postDelayed({
                if (!stopping) {
                    worker.execute {
                        try {
                            startInternal()
                        } finally {
                            starting = false
                        }
                    }
                } else {
                    starting = false
                }
            }, 2_000)
        } else {
            fail("Engine exited.\n${outputTail()}")
        }
    }

    private fun startSse(engine: EngineClient) {
        val generation = ++sseGeneration
        val thread = Thread {
            while (!stopping && generation == sseGeneration && process?.isAlive == true) {
                try {
                    engine.stream(
                        onEvent = ::handleEvent,
                        onConnected = {
                            val active = runCatching { engine.activeSessions() }.getOrDefault(emptySet())
                            publishStatus(tracker.setActive(active))
                        },
                        onClosed = {},
                    )
                } catch (t: Throwable) {
                    if (!stopping) Log.d(TAG, "event stream closed: ${t.message}")
                }
                if (stopping || generation != sseGeneration || process?.isAlive != true) return@Thread
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        sseThread = thread
        thread.start()
    }

    private fun stopSse() {
        sseGeneration++
        sseThread?.interrupt()
        sseThread = null
    }

    private fun handleEvent(event: EngineEvent) {
        if (event.type !in RELEVANT_EVENTS) return
        val status = tracker.onEvent(event)
        when (event.type) {
            "permission.asked" -> {
                status.pending?.let {
                    Notifier.showDecision(app, it)
                    scheduleDecisionTimeout(it)
                }
            }

            "permission.replied",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            -> {
                cancelDecisionTimeout()
                Notifier.cancelDecision(app)
            }
        }
        publishStatus(status)
    }

    private fun publishStatus(status: StatusTracker.AgentStatus = tracker.status()) {
        if (status == lastPublished) return
        lastPublished = status
        watch?.sendStatus(status)
        main.post {
            listeners.forEach { it.onStatus(status) }
            statusListeners.forEach { it.onStatus(status) }
        }
    }

    private fun startWatch() {
        if (watch == null) {
            watch = WearBridge(app, ::onWatchCommand).also { it.start() }
        }
        watch?.sendStatus(tracker.status())
    }

    private fun stopWatch() {
        watch?.stop()
        watch = null
    }

    private fun onWatchCommand(command: WatchProtocol.Command) {
        when (command.action) {
            "approve" -> {
                val status = tracker.status()
                val requestID = command.requestID ?: status.pending?.requestID ?: return
                val sessionID = command.sessionID ?: status.pending?.sessionID ?: status.sessionID ?: return
                replyPermission(sessionID, requestID, "once")
            }

            "deny" -> {
                val status = tracker.status()
                val requestID = command.requestID ?: status.pending?.requestID ?: return
                val sessionID = command.sessionID ?: status.pending?.sessionID ?: status.sessionID ?: return
                replyPermission(sessionID, requestID, "reject")
            }

            "stop" -> interruptCurrent()
            "refresh" -> watch?.sendStatus(tracker.status())
            "prompt" -> command.text?.takeIf { it.isNotBlank() }?.let { text ->
                main.post {
                    if (listeners.isEmpty()) {
                        pendingPrompt = text
                        Notifier.showPrompt(app, text)
                    } else {
                        listeners.forEach { it.onPrompt(text) }
                    }
                }
            }
        }
    }

    private fun scheduleDecisionTimeout(pending: StatusTracker.PendingDecision) {
        cancelDecisionTimeout()
        val runnable = Runnable {
            if (tracker.status().pending?.requestID == pending.requestID) {
                Log.i(TAG, "permission ${pending.requestID} timed out; denying")
                replyPermission(pending.sessionID, pending.requestID, "reject")
            }
        }
        decisionTimeout = runnable
        main.postDelayed(runnable, DECISION_TIMEOUT_MS)
    }

    private fun cancelDecisionTimeout() {
        decisionTimeout?.let { main.removeCallbacks(it) }
        decisionTimeout = null
    }

    private fun registerDns() {
        if (dnsCallback != null) return
        dnsCallback = Dns.register(app) { servers ->
            if (servers.isNotEmpty()) {
                runCatching { layout.resolvConf.writeText(EngineLauncher.resolvConf(servers)) }
            }
        }
    }

    private fun fail(message: String) {
        stopSse()
        stopWatch()
        Dns.unregister(app, dnsCallback)
        dnsCallback = null
        Notifier.cancelDecision(app)
        process?.destroy()
        process = null
        setState(State.Failed(message))
    }

    private fun setState(next: State) {
        main.post {
            state = next
            onStateChanged(next)
            listeners.forEach { it.onState(next) }
        }
    }

    private fun outputTail(): String = synchronized(output) {
        output.toList().joinToString("\n").takeLast(2_000)
    }

    private companion object {
        const val TAG = "EngineController"
        const val DECISION_TIMEOUT_MS = 5 * 60 * 1000L
        val RELEVANT_EVENTS = setOf(
            "session.execution.started",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            "session.tool.input.started",
            "session.tool.called",
            "session.tool.success",
            "session.tool.failed",
            "permission.asked",
            "permission.replied",
        )
    }
}
