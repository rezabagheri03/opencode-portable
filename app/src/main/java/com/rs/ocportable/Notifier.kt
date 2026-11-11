package com.rs.ocportable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifier {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_DECISIONS = "decisions"
    const val ENGINE_ID = 1
    const val DECISION_ID = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "Engine", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The local opencode engine"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DECISIONS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Commands waiting for your approval"
                enableVibration(true)
            },
        )
    }

    fun engineNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("opencode")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Stop", stop).build())
            .build()
    }

    fun updateEngine(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(ENGINE_ID, engineNotification(context, text))
    }

    fun decisionNotification(context: Context, pending: StatusTracker.PendingDecision): Notification {
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("requestID", pending.requestID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getService(
            context,
            3,
            Intent(context, EngineService::class.java)
                .setAction(EngineService.ACTION_DENY)
                .putExtra("sessionID", pending.sessionID)
                .putExtra("requestID", pending.requestID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val short = pending.text.lineSequence().firstOrNull()?.take(120) ?: "Approval needed"
        return Notification.Builder(context, CHANNEL_DECISIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Approval needed")
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(pending.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60 * 1000L)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Deny", deny).build())
            .build()
    }

    fun showDecision(context: Context, pending: StatusTracker.PendingDecision) {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(DECISION_ID, decisionNotification(context, pending))
    }

    fun showPrompt(context: Context, text: String) {
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_DECISIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Prompt from watch")
            .setContentText(text.lineSequence().firstOrNull()?.take(120) ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(DECISION_ID, notification)
    }

    fun cancelDecision(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(DECISION_ID)
    }
}
