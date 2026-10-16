package com.jtech.zemer.utils.mp3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure PCM helpers for the MP3 export: turn a decoder output buffer into interleaved 16-bit samples
 * and fold anything wider than stereo down to two channels (MP3 carries at most two).
 */
object PcmFrames {
    /** android.media.AudioFormat encoding constants, duplicated so this stays JVM-testable. */
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_FLOAT = 4
    const val ENCODING_PCM_24BIT_PACKED = 21
    const val ENCODING_PCM_32BIT = 22

    fun isSupported(encoding: Int): Boolean = encoding in SUPPORTED

    /**
     * Reads every sample in [buffer] (position..limit, native byte order expected) as signed 16-bit.
     * Throws [IllegalArgumentException] for an encoding we cannot convert, never guesses.
     */
    fun toPcm16(buffer: ByteBuffer, encoding: Int): ShortArray {
        val b = buffer.slice().order(ByteOrder.nativeOrder())
        return when (encoding) {
            ENCODING_PCM_16BIT -> {
                val shorts = b.asShortBuffer()
                ShortArray(shorts.remaining()).also(shorts::get)
            }
            ENCODING_PCM_FLOAT -> {
                val floats = b.asFloatBuffer()
                ShortArray(floats.remaining()) { i ->
                    (floats.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
                }
            }
            ENCODING_PCM_8BIT -> ShortArray(b.remaining()) { i -> (((b.get(i).toInt() and 0xFF) - 128) shl 8).toShort() }
            ENCODING_PCM_24BIT_PACKED -> {
                val count = b.remaining() / 3
                ShortArray(count) { i ->
                    val at = i * 3
                    // Little-endian packed 24-bit; keep the top 16 bits.
                    val value = (b.get(at).toInt() and 0xFF) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or
                        (b.get(at + 2).toInt() shl 16)
                    (value shr 8).toShort()
                }
            }
            ENCODING_PCM_32BIT -> {
                val ints = b.asIntBuffer()
                ShortArray(ints.remaining()) { i -> (ints.get(i) shr 16).toShort() }
            }
            else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding")
        }
    }

    /**
     * Folds interleaved [channels]-channel audio to stereo (or passes mono/stereo through untouched).
     * Front-left/right keep full weight; every other channel (center, surround, LFE) is shared equally
     * into both sides at half weight, then the result is scaled so it cannot clip.
     */
    fun downmixToStereo(samples: ShortArray, channels: Int): ShortArray {
        require(channels >= 1) { "channels must be positive" }
        if (channels <= 2) return samples
        val frames = samples.size / channels
        val out = ShortArray(frames * 2)
        val sharedWeight = 0.5f
        val norm = 1f / (1f + sharedWeight * (channels - 2))
        for (f in 0 until frames) {
            val base = f * channels
            var shared = 0f
            for (c in 2 until channels) shared += samples[base + c]
            shared *= sharedWeight
            out[f * 2] = ((samples[base] + shared) * norm).roundToInt().coerceIn(-32768, 32767).toShort()
            out[f * 2 + 1] = ((samples[base + 1] + shared) * norm).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private val SUPPORTED = setOf(
        ENCODING_PCM_16BIT,
        ENCODING_PCM_8BIT,
        ENCODING_PCM_FLOAT,
        ENCODING_PCM_24BIT_PACKED,
        ENCODING_PCM_32BIT,
    )
}
