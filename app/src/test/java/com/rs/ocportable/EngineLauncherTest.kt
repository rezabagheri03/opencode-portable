package com.rs.ocportable

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class EngineLauncherTest {
    private val layout = EngineLauncher.Layout(File("/data/data/p/files"), File("/data/app/lib/arm64"))
    private val cmd = EngineLauncher.command(layout, listOf("1.1.1.1", "8.8.8.8"), "pw")

    @Test
    fun prootIsTheExecutable() = assertTrue(cmd[0].endsWith("libproot.so"))

    @Test
    fun serveFlagsArePresent() {
        assertTrue(cmd.toString(), cmd.containsAll(listOf("serve", "--hostname", "127.0.0.1", "--port", "4096")))
    }

    @Test
    fun passwordReachesTheEngine() = assertTrue("OPENCODE_PASSWORD=pw" in cmd)

    @Test
    fun everyEnvFlagHasAValue() {
        val opens = cmd.count { it == "-u" }
        assertEquals(4, opens)
        cmd.forEachIndexed { i, arg -> if (arg == "-u") assertTrue("flag without value: $arg", "=" in cmd[i + 1]) }
    }

    @Test
    fun workspaceIsBoundOnce() = assertEquals(1, cmd.count { it.contains(":/workspace") })

    @Test
    fun resolvConfIsWellFormed() {
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", EngineLauncher.resolvConf(listOf("1.1.1.1", "8.8.8.8")))
    }
}
