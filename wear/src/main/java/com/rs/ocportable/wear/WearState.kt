package com.rs.ocportable.wear

import android.content.Context

/**
 * The last status received, held in memory and persisted so a process restart does not blank the
 * screen. The durable source of truth is the phone's DataItem.
 */
object WearState {
    private const val PREFS = "watch"
    private const val KEY = "status"

    @Volatile var status: WearProtocol.Status? = null
        private set

    fun save(context: Context, json: String?): WearProtocol.Status? {
        if (json.isNullOrBlank()) return status
        val parsed = WearProtocol.parseStatus(json) ?: return status
        status = parsed
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
        return parsed
    }

    fun load(context: Context) {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        status = WearProtocol.parseStatus(json)
    }

    fun clear(context: Context) {
        status = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
