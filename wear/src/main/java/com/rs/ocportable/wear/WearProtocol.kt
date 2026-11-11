package com.rs.ocportable.wear

import org.json.JSONObject

/**
 * Wire contract shared with the phone. Kept as a small duplicate rather than a shared module so
 * the watch APK has no dependency on the phone's classes.
 */
object WearProtocol {
    const val CAPABILITY = "ocportable_engine"
    const val PATH_STATUS = "/status"
    const val PATH_DECISION = "/decision"
    const val PATH_COMMAND = "/command"
    const val PATH_AUDIO = "/audio"

    data class Pending(val sessionID: String, val requestID: String, val text: String)

    data class Status(
        val phase: String,
        val activity: String,
        val startedAt: Long,
        val elapsed: Long,
        val sessionID: String?,
        val pending: Pending?,
    )

    fun parseStatus(json: String?): Status? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            if (root.optString("type") != "status") return null
            val pending = root.optJSONObject("pending")?.let {
                Pending(
                    sessionID = it.optString("sessionID"),
                    requestID = it.optString("requestID"),
                    text = it.optString("text"),
                )
            }
            Status(
                phase = root.optString("phase").ifBlank { "idle" },
                activity = root.optString("activity"),
                startedAt = root.optLong("startedAt"),
                elapsed = root.optLong("elapsed"),
                sessionID = root.optString("sessionID").takeIf { it.isNotBlank() && it != "null" },
                pending = pending,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun commandJson(
        action: String,
        sessionID: String? = null,
        requestID: String? = null,
        text: String? = null,
    ): String = JSONObject()
        .put("type", "command")
        .put("action", action)
        .put("sessionID", sessionID ?: JSONObject.NULL)
        .put("requestID", requestID ?: JSONObject.NULL)
        .put("text", text ?: JSONObject.NULL)
        .toString()
}
