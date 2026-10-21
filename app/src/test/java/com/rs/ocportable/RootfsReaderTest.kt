package com.rs.ocportable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Extracts the real bundled rootfs and checks the result.
 *
 * This is the only way to exercise the parser against something representative: the archive has
 * 1,636 members including 520 symlinks and 254 hard links. It runs against `dist/rootfs/` rather
 * than a fixture, so a rootfs change is tested without maintaining a second copy.
 */
class RootfsReaderTest {

    private val archive = File(System.getProperty("user.dir")).parentFile
        .resolve("dist/rootfs/rootfs.tar.gz")

    private fun extractTo(dir: File): File {
        try {
            GZIPInputStream(FileInputStream(archive), 64 * 1024).use { gz ->
                RootfsReader(gz, dir).extract()
            }
        } catch (e: Throwable) {
            throw AssertionError("extraction failed: ${e::class.simpleName}: ${e.message}", e)
        }
        return dir
    }

    @Test
    fun extractsTheBundledRootfs() {
        assumeTrue("no built rootfs at $archive", archive.isFile)
        val out = createTempDir()
        try {
            extractTo(out)

            // The engine cannot start without these, and each has failed silently in a real bug:
            // the loader is its PT_INTERP, libstdc++ is its other DT_NEEDED.
            //
            // Symlinks are checked with isSymbolicLink, not exists(): /bin/sh points at /bin/busybox,
            // an absolute *guest* path that only resolves inside proot. On the host Java resolves
            // the link and reports exists()=false, which is correct and not a failure.
            assertTrue("missing symlink /bin/sh", isLink(File(out, "bin/sh")))
            assertTrue("missing busybox", File(out, "bin/busybox").isFile)
            assertTrue("missing loader", isLink(File(out, "lib/ld-musl-aarch64.so.1")) || File(out, "lib/ld-musl-aarch64.so.1").isFile)
            assertTrue("missing libstdc++", File(out, "usr/lib/libstdc++.so.6").exists() || isLink(File(out, "usr/lib/libstdc++.so.6")))
            assertTrue("missing rg", File(out, "usr/bin/rg").isFile)
            assertTrue("missing ca-certs", File(out, "etc/ssl/certs/ca-certificates.crt").isFile)

            // Every symlink must resolve to something inside the tree, except the runtime mounts.
            val root = out.canonicalFile
            val unresolved = out.walkTopDown()
                .filter { java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .map { it to java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }
                .filterNot { (link, target) -> target.startsWith("/") || resolveWithin(root, link, target) != null }
                .map { (link, target) -> "${link.relativeTo(out)} -> $target" }
                .toList()
            assertEquals("broken symlinks: $unresolved", emptyList<String>(), unresolved)
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * The link target text is the part Windows cannot preserve: its Path API reinterprets a
     * leading `/` and rewrites separators, so a Windows host would write "\bin\busybox" and ship
     * a rootfs whose shell dangles. CI runs this on Linux, where the exact archive text must
     * survive — that is the environment the device resembles.
     */
    @Test
    fun symlinkTargetsAreExactOnLinux() {
        assumeTrue("no built rootfs at $archive", archive.isFile)
        assumeTrue("only meaningful on a POSIX host", File.separatorChar == '/')
        val out = createTempDir()
        try {
            extractTo(out)
            assertEquals("/bin/busybox", readLink(File(out, "bin/sh")))
            assertEquals("/bin/busybox", readLink(File(out, "bin/ash")))
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun hardLinksShareContent() {
        assumeTrue("no built rootfs at $archive", archive.isFile)
        val out = createTempDir()
        try {
            extractTo(out)
            val alias = File(out, "usr/share/zoneinfo/UTC")
            val canonical = File(out, "usr/share/zoneinfo/Etc/UCT")
            assertTrue("UTC/UCT missing", alias.exists() && canonical.exists())
            assertEquals("hard link did not copy content", canonical.readBytes().size, alias.readBytes().size)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val out = createTempDir()
        try {
            // A crafted member name must not escape the root.
            val evil = escapeArchive("../../escaped")
            java.io.ByteArrayInputStream(evil).use { stream ->
                var threw = false
                try {
                    RootfsReader(stream, out).extract()
                } catch (e: IllegalArgumentException) {
                    threw = true
                } catch (e: SecurityException) {
                    threw = true
                }
                assertTrue("traversal was not rejected", threw)
            }
            assertFalse("file escaped the root", File(out.parentFile, "escaped").exists())
        } finally {
            out.deleteRecursively()
        }
    }

    private fun escapeArchive(name: String): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000644\u00000000000\u00000000000\u00000000000000".toByteArray().copyInto(header, 100)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        header[262] = 0
        "00".toByteArray().copyInto(header, 263)
        // checksum
        val sum = header.sumOf { if (it < 0) it + 256 else it.toInt() }
        val chksum = "%06o\u0000 ".format(sum)
        chksum.toByteArray().copyInto(header, 148)
        return header
    }

    private fun isLink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())

    private fun readLink(f: File) = java.nio.file.Files.readSymbolicLink(f.toPath()).toString()

    private fun resolveWithin(root: File, link: File, target: String): File? {
        var current = link.parentFile
        for (part in target.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> current = current?.parentFile
                else -> current = current?.let { File(it, part) }
            }
        }
        val resolved = current ?: return null
        return if (resolved.canonicalPath.startsWith(root.canonicalPath)) resolved else null
    }
}
