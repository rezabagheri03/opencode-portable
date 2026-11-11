package com.rs.ocportable

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class EngineLauncherTest {
    private val layout = EngineLauncher.Layout(File("/data/data/p/files"), File("/data/app/lib/arm64"))
    private val cmd = EngineLauncher.command(layout, "pw")

    @Test
    fun prootIsTheExecutable() = assertTrue(cmd[0].endsWith("libproot.so"))

    @Test
    fun prootCleansUpItsTracees() {
        assertTrue("--kill-on-exit" in cmd)
        assertTrue("--link2symlink" in cmd)
    }

    @Test
    fun serveFlagsArePresent() {
        assertTrue(cmd.toString(), cmd.containsAll(listOf("serve", "--hostname", "127.0.0.1", "--port", "4096")))
    }

    @Test
    fun passwordIsAssignedNotUnset() {
        assertTrue("OPENCODE_PASSWORD=pw" in cmd)
        assertFalse("-u" in cmd)
    }

    @Test
    fun everyEnvironmentAssignmentIsPresent() {
        val expected = listOf(
            "HOME=/root",
            "TMPDIR=/tmp",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "OPENCODE_PASSWORD=pw",
        )
        expected.forEach { assertTrue("missing $it", it in cmd) }
    }

    @Test
    fun workspaceIsBoundOnceAndUsedAsCwd() {
        assertEquals(1, cmd.count { it.contains(":/workspace") })
        assertEquals("/workspace", cmd[cmd.indexOf("-w") + 1])
    }

    @Test
    fun engineIsBoundFromNativeLibraryDir() {
        val bind = layout.engine.absolutePath + ":" + EngineLauncher.GUEST_ENGINE
        assertTrue("missing engine bind $bind", bind in cmd)
        assertEquals(EngineLauncher.GUEST_ENGINE, cmd[cmd.indexOf("serve") - 1])
    }

    @Test
    fun prootEnvironmentUsesTheExtractedDeps() {
        val env = EngineLauncher.environment(layout)
        assertEquals(layout.prootLibs.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals(layout.tmp.absolutePath, env["PROOT_TMP_DIR"])
    }

    @Test
    fun resolvConfIsWellFormed() {
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", EngineLauncher.resolvConf(listOf("1.1.1.1", "8.8.8.8")))
    }
}
