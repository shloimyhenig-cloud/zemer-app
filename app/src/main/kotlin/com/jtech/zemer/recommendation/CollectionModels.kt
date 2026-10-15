package com.jtech.zemer.recommendation

/*
 * Models for "Build My Collection": an on-device recommender that turns a handful of favorite singers,
 * albums and songs into a list of COMPLETE albums to download. Everything here is plain Kotlin (no
 * Android, no InnerTube types) so the whole pipeline - gathering included - runs in JVM unit tests
 * against a fake catalog.
 */

data class CatalogArtist(val id: String?, val name: String)

data class CatalogAlbum(
    val id: String,
    val title: String,
    val artists: List<CatalogArtist>,
    val year: Int? = null,
    val thumbnail: String? = null,
    val playlistId: String? = null,
    /** Listed in an artist's "Singles" shelf (an EP/single release, not a full CD). */
    val isSingle: Boolean = false,
) {
    val artistIds: Set<String> get() = artists.mapNotNullTo(LinkedHashSet()) { it.id }
}

data class CatalogSong(
    val id: String,
    val title: String,
    val artists: List<CatalogArtist>,
    val albumId: String? = null,
    val albumTitle: String? = null,
) {
    val artistIds: Set<String> get() = artists.mapNotNullTo(LinkedHashSet()) { it.id }
}

data class ArtistDiscography(
    val artist: CatalogArtist,
    val albums: List<CatalogAlbum>,
    val singles: List<CatalogAlbum>,
    val songs: List<CatalogSong>,
)

/** An album opened for verification: its real track count decides "complete CD" vs single/EP. */
data class AlbumDetails(val album: CatalogAlbum, val trackCount: Int)

/** Albums the server currently ranks (1 = top), e.g. a Top-50 chart rolled up to its albums. */
data class RankedAlbum(val album: CatalogAlbum, val rank: Int)

/** On-device listening history, most-listened first. */
data class ListeningHistory(
    val artistIds: List<String> = emptyList(),
    val albumIds: List<String> = emptyList(),
)

/** What the user picked in the three picker steps. Albums/songs carry their credits and year. */
data class CollectionSeeds(
    val artists: List<CatalogArtist> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val songs: List<CatalogSong> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
    val pickedArtistIds: Set<String> get() = artists.mapNotNullTo(LinkedHashSet()) { it.id }
    val pickedAlbumIds: Set<String> get() = albums.mapTo(LinkedHashSet()) { it.id }

    /** Artists credited on the picked albums/songs that the user did not pick directly. */
    val impliedArtistIds: Set<String>
        get() = (albums.flatMap { it.artistIds } + songs.flatMap { it.artistIds })
            .toCollection(LinkedHashSet()) - pickedArtistIds
}

data class CollectionPreferences(
    /** Favor established albums over the newest releases. */
    val preferClassics: Boolean = true,
    /** Favor collections/compilations ("Those Were the Days", "Best of", "Vol."...). */
    val preferCollections: Boolean = true,
    /** How many albums to propose; clamped to [MIN_TARGET]..[MAX_TARGET]. */
    val targetCount: Int = DEFAULT_TARGET,
) {
    val clampedTarget: Int get() = targetCount.coerceIn(MIN_TARGET, MAX_TARGET)

    companion object {
        const val MIN_TARGET = 60
        const val DEFAULT_TARGET = 80
        const val MAX_TARGET = 100
    }
}

/** Why an album was recommended. Rendered by the UI as localized chips; ordered strongest first. */
enum class RecommendationReason {
    YOUR_PICK,
    FAVORITE_ARTIST,
    ARTIST_FROM_YOUR_PICKS,
    SIMILAR_TO_YOUR_PICKS,
    RELATED_ARTIST,
    YOU_LISTEN_TO_IT,
    POPULAR_ON_ZEMER,
    CHART,
    CLASSIC,
    MATCHES_YOUR_ERA,
    COLLECTION,
}

data class AlbumRecommendation(
    val album: CatalogAlbum,
    /** 0..100, relative within this run. */
    val score: Int,
    val reasons: List<RecommendationReason>,
    val trackCount: Int?,
)
