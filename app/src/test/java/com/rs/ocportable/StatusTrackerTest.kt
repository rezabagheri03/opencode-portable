package com.rs.ocportable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTrackerTest {
    private fun event(type: String, vararg data: Pair<String, Any?>) = EngineEvent(type, mapOf(*data))

    @Test
    fun startedIsRunning() {
        val tracker = StatusTracker()
        val status = tracker.onEvent(event("session.execution.started", "sessionID" to "ses_1"), now = 100)
        assertEquals(StatusTracker.Phase.RUNNING, status.phase)
        assertEquals("ses_1", status.sessionID)
        assertEquals(100, status.startedAt)
    }

    @Test
    fun toolNameComesFromInputStarted() {
        val tracker = StatusTracker()
        tracker.onEvent(
            event("session.tool.input.started", "id" to "tool_1", "name" to "edit", "sessionID" to "ses_1"),
        )
        val status = tracker.onEvent(
            event(
                "session.tool.called",
                "id" to "tool_1",
                "sessionID" to "ses_1",
                "input" to mapOf("path" to "src/auth.ts"),
            ),
        )
        assertEquals("editing src/auth.ts", status.activity)
        assertEquals(StatusTracker.Phase.RUNNING, status.phase)
    }

    @Test
    fun shellCommandBecomesActivity() {
        val tracker = StatusTracker()
        tracker.onEvent(event("session.tool.input.started", "id" to "t", "name" to "shell"))
        val status = tracker.onEvent(
            event("session.tool.called", "id" to "t", "input" to mapOf("command" to "bun test")),
        )
        assertEquals("running bun test", status.activity)
    }

    @Test
    fun permissionRequestCarriesFullResources() {
        val tracker = StatusTracker()
        val status = tracker.onEvent(
            event(
                "permission.asked",
                "id" to "per_1",
                "sessionID" to "ses_1",
                "action" to "shell",
                "resources" to listOf("git commit -m 'x'", "git push"),
            ),
        )
        assertEquals(StatusTracker.Phase.WAITING, status.phase)
        assertEquals("per_1", status.pending?.requestID)
        assertEquals("git commit -m 'x'\ngit push", status.pending?.text)
    }

    @Test
    fun replyClearsPending() {
        val tracker = StatusTracker()
        tracker.onEvent(
            event("permission.asked", "id" to "per_1", "sessionID" to "ses_1", "resources" to listOf("ls")),
        )
        val status = tracker.onEvent(
            event("permission.replied", "requestID" to "per_1", "reply" to "once"),
        )
        assertNull(status.pending)
        assertEquals(StatusTracker.Phase.RUNNING, status.phase)
        assertEquals("approved", status.activity)
    }

    @Test
    fun activeResyncRecoversAfterAStreamGap() {
        val tracker = StatusTracker()
        assertEquals(StatusTracker.Phase.IDLE, tracker.setActive(emptySet()).phase)
        val status = tracker.setActive(setOf("ses_9"), now = 500)
        assertEquals(StatusTracker.Phase.RUNNING, status.phase)
        assertEquals("ses_9", status.sessionID)
        assertEquals(500, status.startedAt)
    }

    @Test
    fun pendingSurvivesActiveResync() {
        val tracker = StatusTracker()
        tracker.onEvent(
            event("permission.asked", "id" to "per_1", "sessionID" to "ses_1", "resources" to listOf("rm -rf build")),
        )
        val status = tracker.setActive(setOf("ses_1"))
        assertEquals(StatusTracker.Phase.WAITING, status.phase)
        assertEquals("per_1", status.pending?.requestID)
    }

    @Test
    fun completionIsIdle() {
        val tracker = StatusTracker()
        tracker.onEvent(event("session.execution.started", "sessionID" to "ses_1"))
        val status = tracker.onEvent(event("session.execution.succeeded", "sessionID" to "ses_1"))
        assertEquals(StatusTracker.Phase.IDLE, status.phase)
        assertTrue(status.activity == "done")
    }
}
