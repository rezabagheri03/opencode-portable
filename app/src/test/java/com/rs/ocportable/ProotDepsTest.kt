package com.rs.ocportable

import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The proot dependency blob is a second tar inside the APK. If its format or members drift, proot
 * fails at exec with a loader error that looks like a rootfs problem.
 */
class ProotDepsTest {
    private val asset: File = File(System.getProperty("user.dir") ?: ".")
        .resolve("src/main/assets/proot-deps.dat")

    @Test
    fun extractsBothBionicDependencies() {
        assumeTrue("no staged proot-deps.dat at $asset", asset.isFile)
        val out = createTempDir()
        try {
            GZIPInputStream(FileInputStream(asset)).use { gz ->
                RootfsReader(gz, out).extract()
            }
            assertTrue(File(out, "libtalloc.so.2").isFile)
            assertTrue(File(out, "libandroid-shmem.so").isFile)
        } finally {
            out.deleteRecursively()
        }
    }
}
