package com.jtech.zemer.utils.mp3

import com.zemer.lame.Id3v2Tag

/**
 * What an exported MP3 is tagged and named with. [artist] is the folder artist (the album artist when
 * known), so every track of an album lands in the same `Music/Zemer/<artist>/<album>/` folder.
 */
data class Mp3TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val trackTotal: Int?,
) {
    /**
     * `07 - Title.mp3` when the track position is known (zero-padded to the album's width so file
     * managers sort the album in order), else `Title.mp3`. Path sanitizing stays MediaStoreHelper's job.
     */
    fun fileName(): String {
        val cleanTitle = title.trim().ifEmpty { "Track" }
        val number = trackNumber?.takeIf { it > 0 } ?: return "$cleanTitle.mp3"
        val width = maxOf(2, (trackTotal?.takeIf { it >= number } ?: number).toString().length)
        return "${number.toString().padStart(width, '0')} - $cleanTitle.mp3"
    }

    fun toTag(coverArt: ByteArray?): Id3v2Tag = Id3v2Tag(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist ?: artist.takeIf { album != null },
        year = year,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        coverArt = coverArt,
    )
}
