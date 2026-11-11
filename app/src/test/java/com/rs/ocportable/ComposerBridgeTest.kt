package com.rs.ocportable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerBridgeTest {
    @Test
    fun jsStringEscapesQuotesAndNewlines() {
        assertEquals("\"a\\\"b\\nc\"", ComposerBridge.jsString("a\"b\nc"))
    }

    @Test
    fun setTextUsesTheInstalledFunction() {
        val script = ComposerBridge.setTextScript("hello")
        assertTrue(script.contains("window.__oc_setComposerText"))
        assertTrue(script.contains("\"hello\""))
    }

    @Test
    fun installDefinesTheComposerFunction() {
        assertTrue(ComposerBridge.INSTALL.contains("window.__oc_setComposerText"))
        assertTrue(ComposerBridge.INSTALL.contains("InputEvent"))
    }

    @Test
    fun cssInjectionCreatesAStyleElement() {
        assertTrue(ComposerBridge.CSS.contains("oc-portable-css"))
        assertTrue(ComposerBridge.CSS.contains("touch-action"))
    }
}
