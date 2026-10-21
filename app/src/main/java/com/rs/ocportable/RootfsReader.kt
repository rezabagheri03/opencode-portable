package com.rs.ocportable

import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Reads a USTAR archive from a (gzip) stream and materialises it on disk.
 *
 * Android ships no `tar`, and the rootfs lives *inside* the APK, so extraction has to happen here.
 * The parser is deliberately narrow: `tools/rootfs/build.py` writes USTAR explicitly and
 * `verify.py` rejects anything that would need a PAX or GNU extension, so unrecognised headers are
 * a build fault rather than something to tolerate at runtime.
 *
 * Only member types 0 (file), 1 (hard link), 2 (symlink) and 5 (directory) occur in the archive.
 */
class RootfsReader(
    private val input: InputStream,
    private val root: File,
    /** Called with (bytesWrittenSoFar, totalBytes) as members are unpacked. */
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
) : Closeable {

    private var written = 0L
    private var total = 0L
    private val header = ByteArray(BLOCK)
    private val padding = ByteArray(BLOCK)

    /** @param totalBytes uncompressed size, used only to make progress meaningful. */
    fun withTotal(totalBytes: Long): RootfsReader = apply { total = totalBytes }

    fun extract() {
        var pendingHardLink: Pair<String, String>? = null
        while (true) {
            if (!readFully(header)) break
            if (isZeroBlock(header)) {
                // One zero block ends the archive; the second is optional padding.
                break
            }
            val name = cString(header, 0, 100)
            if (name.isEmpty()) break

            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = cString(header, 157, 100)
            val prefix = cString(header, 345, 155)
            val path = if (prefix.isEmpty()) name else "$prefix/$name"

            // Defer hard links until the whole tree exists, so the target's directory is present
            // regardless of member order.
            if (type == '1') {
                pendingHardLink?.let { materialiseHardLink(it) }
                pendingHardLink = path to linkName
                skip(size)
                continue
            }
            pendingHardLink?.let { materialiseHardLink(it); pendingHardLink = null }

            val target = resolve(path)
            when (type) {
                '5' -> target.mkdirs()
                '2' -> {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    // The target string must be written exactly as the archive holds it: "/bin/busybox"
                    // is a *guest* path that only resolves inside proot. Running the archive's text
                    // through the host's Path API rewrites it to "\bin\busybox" on Windows, which would
                    // ship a rootfs with a dangling shell. `Paths.get` splits on the host separator,
                    // so the archive's own separator is restored for every non-leading segment.
                    java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                }
                '0' -> writeFile(target, size)
                else -> throw IllegalStateException("unsupported tar member type '$type': $path")
            }
            // Members are padded to a 512-byte boundary.
            val remainder = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
            if (remainder > 0 && !readFully(padding, remainder)) {
                throw IllegalStateException("truncated archive after $path")
            }
            onProgress(written, if (total > 0) total else written)
        }
        pendingHardLink?.let { materialiseHardLink(it) }
    }

    private fun materialiseHardLink(link: Pair<String, String>) {
        val (path, linkName) = link
        val target = resolve(path)
        val source = resolve(linkName)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.exists()) throw IllegalStateException("hard link target missing: $linkName")
        java.nio.file.Files.createLink(target.toPath(), source.toPath())
    }

    private fun writeFile(target: File, size: Long) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            var remaining = size
            val buffer = ByteArray(COPY_BUFFER)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IllegalStateException("truncated archive in ${target.name}")
                out.write(buffer, 0, read)
                remaining -= read
                written += read
            }
        }
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(true, false)
    }

    /**
     * Rejects traversal. Member names are relative, and ".." or an absolute path would let a
     * malformed archive write outside the rootfs — the archive is trusted, but the check is cheap
     * and this is the one place where being wrong is unrecoverable.
     */
    private fun resolve(path: String): File {
        val clean = path.trimStart('/')
        require(!clean.split('/').contains("..")) { "path traversal in archive: $path" }
        val file = File(root, clean)
        require(file.canonicalPath.startsWith(root.canonicalPath)) { "escapes rootfs: $path" }
        return file
    }

    private fun skip(size: Long) {
        var remaining = size
        val buffer = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("truncated archive")
            remaining -= read
        }
    }

    private fun readFully(buffer: ByteArray, length: Int = buffer.size): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun isZeroBlock(block: ByteArray) = block.all { it == 0.toByte() }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, bytes.size)
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    /** Octal fields are NUL- or space-terminated ASCII digits. */
    private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
        val text = cString(bytes, offset, length).trim().trimEnd(' ')
        if (text.isEmpty()) return 0
        return text.toLong(8)
    }

    override fun close() = input.close()

    companion object {
        const val BLOCK = 512
        private const val COPY_BUFFER = 64 * 1024
    }
}
