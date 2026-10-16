package com.jtech.zemer.recommendation

import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.search.ZemerResultMapper
import com.jtech.zemer.search.ZemerSearchOptions
import com.jtech.zemer.search.ZemerSearchRepository
import com.jtech.zemer.utils.joinByBullet
import com.metrolist.innertube.YouTube.SearchFilter
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** One row in a picker step. [seed] is what the recommender receives if the row is picked. */
sealed interface PickOption {
    val id: String
    val title: String
    val subtitle: String?
    val thumbnail: String?

    data class Artist(val seed: CatalogArtist, override val thumbnail: String?) : PickOption {
        override val id get() = seed.id ?: seed.name
        override val title get() = seed.name
        override val subtitle: String? get() = null
    }

    data class Album(val seed: CatalogAlbum) : PickOption {
        override val id get() = seed.id
        override val title get() = seed.title
        override val subtitle get() = joinByBullet(
            seed.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
            seed.year?.toString(),
        ).ifBlank { null }
        override val thumbnail get() = seed.thumbnail
    }

    data class Song(val seed: CatalogSong, override val thumbnail: String?) : PickOption {
        override val id get() = seed.id
        override val title get() = seed.title
        override val subtitle get() = joinByBullet(
            seed.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
            seed.albumTitle?.takeIf { it.isNotBlank() },
        ).ifBlank { null }
    }
}

enum class PickKind { ARTIST, ALBUM, SONG }

/**
 * Rows for the three picker steps: suggestions shown before anything is typed (what's popular, what
 * the user already listens to, and what belongs to the singers picked in earlier steps), and Zemer
 * search results once a query is entered. Everything comes from the same filtered sources as the rest
 * of the app.
 */
class CollectionPickerSource(
    private val repository: ZemerSearchRepository,
    private val database: MusicDatabase,
) {
    suspend fun search(kind: PickKind, query: String, options: ZemerSearchOptions): List<PickOption> {
        val filter = when (kind) {
            PickKind.ARTIST -> SearchFilter.FILTER_ARTIST
            PickKind.ALBUM -> SearchFilter.FILTER_ALBUM
            PickKind.SONG -> SearchFilter.FILTER_SONG
        }
        return repository.filtered(query, filter, options).items.mapNotNull { item ->
            when {
                kind == PickKind.ARTIST && item is ArtistItem -> item.toOption()
                kind == PickKind.ALBUM && item is AlbumItem -> item.toOption()
                kind == PickKind.SONG && item is SongItem && !item.isVideo && !item.isEpisode -> item.toOption()
                else -> null
            }
        }.distinctBy { it.id }.take(MAX_RESULTS)
    }

    suspend fun suggestions(
        kind: PickKind,
        pickedArtists: List<CatalogArtist>,
        pickedAlbums: List<CatalogAlbum>,
        options: ZemerSearchOptions,
    ): List<PickOption> = coroutineScope {
        when (kind) {
            PickKind.ARTIST -> {
                val listened = async {
                    runCatching { database.mostPlayedArtists(fromTimeStamp = 0, limit = 15).first() }.getOrNull()
                        .orEmpty()
                        .map { PickOption.Artist(CatalogArtist(it.id, it.title), it.thumbnailUrl) }
                }
                val popular = async {
                    runCatching { repository.homeRows(options).artists }.getOrNull().orEmpty().map { it.toOption() }
                }
                (listened.await() + popular.await()).distinctBy { it.id }
            }
            PickKind.ALBUM -> {
                val byPicked = pickedArtists.mapNotNull { it.id }.take(6).map { id ->
                    async {
                        runCatching { repository.artist(id, options) }.getOrNull()
                            ?.sections?.firstOrNull { it.title == ZemerResultMapper.TITLE_ALBUMS }
                            ?.items.orEmpty().filterIsInstance<AlbumItem>().take(8).map { it.toOption() }
                    }
                }
                val popular = async {
                    runCatching { repository.homeRows(options).albums }.getOrNull().orEmpty().map { it.toOption() }
                }
                (byPicked.awaitAll().flatMap { it.orEmpty() }.interleaveByArtist() + popular.await()).distinctBy { it.id }
            }
            PickKind.SONG -> {
                val artistIds = (pickedArtists.mapNotNull { it.id } + pickedAlbums.flatMap { it.artistIds }).distinct()
                artistIds.take(8).map { id ->
                    async {
                        runCatching { repository.artist(id, options) }.getOrNull()
                            ?.sections?.firstOrNull { it.title == ZemerResultMapper.TITLE_SONGS }
                            ?.items.orEmpty().filterIsInstance<SongItem>()
                            .filter { !it.isVideo && !it.isEpisode }
                            .take(6).map { it.toOption() }
                    }
                }.awaitAll().flatMap { it.orEmpty() }.distinctBy { it.id }
            }
        }.take(MAX_SUGGESTIONS)
    }

    // Round-robin so the first screen shows a few albums of EACH picked singer, not one singer's 8.
    private fun List<PickOption.Album>.interleaveByArtist(): List<PickOption.Album> {
        val groups = groupBy { it.seed.artists.firstOrNull()?.id }.values.map { it.toMutableList() }
        val out = ArrayList<PickOption.Album>(size)
        while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) out += g.removeAt(0) }
        return out
    }

    private fun ArtistItem.toOption() = PickOption.Artist(CatalogArtist(id, title), thumbnail)

    private fun AlbumItem.toOption() = PickOption.Album(
        CatalogAlbum(
            id = browseId,
            title = title,
            artists = artists.orEmpty().map { CatalogArtist(it.id, it.name) },
            year = year,
            thumbnail = thumbnail,
            playlistId = playlistId,
        ),
    )

    private fun SongItem.toOption() = PickOption.Song(
        CatalogSong(
            id = id,
            title = title,
            artists = artists.map { CatalogArtist(it.id, it.name) },
            albumId = album?.id,
            albumTitle = album?.name,
        ),
        thumbnail,
    )

    private companion object {
        const val MAX_RESULTS = 30
        const val MAX_SUGGESTIONS = 40
    }
}
