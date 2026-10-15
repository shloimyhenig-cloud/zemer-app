package com.jtech.zemer.utils.mp3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3ExportPureTest {

    private fun meta(track: Int?, total: Int?, title: String = "Ani Maamin") = Mp3TrackMetadata(
        title = title,
        artist = "Avraham Fried",
        album = "No Jews Allowed",
        albumArtist = "Avraham Fried",
        year = 1987,
        trackNumber = track,
        trackTotal = total,
    )

    @Test
    fun `file name is zero-padded track number then title`() {
        assertEquals("03 - Ani Maamin.mp3", meta(3, 12).fileName())
        assertEquals("007 - Ani Maamin.mp3", meta(7, 120).fileName())
        assertEquals("Ani Maamin.mp3", meta(null, 12).fileName())
        assertEquals("Ani Maamin.mp3", meta(0, 12).fileName())
        // A total smaller than the number is ignored rather than producing "12/3".
        assertEquals("12 - Ani Maamin.mp3", meta(12, 3).fileName())
        assertEquals("01 - Track.mp3", meta(1, 9, title = "  ").fileName())
    }

    @Test
    fun `tag carries title artist album year and track position`() {
        val tag = meta(3, 12).toTag(coverArt = null)
        assertEquals("Ani Maamin", tag.title)
        assertEquals("Avraham Fried", tag.artist)
        assertEquals("No Jews Allowed", tag.album)
        assertEquals(1987, tag.year)
        assertEquals(3, tag.trackNumber)
        assertEquals(12, tag.trackTotal)
        assertNull(tag.coverArt)
        val bytes = tag.toByteArray()
        assertArrayEquals("ID3".toByteArray(), bytes.copyOfRange(0, 3))
        assertEquals("ID3v2.3", 3, bytes[3].toInt())
        val text = String(bytes, Charsets.UTF_16LE)
        assertTrue(text.contains("No Jews Allowed"))
        assertTrue(text.contains("3/12"))
    }

    @Test
    fun `16-bit PCM passes through and float PCM is scaled and clamped`() {
        val shorts = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder())
        shorts.putShort(1).putShort(-2).putShort(Short.MAX_VALUE).flip()
        assertArrayEquals(shortArrayOf(1, -2, Short.MAX_VALUE), PcmFrames.toPcm16(shorts, PcmFrames.ENCODING_PCM_16BIT))

        val floats = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        floats.putFloat(0.5f).putFloat(-2f).putFloat(1f).flip()
        assertArrayEquals(
            shortArrayOf(16384, -32767, 32767),
            PcmFrames.toPcm16(floats, PcmFrames.ENCODING_PCM_FLOAT),
        )
    }

    @Test
    fun `decoder buffer position is respected`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        buffer.putShort(9).putShort(8).putShort(7).putShort(6)
        buffer.position(4).limit(8)
        assertArrayEquals(shortArrayOf(7, 6), PcmFrames.toPcm16(buffer, PcmFrames.ENCODING_PCM_16BIT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown PCM encodings are rejected`() {
        PcmFrames.toPcm16(ByteBuffer.allocate(4), 999)
    }

    @Test
    fun `surround is folded to stereo without clipping and stereo is untouched`() {
        val stereo = shortArrayOf(1, 2, 3, 4)
        assertTrue(stereo === PcmFrames.downmixToStereo(stereo, 2))

        // One 5.1 frame (FL, FR, C, LFE, SL, SR) at full scale everywhere.
        val loud = ShortArray(6) { Short.MAX_VALUE }
        val folded = PcmFrames.downmixToStereo(loud, 6)
        assertEquals(2, folded.size)
        assertTrue(folded.all { it in 32_000..32_767 })

        // Center-only content reaches both sides equally.
        val center = shortArrayOf(0, 0, 10_000, 0, 0, 0)
        val c = PcmFrames.downmixToStereo(center, 6)
        assertEquals(c[0], c[1])
        assertTrue(c[0] > 0)
    }
}
