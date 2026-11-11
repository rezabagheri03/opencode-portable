package com.rs.ocportable

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the manager against the real built rootfs. The important regression is the shell
 * check: `/bin/sh` is a guest-absolute symlink, so a host `exists()` would report false on stock
 * Android and re-extract on every launch.
 */
class RootfsManagerTest {
    private val archive: File = File(System.getProperty("user.dir") ?: ".")
        .parentFile
        ?.resolve("dist/rootfs/rootfs.tar.gz")
        ?: File("dist/rootfs/rootfs.tar.gz")

    @Test
    fun extractsThenReportsReadyAndSkipsTheSecondRun() {
        assumeTrue("no built rootfs at $archive", archive.isFile)
        val files = createTempDir()
        try {
            val manager = RootfsManager(files, { archive.length() })
            manager.ensure { FileInputStream(archive) }

            assertEquals(RootfsManager.State.Ready, manager.state())
            assertTrue(File(files, "rootfs/bin/busybox").isFile)
            assertTrue(Files.isSymbolicLink(File(files, "rootfs/bin/sh").toPath()))

            var opened = false
            manager.ensure {
                opened = true
                throw AssertionError("should not extract again")
            }
            assertFalse(opened)
        } finally {
            files.deleteRecursively()
        }
    }
}
