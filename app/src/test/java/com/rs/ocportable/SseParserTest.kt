package com.rs.ocportable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun oneDataFrame() {
        val parser = EngineClient.SseParser()
        assertNull(parser.feed("data: {\"type\":\"session.execution.started\"}"))
        assertEquals("{\"type\":\"session.execution.started\"}", parser.feed(""))
    }

    @Test
    fun heartbeatIsIgnored() {
        val parser = EngineClient.SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed(""))
    }

    @Test
    fun multilineDataIsJoined() {
        val parser = EngineClient.SseParser()
        parser.feed("data: one")
        parser.feed("data: two")
        assertEquals("one\ntwo", parser.feed(""))
    }

    @Test
    fun blankLineWithoutDataIsNull() {
        assertNull(EngineClient.SseParser().feed(""))
    }
}
