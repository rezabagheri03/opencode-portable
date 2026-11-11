package com.rs.ocportable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun statusCarriesPendingDecision() {
        val status = StatusTracker.AgentStatus(
            phase = StatusTracker.Phase.WAITING,
            activity = "waiting for approval",
            startedAt = 1_000,
            sessionID = "ses_1",
            pending = StatusTracker.PendingDecision("ses_1", "per_1", "git push"),
        )
        val json = JSONObject(WatchProtocol.statusJson(status, now = 4_000))
        assertEquals("status", json.getString("type"))
        assertEquals("waiting", json.getString("phase"))
        assertEquals(3_000, json.getLong("elapsed"))
        assertEquals("git push", json.getJSONObject("pending").getString("text"))
    }

    @Test
    fun statusWithoutPendingUsesNull() {
        val json = JSONObject(WatchProtocol.statusJson(StatusTracker.AgentStatus()))
        assertTrue(json.isNull("pending"))
        assertTrue(json.isNull("sessionID"))
    }

    @Test
    fun commandRoundTrips() {
        val command = WatchProtocol.parseCommand(
            WatchProtocol.commandJson("approve", "ses_1", "per_1"),
        )
        assertEquals("approve", command?.action)
        assertEquals("ses_1", command?.sessionID)
        assertEquals("per_1", command?.requestID)
    }

    @Test
    fun promptRoundTrips() {
        val command = WatchProtocol.parseCommand(WatchProtocol.commandJson("prompt", text = "run the tests"))
        assertEquals("prompt", command?.action)
        assertEquals("run the tests", command?.text)
    }

    @Test
    fun malformedCommandIsNull() {
        assertNull(WatchProtocol.parseCommand("{\"type\":\"status\"}"))
        assertNull(WatchProtocol.parseCommand("not json"))
    }
}
