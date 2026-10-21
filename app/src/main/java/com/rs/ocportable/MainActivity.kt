package com.rs.ocportable

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * First-run host. Unpacks the rootfs if it is missing or stale, then reports what it found.
 *
 * The WebView, foreground service and engine start (P1-5, P1-6) attach here once they exist; for
 * now this is the surface that makes the extraction's progress visible, which is the part of P1-4
 * that cannot be verified without a device.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 20f }
        detail = TextView(this).apply { textSize = 13f }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(bar)
            addView(detail)
        })

        val manager = RootfsManager(
            filesDir = filesDir,
            assetBytes = { assets.openFd(RootfsManager.ASSET).use { it.length } },
        ) { progress ->
            ui.post {
                bar.progress = (progress.fraction * bar.max).toInt()
                detail.text = "${progress.written / 1_048_576} / ${progress.total / 1_048_576} MiB"
            }
        }
        when (manager.state()) {
            RootfsManager.State.Ready -> {
                bar.visibility = android.view.View.GONE
                render("Ready", describe())
            }
            RootfsManager.State.Absent, RootfsManager.State.Stale -> extract(manager)
        }
    }

    private fun extract(manager: RootfsManager) {
        status.text = "Unpacking…"
        worker.execute {
            try {
                manager.ensure { assets.open(RootfsManager.ASSET) }
                ui.post { bar.visibility = android.view.View.GONE; render("Ready", describe()) }
            } catch (e: Throwable) {
                ui.post {
                    bar.visibility = android.view.View.GONE
                    // Naming the failure matters: an extraction error otherwise surfaces later as a
                    // proot failure, which points at the wrong component.
                    render("Extraction failed", "${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun render(headline: String, body: String) {
        status.text = headline
        detail.text = body
    }

    private fun describe(): String = buildString {
        val rootfs = java.io.File(filesDir, "rootfs")
        appendLine("rootfs: ${rootfs.absolutePath}")
        appendLine("stamp:  ${BuildConfig.ROOTFS_SHA256.take(16)}…")
        appendLine("bin/sh: ${java.nio.file.Files.isSymbolicLink(java.io.File(rootfs, "bin/sh").toPath())}")
        appendLine("size:   ${rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576} MiB")
        appendLine()
        appendLine("abi=${android.os.Build.SUPPORTED_ABIS.first()}  sdk=${android.os.Build.VERSION.SDK_INT}")
        append("nativeLibraryDir=${applicationInfo.nativeLibraryDir}")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
