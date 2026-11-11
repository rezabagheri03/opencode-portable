package com.rs.ocportable

import android.content.Context
import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Owns the rootfs: decides whether it needs unpacking, unpacks it, and stamps the result.
 *
 * The stamp is the whole reason this class exists. A half-written rootfs is worse than none — the
 * engine starts, finds a broken `/bin/sh`, and fails with an error that points at proot rather than
 * at an interrupted extraction. So extraction goes to a scratch directory and is only moved into
 * place once complete, and a marker file records which build produced it.
 *
 * Every shell check here deliberately avoids `File.exists()` on `bin/sh`: the archive stores it as
 * a symlink to the absolute *guest* path `/bin/busybox`, which Java would resolve against the
 * Android host. On stock Android that path does not exist, so `exists()` would report false after a
 * perfectly good extraction and re-extract on every launch.
 */
class RootfsManager(
    private val filesDir: File,
    private val assetBytes: () -> Long,
    private val onProgress: (Progress) -> Unit = {},
) {

    class Progress(val written: Long, val total: Long) {
        val fraction: Float get() = if (total <= 0) 0f else (written.toFloat() / total).coerceIn(0f, 1f)
    }

    enum class State {
        /** No rootfs and nothing to do yet. */
        Absent,

        /** A complete, stamped rootfs matching this build. */
        Ready,

        /** Present but from another build, or stamped but damaged. */
        Stale,
    }

    private val rootfs = File(filesDir, "rootfs")
    private val stamp = File(rootfs, STAMP)
    private val staging = File(filesDir, "rootfs.staging")

    val directory: File get() = rootfs

    /**
     * A rootfs is usable only if the marker says it came from this build *and* the files the
     * engine needs are actually there. `/bin/busybox` is a real file; `/bin/sh` is a guest-absolute
     * symlink, so it is checked without following it.
     */
    fun state(): State {
        if (!stamp.isFile) return if (rootfs.exists()) State.Stale else State.Absent
        if (stamp.readText().trim() != BuildConfig.ROOTFS_SHA256) return State.Stale
        val shell = File(rootfs, "bin/sh")
        val shellOk = Files.isSymbolicLink(shell.toPath()) || shell.isFile
        return if (File(rootfs, "bin/busybox").isFile && File(rootfs, "etc/alpine-release").isFile && shellOk) {
            State.Ready
        } else {
            State.Stale
        }
    }

    /**
     * Unpacks the bundled rootfs. Safe to call when already [State.Ready]; it returns immediately.
     *
     * @param openAsset supplies the asset stream; injected so this can be tested without Android.
     */
    fun ensure(openAsset: () -> InputStream) {
        if (state() == State.Ready) return

        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val total = assetBytes()
            openAsset().use { raw ->
                val counter = CountingInputStream(raw)
                GZIPInputStream(counter, 64 * 1024).use { gz ->
                    RootfsReader(gz, staging) { _, _ ->
                        onProgress(Progress(counter.count, total))
                    }.extract()
                }
            }
            // Never use exists() here: see the class comment.
            require(File(staging, "bin/busybox").isFile) { "archive produced no /bin/busybox" }
            val shell = File(staging, "bin/sh")
            require(Files.isSymbolicLink(shell.toPath()) || shell.isFile) { "archive produced no /bin/sh" }

            // Replace atomically-ish: the old tree is only removed once the new one is complete.
            val previous = File(filesDir, "rootfs.old")
            previous.deleteRecursively()
            if (rootfs.exists()) rootfs.renameTo(previous)
            if (!staging.renameTo(rootfs)) throw IllegalStateException("could not move rootfs into place")
            previous.deleteRecursively()

            stamp.writeText(BuildConfig.ROOTFS_SHA256)
            onProgress(Progress(total, total))
        } catch (e: Throwable) {
            // Leave nothing half-written for the next launch to trust.
            staging.deleteRecursively()
            throw e
        }
    }

    /** Whether a first-run extraction would actually have work to do. */
    fun needsExtraction(): Boolean = state() != State.Ready

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }
    }

    companion object {
        private const val STAMP = ".stamp"

        fun forApp(context: Context, onProgress: (Progress) -> Unit = {}): RootfsManager {
            val assets = context.assets
            return RootfsManager(
                filesDir = context.filesDir,
                assetBytes = { assets.openFd(ASSET).use { it.length } },
                onProgress = onProgress,
            )
        }

        const val ASSET = "rootfs.dat"
    }
}
