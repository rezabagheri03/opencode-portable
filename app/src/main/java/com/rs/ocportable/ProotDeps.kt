package com.rs.ocportable

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * proot is a Bionic binary with two non-system dependencies (`libtalloc.so.2`,
 * `libandroid-shmem.so`). Both are shipped as the gzipped tar `assets/proot-deps.dat` because AGP
 * only accepts `.so` files in `jniLibs`, and proot resolves them through `LD_LIBRARY_PATH`.
 */
object ProotDeps {
    private val REQUIRED = listOf("libtalloc.so.2", "libandroid-shmem.so")
    private const val ASSET = "proot-deps.dat"

    fun ensure(context: Context, layout: EngineLauncher.Layout) {
        if (REQUIRED.all { File(layout.prootLibs, it).isFile }) return

        layout.prootLibs.deleteRecursively()
        layout.prootLibs.mkdirs()
        context.assets.open(ASSET).use { raw ->
            GZIPInputStream(raw).use { gz ->
                RootfsReader(gz, layout.prootLibs).extract()
            }
        }
        REQUIRED.forEach {
            require(File(layout.prootLibs, it).isFile) { "$ASSET did not contain $it" }
        }
    }
}
