package com.rs.ocportable

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Phone side of the Wearable Data Layer link.
 *
 * `MessageClient` carries live status and commands; `DataClient` keeps the pending decision so a
 * watch that reconnects after a dropout still sees it. Status is throttled to 1 Hz because radio
 * wake-ups are the expensive part on a 247 mAh battery.
 */
class WearBridge(
    private val context: Context,
    private val onCommand: (WatchProtocol.Command) -> Unit,
) : MessageClient.OnMessageReceivedListener {

    private val main = Handler(Looper.getMainLooper())
    private val messages = Wearable.getMessageClient(context)
    private val data = Wearable.getDataClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val nodes = Wearable.getNodeClient(context)
    private val channels = Wearable.getChannelClient(context)

    private val audioCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path != WatchProtocol.PATH_AUDIO) return
            channels.getInputStream(channel).addOnSuccessListener { input ->
                Thread {
                    val bytes = try {
                        input.use { readLimited(it) }
                    } catch (_: Exception) {
                        ByteArray(0)
                    } finally {
                        channels.close(channel)
                    }
                    val text = WalkieAudio.transcribe(context, bytes)
                    if (!text.isNullOrBlank()) {
                        onCommand(WatchProtocol.Command("prompt", null, null, text))
                    }
                }.start()
            }.addOnFailureListener {
                channels.close(channel)
            }
        }
    }

    @Volatile private var nodeID: String? = null
    @Volatile private var started = false
    @Volatile private var lastSentAt = 0L
    @Volatile private var lastStatus: StatusTracker.AgentStatus? = null
    @Volatile private var lastDecisionJson: String? = null
    private var pendingSend: Runnable? = null

    fun start() {
        if (started) return
        started = true
        messages.addListener(this)
        capabilities.addLocalCapability(WatchProtocol.CAPABILITY)
        channels.registerChannelCallback(audioCallback)
        refreshNode()
    }

    fun stop() {
        if (!started) return
        started = false
        messages.removeListener(this)
        capabilities.removeLocalCapability(WatchProtocol.CAPABILITY)
        channels.unregisterChannelCallback(audioCallback)
        pendingSend?.let { main.removeCallbacks(it) }
        pendingSend = null
    }

    fun refresh() {
        refreshNode()
    }

    fun sendStatus(status: StatusTracker.AgentStatus) {
        if (!started) return
        lastStatus = status
        val now = SystemClock.elapsedRealtime()
        val wait = 1_000 - (now - lastSentAt)
        if (wait <= 0) {
            sendNow(status)
            return
        }
        pendingSend?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            pendingSend = null
            lastStatus?.let(::sendNow)
        }
        pendingSend = runnable
        main.postDelayed(runnable, wait)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.PATH_COMMAND) return
        val text = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
        val command = runCatching { WatchProtocol.parseCommand(text) }.getOrNull() ?: return
        onCommand(command)
    }

    private fun sendNow(status: StatusTracker.AgentStatus) {
        lastSentAt = SystemClock.elapsedRealtime()
        val json = runCatching { WatchProtocol.statusJson(status) }.getOrNull() ?: return
        putDecision(json, status)
        withNode { node ->
            messages.sendMessage(node, WatchProtocol.PATH_STATUS, json.toByteArray(Charsets.UTF_8))
                .addOnFailureListener { failure ->
                    Log.d(TAG, "status send failed: ${failure.message}")
                    nodeID = null
                }
        }
    }

    private fun putDecision(json: String, status: StatusTracker.AgentStatus) {
        // Persist the decision only when it changes; clearing the item is equally important so the
        // watch stops showing a resolved prompt. Status messages themselves are not durable.
        if (status.pending == null) {
            if (lastDecisionJson == null) return
            lastDecisionJson = null
        } else {
            if (lastDecisionJson == json) return
            lastDecisionJson = json
        }
        val request = PutDataMapRequest.create(WatchProtocol.PATH_DECISION)
        request.dataMap.putString("json", json)
        request.dataMap.putLong("updatedAt", System.currentTimeMillis())
        if (status.pending != null) request.setUrgent()
        data.putDataItem(request.asPutDataRequest()).addOnFailureListener {
            Log.d(TAG, "data item failed: ${it.message}")
        }
    }

    private fun withNode(action: (String) -> Unit) {
        nodeID?.let(action) ?: refreshNode(action)
    }

    private fun refreshNode(then: ((String) -> Unit)? = null) {
        capabilities.getCapability(WatchProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val first = info.nodes.firstOrNull()
                if (first != null) {
                    nodeID = first.id
                    Log.i(TAG, "watch node: ${first.displayName ?: first.id}")
                    then?.invoke(first.id)
                } else {
                    nodes.connectedNodes.addOnSuccessListener { connected ->
                        connected.firstOrNull()?.let {
                            nodeID = it.id
                            then?.invoke(it.id)
                        }
                    }
                }
            }
            .addOnFailureListener { failure ->
                Log.d(TAG, "capability lookup failed: ${failure.message}")
            }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < 2 * 1024 * 1024) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "WearBridge"
    }
}
