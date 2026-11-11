package com.rs.ocportable

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStoreTest {
    @Test
    fun tokenUsesTheOpencodeUsername() {
        val decoded = String(Base64.getDecoder().decode(PasswordStore.token("pw")))
        assertEquals("opencode:pw", decoded)
    }

    @Test
    fun tokenUrlIsQuerySafe() {
        val encoded = PasswordStore.tokenUrl("pw")
        assertTrue(encoded.isNotBlank())
        assertTrue(!encoded.contains("+"))
        assertTrue(!encoded.contains("/"))
    }
}
