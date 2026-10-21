package com.rs.ocportable

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Owns the rootfs: decides whether it needs unpacking, unpacks it, and stamps the result.
 *
 * The stamp is the whole reason this class exists. A half-written rootfs is worse than none — the
 * engine starts, finds a broken `/bin/sh`, and fails with an error that points at proot rather than
 * at an interrupted extraction. So extraction goes to a scratch directory and is only moved into
 * place once complete, and a marker file records which build produced it.
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

    /**
     * A rootfs is usable only if the marker says it came from this build *and* the shell it needs
     * is actually there. The second check catches an extraction killed between the last member and
     * the stamp write, and any post-hoc corruption.
     */
    fun state(): State {
        if (!stamp.isFile) return if (rootfs.exists()) State.Stale else State.Absent
        if (stamp.readText().trim() != BuildConfig.ROOTFS_SHA256) return State.Stale
        return if (File(rootfs, "bin/sh").exists() && File(rootfs, "etc/alpine-release").isFile) {
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
    fun ensure(openAsset: () -> java.io.InputStream) {
        if (state() == State.Ready) return

        staging.deleteRecursively()
        staging.mkdirs()
        try {
            openAsset().use { raw ->
                GZIPInputStream(raw, 64 * 1024).use { gz ->
                    RootfsReader(gz, staging) { done, total ->
                        onProgress(Progress(done, total))
                    }.withTotal(assetBytes()).extract()
                }
            }
            require(File(staging, "bin/sh").exists()) { "archive produced no /bin/sh" }

            // Replace atomically-ish: the old tree is only removed once the new one is complete.
            val previous = File(filesDir, "rootfs.old")
            previous.deleteRecursively()
            if (rootfs.exists()) rootfs.renameTo(previous)
            if (!staging.renameTo(rootfs)) throw IllegalStateException("could not move rootfs into place")
            previous.deleteRecursively()

            stamp.writeText(BuildConfig.ROOTFS_SHA256)
        } catch (e: Throwable) {
            // Leave nothing half-written for the next launch to trust.
            staging.deleteRecursively()
            throw e
        }
    }

    /** Whether a first-run extraction would actually have work to do. */
    fun needsExtraction(): Boolean = state() != State.Ready

    companion object {
        private const val STAMP = ".stamp"

        fun forApp(context: Context): RootfsManager {
            val assets = context.assets
            return RootfsManager(
                filesDir = context.filesDir,
                assetBytes = { assets.openFd(ASSET).use { it.length } },
            )
        }

        const val ASSET = "rootfs.dat"
    }
}
