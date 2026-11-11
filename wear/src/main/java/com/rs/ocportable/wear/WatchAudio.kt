package com.rs.ocportable.wear

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records a push-to-talk clip at 8 kHz and compresses it to µ-law: 8 KB/s, so a 10-second
 * utterance is 80 KB instead of 320 KB of raw 16-bit PCM. The phone decodes it and runs the
 * bundled whisper model.
 */
class WatchAudio {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var output: ByteArrayOutputStream? = null
    @Volatile private var recording = false

    fun start(): Boolean {
        if (recording) return true
        val rate = 8_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 16_000),
            )
        } catch (_: Exception) {
            return false
        }
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            return false
        }
        val buffer = ByteArrayOutputStream()
        output = buffer
        record = input
        recording = true
        return try {
            input.startRecording()
            reader = Thread {
                val chunk = ShortArray(1_024)
                while (recording) {
                    val read = input.read(chunk, 0, chunk.size)
                    for (i in 0 until read.coerceAtLeast(0)) {
                        buffer.write(encode(chunk[i]).toInt() and 0xFF)
                    }
                }
            }.also { it.start() }
            true
        } catch (_: Exception) {
            recording = false
            input.release()
            record = null
            false
        }
    }

    fun stop(): ByteArray {
        if (!recording && output == null) return ByteArray(0)
        recording = false
        runCatching { record?.stop() }
        runCatching { reader?.join(1_500) }
        runCatching { record?.release() }
        record = null
        reader = null
        val bytes = output?.toByteArray() ?: ByteArray(0)
        output = null
        return bytes
    }

    fun cancel() {
        recording = false
        runCatching { record?.stop() }
        runCatching { reader?.join(500) }
        runCatching { record?.release() }
        record = null
        reader = null
        output = null
    }

    private fun encode(sample: Short): Byte {
        val bias = 0x84
        val clip = 32635
        var value = sample.toInt()
        val sign = if (value < 0) 0x80 else 0
        if (value < 0) value = -value
        if (value > clip) value = clip
        value += bias
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (value and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (value shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}
