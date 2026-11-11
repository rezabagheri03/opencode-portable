package com.rs.ocportable.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives status and the durable decision item even when the watch UI is not open. The phone
 * sends a MessageClient status for latency and a DataItem for reconnect durability.
 */
class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_STATUS) return
        apply(String(event.data, Charsets.UTF_8))
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WearProtocol.PATH_DECISION) continue
            apply(DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json"))
        }
    }

    private fun apply(json: String?) {
        val previous = WearState.status
        val next = WearState.save(this, json) ?: return
        val pendingChanged = next.pending?.requestID != previous?.pending?.requestID
        if (next.pending != null && pendingChanged) {
            vibrate(120)
            notifyDecision(next)
        } else if (next.phase == "idle" && previous?.phase == "running") {
            vibrate(60)
            cancelDecision()
        }
        if (next.pending == null) cancelDecision()
    }

    private fun notifyDecision(status: WearProtocol.Status) {
        val pending = status.pending ?: return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_watch_notification)
            .setContentTitle("Approval needed")
            .setContentText(pending.text.lineSequence().firstOrNull()?.take(80) ?: pending.text)
            .setStyle(Notification.BigTextStyle().bigText(pending.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelDecision() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun vibrate(milliseconds: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val CHANNEL = "decisions"
        const val NOTIFICATION_ID = 1
    }
}
