package com.rs.ocportable

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkieAudioTest {
    @Test
    fun silenceDecodesToZero() {
        assertEquals(0, WalkieAudio.decode(byteArrayOf(0xFF.toByte()))[0].toInt())
    }

    @Test
    fun upsampleDoublesLength() {
        val out = WalkieAudio.upsample(shortArrayOf(100, 200))
        assertEquals(4, out.size)
        assertEquals(100, out[0].toInt())
        assertEquals(150, out[1].toInt())
        assertEquals(200, out[2].toInt())
        assertEquals(200, out[3].toInt())
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(0, WalkieAudio.upsample(ShortArray(0)).size)
    }
}
