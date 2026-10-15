package com.zemer.lame

import java.io.ByteArrayOutputStream

/**
 * Builds an ID3v2.3 tag to prepend to an MP3 stream.
 *
 * v2.3 (not v2.4) on purpose: it is the version every player, car stereo, Windows Explorer and the
 * Android media scanner read reliably. Text frames are UTF-16 with BOM (encoding 1), the only Unicode
 * encoding v2.3 defines, so Hebrew/Yiddish titles survive intact.
 */
data class Id3v2Tag(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val genre: String? = null,
    /** JPEG or PNG bytes for the front cover (APIC type 3); null = no picture frame. */
    val coverArt: ByteArray? = null,
) {
    fun toByteArray(): ByteArray {
        val frames = ByteArrayOutputStream()
        textFrame("TIT2", title)?.let(frames::write)
        textFrame("TPE1", artist)?.let(frames::write)
        textFrame("TPE2", albumArtist)?.let(frames::write)
        textFrame("TALB", album)?.let(frames::write)
        textFrame("TYER", year?.takeIf { it in 1..9999 }?.toString()?.padStart(4, '0'))?.let(frames::write)
        textFrame("TRCK", trackText())?.let(frames::write)
        textFrame("TCON", genre)?.let(frames::write)
        coverArt?.takeIf { it.isNotEmpty() }?.let { frames.write(pictureFrame(it)) }

        val body = frames.toByteArray()
        val padded = body.size + PADDING_BYTES
        val out = ByteArrayOutputStream(HEADER_BYTES + padded)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(syncSafe(padded))
        out.write(body)
        out.write(ByteArray(PADDING_BYTES))
        return out.toByteArray()
    }

    private fun trackText(): String? {
        val number = trackNumber?.takeIf { it > 0 } ?: return null
        val total = trackTotal?.takeIf { it >= number }
        return if (total != null) "$number/$total" else number.toString()
    }

    // Pictures are compared by content so two tags with the same cover are equal.
    override fun equals(other: Any?): Boolean =
        other is Id3v2Tag &&
            title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && year == other.year && trackNumber == other.trackNumber &&
            trackTotal == other.trackTotal && genre == other.genre &&
            coverArt.contentEquals(other.coverArt)

    override fun hashCode(): Int =
        listOf(title, artist, album, albumArtist, year, trackNumber, trackTotal, genre).hashCode() * 31 +
            (coverArt?.contentHashCode() ?: 0)

    companion object {
        private const val HEADER_BYTES = 10
        private const val PADDING_BYTES = 256
        private const val ENCODING_UTF16_BOM: Byte = 1
        private const val PICTURE_TYPE_FRONT_COVER: Byte = 3

        private fun textFrame(id: String, value: String?): ByteArray? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val body = ByteArrayOutputStream()
            body.write(ENCODING_UTF16_BOM.toInt())
            body.write(utf16WithBom(text))
            return frame(id, body.toByteArray())
        }

        private fun pictureFrame(image: ByteArray): ByteArray {
            val mime = if (image.isPng()) "image/png" else "image/jpeg"
            val body = ByteArrayOutputStream(image.size + 32)
            body.write(0) // ISO-8859-1 for the (empty) description
            body.write(mime.toByteArray(Charsets.ISO_8859_1))
            body.write(0)
            body.write(PICTURE_TYPE_FRONT_COVER.toInt())
            body.write(0) // empty description, terminated
            body.write(image)
            return frame("APIC", body.toByteArray())
        }

        // v2.3 frame sizes are plain 32-bit big-endian (not sync-safe; that changed in v2.4).
        private fun frame(id: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(body.size + HEADER_BYTES)
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            val size = body.size
            out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            out.write(byteArrayOf(0, 0))
            out.write(body)
            return out.toByteArray()
        }

        private fun utf16WithBom(text: String): ByteArray {
            // Little-endian with BOM FF FE. v2.3 text frames need no terminator (the frame size bounds it).
            val chars = text.toByteArray(Charsets.UTF_16LE)
            return byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chars
        }

        // The tag-size field is sync-safe (7 bits per byte) in every ID3v2 version.
        internal fun syncSafe(value: Int): ByteArray {
            require(value in 0..0x0FFF_FFFF) { "ID3 tag too large" }
            return byteArrayOf(
                ((value shr 21) and 0x7F).toByte(),
                ((value shr 14) and 0x7F).toByte(),
                ((value shr 7) and 0x7F).toByte(),
                (value and 0x7F).toByte(),
            )
        }

        private fun ByteArray.isPng(): Boolean =
            size >= 4 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() &&
                this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte()
    }
}
