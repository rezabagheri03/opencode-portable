package com.rs.ocportable

import android.content.Context
import java.io.File

/**
 * Bundled English STT for audio that did not come from the phone's own microphone — the watch path
 * and the fallback when the device has no speech service. Model: ggml-tiny.en-q5_1.
 */
object Whisper {
    private const val ASSET = "whisper-model.bin"
    private const val FILE = "whisper-model.bin"

    @Volatile private var context = 0L

    init {
        System.loadLibrary("whisper_jni")
    }

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (this.context != 0L) return true
        val model = File(context.filesDir, FILE)
        if (!model.isFile || model.length() < 1_000_000L) {
            val temporary = File(context.filesDir, "$FILE.tmp")
            try {
                context.assets.open(ASSET).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (model.exists()) model.delete()
                if (!temporary.renameTo(model)) throw IllegalStateException("could not stage $ASSET")
            } catch (e: Throwable) {
                temporary.delete()
                throw e
            }
        }
        this.context = nativeInit(model.absolutePath)
        return this.context != 0L
    }

    @Synchronized
    fun transcribe(pcm: ShortArray): String? {
        if (context == 0L || pcm.isEmpty()) return null
        val text = nativeTranscribe(context, pcm, threads())
        return text.trim().ifBlank { null }
    }

    @Synchronized
    fun close() {
        if (context != 0L) {
            nativeFree(context)
            context = 0L
        }
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(context: Long)
    private external fun nativeTranscribe(context: Long, pcm: ShortArray, threads: Int): String
}
