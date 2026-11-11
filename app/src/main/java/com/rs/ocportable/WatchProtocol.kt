package com.rs.ocportable

import org.json.JSONObject

/**
 * Wire format for the phone <-> watch link. Small JSON over the Wearable Data Layer:
 * `MessageClient` for live status and commands, `DataClient` for the pending decision.
 */
object WatchProtocol {
    const val CAPABILITY = "ocportable_engine"
    const val PATH_STATUS = "/status"
    const val PATH_DECISION = "/decision"
    const val PATH_COMMAND = "/command"
    const val PATH_AUDIO = "/audio"

    data class Command(
        val action: String,
        val sessionID: String?,
        val requestID: String?,
        val text: String?,
    )

    fun statusJson(status: StatusTracker.AgentStatus, now: Long = System.currentTimeMillis()): String {
        val pending = status.pending?.let {
            JSONObject()
                .put("sessionID", it.sessionID)
                .put("requestID", it.requestID)
                .put("text", it.text)
        } ?: JSONObject.NULL
        return JSONObject()
            .put("type", "status")
            .put("phase", status.phase.name.lowercase())
            .put("activity", status.activity)
            .put("startedAt", status.startedAt)
            .put("elapsed", if (status.startedAt > 0) (now - status.startedAt).coerceAtLeast(0) else 0)
            .put("sessionID", status.sessionID ?: JSONObject.NULL)
            .put("pending", pending)
            .toString()
    }

    fun parseCommand(json: String): Command? = try {
        val root = JSONObject(json)
        if (root.optString("type") != "command") {
            null
        } else {
            val action = root.optString("action").takeIf { it.isNotBlank() }
            if (action == null) {
                null
            } else {
                Command(
                    action = action,
                    sessionID = root.optString("sessionID").takeIf { it.isNotBlank() && it != "null" },
                    requestID = root.optString("requestID").takeIf { it.isNotBlank() && it != "null" },
                    text = root.optString("text").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    fun commandJson(action: String, sessionID: String? = null, requestID: String? = null, text: String? = null): String =
        JSONObject()
            .put("type", "command")
            .put("action", action)
            .put("sessionID", sessionID ?: JSONObject.NULL)
            .put("requestID", requestID ?: JSONObject.NULL)
            .put("text", text ?: JSONObject.NULL)
            .toString()
}
