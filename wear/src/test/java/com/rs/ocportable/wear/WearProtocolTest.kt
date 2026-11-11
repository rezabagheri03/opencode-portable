package com.rs.ocportable.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {
    @Test
    fun parsesPendingStatus() {
        val json = """
            {"type":"status","phase":"waiting","activity":"waiting for approval","startedAt":1000,
             "elapsed":3000,"sessionID":"ses_1",
             "pending":{"sessionID":"ses_1","requestID":"per_1","text":"git push"}}
        """.trimIndent()
        val status = WearProtocol.parseStatus(json)
        assertEquals("waiting", status?.phase)
        assertEquals("per_1", status?.pending?.requestID)
        assertEquals("git push", status?.pending?.text)
    }

    @Test
    fun missingPendingIsNull() {
        val status = WearProtocol.parseStatus("""{"type":"status","phase":"idle","activity":"done"}""")
        assertEquals("idle", status?.phase)
        assertNull(status?.pending)
    }

    @Test
    fun malformedStatusIsNull() {
        assertNull(WearProtocol.parseStatus(null))
        assertNull(WearProtocol.parseStatus("not json"))
        assertNull(WearProtocol.parseStatus("""{"type":"command"}"""))
    }

    @Test
    fun commandCarriesTheDecision() {
        val command = WearProtocol.commandJson("approve", "ses_1", "per_1")
        assertEquals(true, command.contains("\"type\":\"command\""))
        assertEquals(true, command.contains("\"action\":\"approve\""))
        assertEquals(true, command.contains("\"requestID\":\"per_1\""))
    }
}
