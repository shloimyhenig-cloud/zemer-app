package com.zemer.lame

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3StreamEncoderTest {

    @Test
    fun `stereo 44_1 kHz PCM becomes MPEG-1 Layer III frames at the requested bitrate`() {
        val mp3 = encodeTone(sampleRate = 44_100, channels = 2, seconds = 2)
        val header = firstFrameHeader(mp3, 0)
        assertEquals("MPEG version bits (11 = MPEG-1)", 0b11, (header ushr 19) and 0b11)
        assertEquals("layer bits (01 = Layer III)", 0b01, (header ushr 17) and 0b11)
        assertEquals("bitrate index 1011 = 192 kbps in MPEG-1 L3", 0b1011, (header ushr 12) and 0b1111)
        assertEquals("sample-rate index 00 = 44.1 kHz", 0b00, (header ushr 10) and 0b11)
        assertTrue("frames for ~2 s of audio", countFrames(mp3, 0) in 70..90)
    }

    @Test
    fun `48 kHz mono (the Opus decode rate) encodes as mono MPEG-1`() {
        val mp3 = encodeTone(sampleRate = 48_000, channels = 1, seconds = 1)
        val header = firstFrameHeader(mp3, 0)
        assertEquals(0b01, (header ushr 10) and 0b11) // 48 kHz
        assertEquals("channel mode 11 = mono", 0b11, (header ushr 6) and 0b11)
    }

    @Test
    fun `tag goes first and the audio after it still starts on a frame boundary`() {
        val tag = Id3v2Tag(title = "Shabbos", artist = "Avraham Fried", album = "Classics").toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        Mp3StreamEncoder(44_100, 2, out).use { encoder ->
            encoder.encode(tone(44_100, 2, 1))
            encoder.finish()
        }
        val bytes = out.toByteArray()
        assertArrayEquals("ID3".toByteArray(), bytes.copyOfRange(0, 3))
        val tagSize = 10 + syncSafeToInt(bytes, 6)
        assertEquals(tag.size, tagSize)
        firstFrameHeader(bytes, tagSize)
    }

    @Test
    fun `decoded audio keeps the input level (no clipping or scaling error)`() {
        val rate = 44_100
        val input = tone(rate, 2, 2)
        val decoded = decode(encodeTone(sampleRate = rate, channels = 2, seconds = 2))
        // Skip the encoder/decoder delay and the flushed tail; compare steady-state RMS.
        val steady = decoded.copyOfRange(rate / 2, rate * 3 / 2)
        val inRms = rms(input.copyOfRange(rate, rate * 2))
        val outRms = rms(steady)
        val ratioDb = 20 * kotlin.math.log10(outRms / inRms)
        assertTrue("level changed by $ratioDb dB", kotlin.math.abs(ratioDb) < 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `more than two channels is rejected rather than silently down-mixed`() {
        Mp3StreamEncoder(48_000, 6, ByteArrayOutputStream())
    }

    private fun encodeTone(sampleRate: Int, channels: Int, seconds: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Mp3StreamEncoder(sampleRate, channels, out).use { encoder ->
            val pcm = tone(sampleRate, channels, seconds)
            // Feed in uneven pieces, as a decoder does.
            var frame = 0
            val total = pcm.size / channels
            while (frame < total) {
                val n = minOf(1_000 + frame % 777, total - frame)
                encoder.encode(pcm, frame * channels, n)
                frame += n
            }
            encoder.finish()
        }
        return out.toByteArray()
    }

    private fun tone(sampleRate: Int, channels: Int, seconds: Int): ShortArray {
        val frames = sampleRate * seconds
        return ShortArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / sampleRate
            (sin(2 * PI * 440 * t) * 12_000).toInt().toShort()
        }
    }

    /** Decodes with the vendored mpglib; returns the left channel. */
    private fun decode(mp3: ByteArray): ShortArray {
        val common = com.zemer.lame.mpg.Common()
        val vbrTag = com.zemer.lame.mp3.VBRTag()
        val decoderInterface = com.zemer.lame.mpg.Interface().apply { setModules(vbrTag, common) }
        val mpg = com.zemer.lame.mpg.MPGLib().apply { setModules(decoderInterface, common) }
        val hip = mpg.hip_decode_init()
        val left = ShortArray(1152)
        val right = ShortArray(1152)
        val data = com.zemer.lame.mp3.MP3Data()
        val out = ArrayList<Short>()
        var pos = 0
        while (true) {
            val len = minOf(1024, mp3.size - pos)
            val chunk = mp3.copyOfRange(pos, pos + len)
            pos += len
            var n = mpg.hip_decode1_headers(hip, chunk, len, left, right, data)
            while (n > 0) {
                for (i in 0 until n) out.add(left[i])
                n = mpg.hip_decode1_headers(hip, chunk, 0, left, right, data)
            }
            if (len == 0) break
        }
        mpg.hip_decode_exit(hip)
        return out.toShortArray()
    }

    private fun rms(samples: ShortArray, stride: Int = 1): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < samples.size) {
            sum += samples[i].toDouble() * samples[i]
            count++
            i += stride
        }
        return kotlin.math.sqrt(sum / count)
    }

    private fun firstFrameHeader(bytes: ByteArray, from: Int): Int {
        assertTrue("no audio", bytes.size > from + 4)
        val header = readInt(bytes, from)
        assertEquals("frame sync", 0x7FF, header ushr 21)
        return header
    }

    // Walks the frame chain; a real MP3 is back-to-back frames whose lengths follow from each header.
    private fun countFrames(bytes: ByteArray, from: Int): Int {
        var pos = from
        var frames = 0
        while (pos + 4 <= bytes.size) {
            val h = readInt(bytes, pos)
            if (h ushr 21 != 0x7FF) break
            val bitrate = MPEG1_L3_KBPS[(h ushr 12) and 0xF]
            val rate = intArrayOf(44_100, 48_000, 32_000)[(h ushr 10) and 0x3]
            val padding = (h ushr 9) and 1
            pos += 144 * bitrate * 1000 / rate + padding
            frames++
        }
        assertEquals("frame chain must end exactly at EOF", bytes.size, pos)
        return frames
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun syncSafeToInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private companion object {
        val MPEG1_L3_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    }
}
