package com.rs.ocportable

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps the engine alive when the Activity is gone.
 *
 * `specialUse` is the only FGS type that honestly fits a local agent server and is not time-capped.
 * The wake lock keeps the CPU from sleeping mid-task; it is released when the service stops.
 */
class EngineService : Service(), EngineController.StatusListener {

    private lateinit var controller: EngineController
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val releaseWakeLock = Runnable {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun controller(): EngineController = this@EngineService.controller
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        startForegroundCompat("Preparing…")
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:engine")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        controller = EngineController(this) { state -> updateNotification(state) }
        controller.addStatusListener(this)
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_DENY -> {
                val sessionID = intent.getStringExtra("sessionID")
                val requestID = intent.getStringExtra("requestID")
                if (!sessionID.isNullOrBlank() && !requestID.isNullOrBlank()) {
                    controller.replyPermission(sessionID, requestID, "reject")
                }
                return START_STICKY
            }

            ACTION_RESTART -> {
                controller.restart()
                return START_STICKY
            }

            else -> controller.start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStatus(status: StatusTracker.AgentStatus) {
        if (status.phase == StatusTracker.Phase.IDLE) scheduleWakeLockRelease() else acquireWakeLock()
    }

    override fun onDestroy() {
        controller.removeStatusListener(this)
        main.removeCallbacks(releaseWakeLock)
        controller.stopNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val notification = Notifier.engineNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ENGINE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // specialUse does not exist before API 34. dataSync is not time-capped on API 33 (the
            // target phone); on API 35+ this branch is unreachable.
            startForeground(Notifier.ENGINE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private fun updateNotification(state: EngineController.State) {
        val text = when (state) {
            is EngineController.State.Idle -> "stopped"
            is EngineController.State.Preparing -> state.message
            is EngineController.State.Starting -> state.message
            is EngineController.State.Running -> "running"
            is EngineController.State.Failed -> state.message.lineSequence().firstOrNull() ?: "failed"
        }
        Notifier.updateEngine(this, text)
        when (state) {
            is EngineController.State.Idle, is EngineController.State.Failed -> scheduleWakeLockRelease()
            else -> acquireWakeLock()
        }
    }

    private fun acquireWakeLock() {
        main.removeCallbacks(releaseWakeLock)
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun scheduleWakeLockRelease() {
        main.removeCallbacks(releaseWakeLock)
        main.postDelayed(releaseWakeLock, WAKE_LOCK_IDLE_MS)
    }

    companion object {
        const val ACTION_STOP = "com.rs.ocportable.STOP"
        const val ACTION_DENY = "com.rs.ocportable.DENY"
        const val ACTION_RESTART = "com.rs.ocportable.RESTART"
        private const val WAKE_LOCK_IDLE_MS = 5 * 60 * 1000L
    }
}
