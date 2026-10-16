package com.jtech.zemer.recommendation

import android.net.Uri
import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.search.ZemerResultMapper
import com.jtech.zemer.search.ZemerSearchOptions
import com.jtech.zemer.search.ZemerSearchRepository
import com.jtech.zemer.utils.MediaStoreHelper
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/**
 * [CollectionCatalog] over Zemer's own sources: the Zemer server (already whitelist-scoped and
 * content-filtered for [options], exactly like the rest of the app) plus the on-device database.
 * Opened album pages are kept so the download step reuses them instead of fetching twice.
 */
class ZemerCollectionCatalog(
    private val repository: ZemerSearchRepository,
    private val database: MusicDatabase,
    private val mediaStoreHelper: MediaStoreHelper,
    private val options: ZemerSearchOptions,
) : CollectionCatalog {

    private val albumPages = ConcurrentHashMap<String, AlbumPage>()

    fun cachedAlbumPage(albumId: String): AlbumPage? = albumPages[albumId]

    suspend fun albumPage(album: CatalogAlbum): AlbumPage? =
        albumPages[album.id] ?: repository.album(album.id, album.playlistId, options)?.also { albumPages[album.id] = it }

    override suspend fun artistDiscography(artistId: String): ArtistDiscography? {
        val page = repository.artist(artistId, options) ?: return null
        fun albums(title: String) = page.sections.firstOrNull { it.title == title }?.items.orEmpty()
            .filterIsInstance<AlbumItem>().map { it.toCatalogAlbum() }
        return ArtistDiscography(
            artist = CatalogArtist(page.artist.id, page.artist.title),
            albums = albums(ZemerResultMapper.TITLE_ALBUMS),
            singles = albums(ZemerResultMapper.TITLE_SINGLES).map { it.copy(isSingle = true) },
            songs = page.sections.firstOrNull { it.title == ZemerResultMapper.TITLE_SONGS }?.items.orEmpty()
                .filterIsInstance<SongItem>().map { it.toCatalogSong() },
        )
    }

    override suspend fun relatedSongs(kind: String, seedId: String): List<CatalogSong> {
        val first = repository.radio(kind, seedId, options)
        // One continuation doubles the neighborhood at the cost of a single extra request.
        val second = first.continuation?.let { runCatching { repository.radioContinuation(it) }.getOrNull() }
        return (first.songs + second?.songs.orEmpty())
            .filter { !it.isVideo && !it.isEpisode }
            .map { it.toCatalogSong() }
    }

    override suspend fun popularAlbums(): List<CatalogAlbum> =
        repository.homeRows(options).albums.map { it.toCatalogAlbum() }

    override suspend fun chartAlbums(): List<RankedAlbum> {
        val charts = repository.curatedPlaylists(options)
            .filter { it.id.startsWith(CHART_ID_PREFIX) || it.anchorDate != null }
            .take(MAX_CHARTS)
        val best = HashMap<String, RankedAlbum>()
        charts.forEach { chart ->
            val page = runCatching { repository.curatedPlaylist(chart.id, options) }.getOrNull() ?: return@forEach
            page.songs.forEachIndexed { index, song ->
                val albumRef = song.album ?: return@forEachIndexed
                val rank = page.rank[song.id] ?: (index + 1)
                val album = CatalogAlbum(
                    id = albumRef.id,
                    title = albumRef.name,
                    artists = song.artists.map { CatalogArtist(it.id, it.name) },
                )
                if ((best[album.id]?.rank ?: Int.MAX_VALUE) > rank) best[album.id] = RankedAlbum(album, rank)
            }
            page.albums.forEachIndexed { index, item ->
                val rank = index + 1
                if ((best[item.id]?.rank ?: Int.MAX_VALUE) > rank) best[item.id] = RankedAlbum(item.toCatalogAlbum(), rank)
            }
        }
        return best.values.sortedBy { it.rank }
    }

    override suspend fun albumDetails(album: CatalogAlbum): AlbumDetails? {
        val page = albumPage(album) ?: return null
        val songs = page.songs.filter { !it.isVideo && !it.isEpisode }
        return AlbumDetails(page.album.toCatalogAlbum(), trackCount = songs.size)
    }

    override suspend fun listeningHistory(): ListeningHistory = ListeningHistory(
        artistIds = database.mostPlayedArtists(fromTimeStamp = 0, limit = HISTORY_ARTISTS).first().map { it.id },
        albumIds = database.mostPlayedAlbums(fromTimeStamp = 0, limit = HISTORY_ALBUMS).first().map { it.id },
    )

    /** An album counts as collected only when every track is already saved as an MP3. */
    override suspend fun fullyDownloadedAlbumIds(): Set<String> {
        val downloaded = database.downloadedSongsByCreateDateAsc(false).first()
        return downloaded
            .filter { song -> song.song.mediaStoreUri?.let { mediaStoreHelper.isMp3(Uri.parse(it)) } == true }
            .groupBy { it.album }
            .filterKeys { it != null }
            .filter { (album, songs) -> album!!.songCount in 1..songs.size }
            .keys
            .mapNotNullTo(HashSet()) { it?.id }
    }

    private fun AlbumItem.toCatalogAlbum() = CatalogAlbum(
        id = browseId,
        title = title,
        artists = artists.orEmpty().map { CatalogArtist(it.id, it.name) },
        year = year,
        thumbnail = thumbnail,
        playlistId = playlistId,
    )

    private fun SongItem.toCatalogSong() = CatalogSong(
        id = id,
        title = title,
        artists = artists.map { CatalogArtist(it.id, it.name) },
        albumId = album?.id,
        albumTitle = album?.name,
    )

    private companion object {
        // Zemer's server charts (Top 50, trending...) are the `auto-*` curated playlists.
        const val CHART_ID_PREFIX = "auto-"
        const val MAX_CHARTS = 3
        const val HISTORY_ARTISTS = 25
        const val HISTORY_ALBUMS = 40
    }
}
