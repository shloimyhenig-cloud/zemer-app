package com.jtech.zemer.recommendation

/**
 * Small state holder shared by the collection-builder picker screens.
 * Selections are IDs from Zemer's existing catalog; no audio URLs or separate media source is stored.
 */
data class CollectionBuilderState(
    val artistIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val songIds: Set<String> = emptySet(),
    val preferClassics: Boolean = true,
    val preferCollections: Boolean = true,
) {
    val canRecommend: Boolean
        get() = artistIds.isNotEmpty() || albumIds.isNotEmpty() || songIds.isNotEmpty()

    fun toggleArtist(id: String): CollectionBuilderState = copy(artistIds = artistIds.toggle(id))

    fun toggleAlbum(id: String): CollectionBuilderState = copy(albumIds = albumIds.toggle(id))

    fun toggleSong(id: String): CollectionBuilderState = copy(songIds = songIds.toggle(id))

    fun toSeed(): CollectionSeed = CollectionSeed(
        artistIds = artistIds,
        albumIds = albumIds,
        songIds = songIds,
    )
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
