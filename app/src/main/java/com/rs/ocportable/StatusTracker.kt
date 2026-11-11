package com.rs.ocportable

/**
 * Derives the one-glance watch state from the engine's event stream.
 *
 * The event names are the contract documented in `docs/07-engine-api.md`, with one correction
 * found while wiring this: `session.tool.called` does NOT carry the tool name. The name arrives in
 * the preceding `session.tool.input.started` event, keyed by the tool-call id, so it is tracked
 * here.
 */
class StatusTracker {

    enum class Phase { IDLE, RUNNING, WAITING }

    data class PendingDecision(
        val sessionID: String,
        val requestID: String,
        val text: String,
    )

    data class AgentStatus(
        val phase: Phase = Phase.IDLE,
        val activity: String = "",
        val startedAt: Long = 0,
        val sessionID: String? = null,
        val pending: PendingDecision? = null,
    )

    private val toolNames = mutableMapOf<String, String>()
    private var current = AgentStatus()

    fun status(): AgentStatus = current

    fun reset() {
        toolNames.clear()
        current = AgentStatus()
    }

    fun setActive(sessionIDs: Set<String>, now: Long = System.currentTimeMillis()): AgentStatus {
        if (current.pending != null) {
            current = current.copy(phase = Phase.WAITING, sessionID = current.pending?.sessionID)
            return current
        }
        current = if (sessionIDs.isEmpty()) {
            AgentStatus()
        } else {
            val first = sessionIDs.first()
            current.copy(
                phase = Phase.RUNNING,
                sessionID = first,
                startedAt = if (current.phase == Phase.RUNNING && current.startedAt > 0) current.startedAt else now,
                activity = current.activity.ifBlank { "working" },
            )
        }
        return current
    }

    fun clearPending(requestID: String? = null): AgentStatus {
        val pending = current.pending ?: return current
        if (requestID != null && requestID != pending.requestID) return current
        current = current.copy(
            phase = if (current.phase == Phase.WAITING) Phase.RUNNING else current.phase,
            pending = null,
        )
        return current
    }

    fun onEvent(event: EngineEvent, now: Long = System.currentTimeMillis()): AgentStatus {
        val data = event.data
        when (event.type) {
            "session.execution.started" -> {
                current = current.copy(
                    phase = Phase.RUNNING,
                    activity = "working",
                    startedAt = now,
                    sessionID = data.string("sessionID") ?: current.sessionID,
                    pending = null,
                )
            }

            "session.execution.succeeded" -> {
                current = AgentStatus(phase = Phase.IDLE, activity = "done")
            }

            "session.execution.failed" -> {
                val error = data["error"]?.toString()?.take(80).orEmpty()
                current = AgentStatus(phase = Phase.IDLE, activity = if (error.isBlank()) "failed" else "failed: $error")
            }

            "session.execution.interrupted" -> {
                current = AgentStatus(phase = Phase.IDLE, activity = "stopped")
            }

            "session.tool.input.started" -> {
                val id = data.string("id")
                val name = data.string("name")
                if (id != null && name != null) toolNames[id] = name
            }

            "session.tool.called" -> {
                val id = data.string("id")
                val name = (id?.let { toolNames[it] }) ?: "tool"
                val input = data["input"].asMap()
                current = current.copy(
                    phase = if (current.pending == null) Phase.RUNNING else Phase.WAITING,
                    activity = describe(name, input),
                    sessionID = data.string("sessionID") ?: current.sessionID,
                    startedAt = if (current.startedAt == 0L) now else current.startedAt,
                )
            }

            "session.tool.success", "session.tool.failed" -> {
                data.string("id")?.let { toolNames.remove(it) }
            }

            "permission.asked" -> {
                val requestID = data.string("id")
                val sessionID = data.string("sessionID") ?: current.sessionID ?: ""
                if (requestID != null) {
                    val resources = data["resources"].asStringList()
                    val text = data.string("message")
                        ?: resources.joinToString("\n").takeIf { it.isNotBlank() }
                        ?: data.string("action")
                        ?: "permission requested"
                    current = current.copy(
                        phase = Phase.WAITING,
                        activity = "waiting for approval",
                        sessionID = sessionID,
                        pending = PendingDecision(sessionID, requestID, text),
                    )
                }
            }

            "permission.replied" -> {
                val requestID = data.string("requestID")
                val pending = current.pending
                if (pending != null && (requestID == null || requestID == pending.requestID)) {
                    val reply = data.string("reply") ?: "answered"
                    current = current.copy(
                        phase = Phase.RUNNING,
                        activity = if (reply == "reject") "denied" else "approved",
                        pending = null,
                    )
                }
            }
        }
        return current
    }

    private fun describe(name: String, input: Map<String, Any?>): String {
        fun value(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { input[it]?.toString()?.takeIf { text -> text.isNotBlank() } }

        return when (name) {
            "edit" -> "editing ${value("path", "filePath", "file_path") ?: "a file"}"
            "write" -> "writing ${value("path", "filePath", "file_path") ?: "a file"}"
            "read" -> "reading ${value("path", "filePath", "file_path") ?: "a file"}"
            "shell" -> "running ${value("command")?.take(80) ?: "a command"}"
            "grep" -> "searching ${value("pattern") ?: "the workspace"}"
            "glob" -> "finding files"
            "webfetch" -> "fetching ${value("url") ?: "a URL"}"
            else -> name
        }
    }

    private fun Any?.asMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: return emptyMap()
        return raw.entries.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()
    }

    private fun Any?.asStringList(): List<String> {
        val raw = this as? List<*> ?: return emptyList()
        return raw.mapNotNull { it?.toString()?.takeIf { text -> text.isNotBlank() } }
    }

    private fun Map<String, Any?>.string(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
}
