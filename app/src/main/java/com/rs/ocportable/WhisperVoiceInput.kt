package com.rs.ocportable

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Hold-to-talk fallback that records a PCM clip and transcribes it with the bundled model.
 *
 * Recording starts immediately; the model load runs on the worker in parallel, so the first
 * utterance only pays the load cost at the end. Nothing is auto-sent.
 */
class WhisperVoiceInput(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var output: ByteArrayOutputStream? = null
    private var callback: VoiceInput.Callback? = null
    @Volatile private var recording = false

    fun start(callback: VoiceInput.Callback): Boolean {
        if (recording) return true
        this.callback = callback
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            callback.onError("Microphone is unavailable.")
            return false
        }
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 32_000),
            )
        } catch (e: Exception) {
            callback.onError(e.message ?: "Could not open the microphone.")
            return false
        }
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            callback.onError("Could not initialise the microphone.")
            return false
        }
        val buffer = ByteArrayOutputStream()
        output = buffer
        record = input
        recording = true
        return try {
            input.startRecording()
            reader = Thread {
                val chunk = ByteArray(4_096)
                while (recording) {
                    val read = input.read(chunk, 0, chunk.size)
                    if (read > 0) buffer.write(chunk, 0, read)
                }
            }.also { it.start() }
            worker.execute {
                // Warm the model while the user is still speaking.
                runCatching { Whisper.ensure(activity) }
            }
            true
        } catch (e: Exception) {
            recording = false
            input.release()
            record = null
            callback.onError(e.message ?: "Could not start recording.")
            false
        }
    }

    fun stop() {
        if (!recording) return
        recording = false
        runCatching { record?.stop() }
        runCatching { reader?.join(1_500) }
        runCatching { record?.release() }
        record = null
        reader = null
        val bytes = output?.toByteArray() ?: ByteArray(0)
        output = null
        val pcm = toShorts(bytes)
        if (pcm.size < 8_000) {
            callback?.onError("Too short — hold the button while you speak.")
            callback = null
            return
        }
        worker.execute {
            val text = try {
                if (!Whisper.ensure(activity)) "" else Whisper.transcribe(pcm)
            } catch (e: Throwable) {
                ""
            }
            main.post {
                if (text.isNullOrBlank()) {
                    callback?.onError("Could not transcribe that.")
                } else {
                    callback?.onFinal(text)
                }
                callback = null
            }
        }
    }

    fun destroy() {
        recording = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        callback = null
        worker.shutdownNow()
    }

    private fun toShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
