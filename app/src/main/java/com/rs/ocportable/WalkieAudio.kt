package com.rs.ocportable

import android.content.Context

/**
 * Decodes the watch's µ-law clip and runs it through the bundled model.
 *
 * µ-law is 8 kHz, 8 bits per sample. Whisper wants 16 kHz floats, so the PCM is linearly
 * upsampled 2x before transcription. This is the P3-6 bridge; the codec choice is still subject to
 * the P3-0 throughput measurement.
 */
object WalkieAudio {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun transcribe(context: Context, ulaw: ByteArray): String? {
        if (ulaw.isEmpty() || ulaw.size > MAX_BYTES) return null
        return try {
            if (!Whisper.ensure(context)) return null
            Whisper.transcribe(upsample(decode(ulaw)))
        } catch (_: Throwable) {
            null
        }
    }

    fun decode(ulaw: ByteArray): ShortArray {
        val out = ShortArray(ulaw.size)
        for (i in ulaw.indices) {
            val u = ulaw[i].toInt().inv() and 0xFF
            val t = (((u and 0x0F) shl 3) + 0x84) shl ((u and 0x70) shr 4)
            out[i] = if ((u and 0x80) != 0) (0x84 - t).toShort() else (t - 0x84).toShort()
        }
        return out
    }

    fun upsample(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val out = ShortArray(pcm.size * 2)
        for (i in pcm.indices) {
            out[i * 2] = pcm[i]
            out[i * 2 + 1] = if (i + 1 < pcm.size) {
                ((pcm[i].toInt() + pcm[i + 1].toInt()) / 2).toShort()
            } else {
                pcm[i]
            }
        }
        return out
    }
}
