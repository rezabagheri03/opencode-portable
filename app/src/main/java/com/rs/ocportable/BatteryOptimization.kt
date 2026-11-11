package com.rs.ocportable

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * Doze suspends network access, so a model call in deep idle hangs even though the engine and the
 * WebView are alive. The exemption is offered once, with an explanation, and declining is allowed.
 */
object BatteryOptimization {
    private const val PREFS = "engine"
    private const val ASKED = "battery_asked"

    fun isExempt(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openSettings(context: Context) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(request)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun maybePrompt(activity: Activity, engineRunning: Boolean) {
        if (!engineRunning || isExempt(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false) || activity.isFinishing) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.isFinishing || isExempt(activity)) return@postDelayed
            AlertDialog.Builder(activity)
                .setTitle("Keep the agent alive")
                .setMessage(
                    "Android may suspend the engine when the screen is off, which stops a running task " +
                        "and drops the model connection. Allowing battery optimisation exemption prevents that. " +
                        "The app still works if you decline.",
                )
                .setPositiveButton("Allow") { _, _ ->
                    prefs.edit().putBoolean(ASKED, true).apply()
                    openSettings(activity)
                }
                .setNegativeButton("Not now") { _, _ ->
                    prefs.edit().putBoolean(ASKED, true).apply()
                    Toast.makeText(activity, "Background work may be suspended.", Toast.LENGTH_LONG).show()
                }
                .show()
        }, 2_500)
    }
}
