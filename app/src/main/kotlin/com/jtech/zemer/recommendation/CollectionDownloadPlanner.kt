package com.jtech.zemer.recommendation

import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.db.entities.Song
import com.jtech.zemer.playback.DownloadUtil
import com.jtech.zemer.playback.DownloadedMp3Converter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * "Download Selected Albums": for each album, store its full track list (the same album persist the
 * album screen does), then hand EVERY track to Zemer's normal download path in MP3 mode. Tracks that
 * are already downloaded in another format are converted on-device instead of fetched again.
 */
class CollectionDownloadPlanner(
    private val database: MusicDatabase,
    private val catalog: ZemerCollectionCatalog,
    private val downloadUtil: DownloadUtil,
    private val converter: DownloadedMp3Converter,
) {
    data class Result(val albums: Int, val failedAlbums: Int, val queuedSongs: Int, val convertedSongs: Int, val alreadyMp3: Int)

    suspend fun downloadAll(albums: List<CatalogAlbum>, onAlbumDone: (done: Int, total: Int) -> Unit): Result = coroutineScope {
        val gate = Semaphore(PARALLEL_ALBUMS)
        var done = 0
        val perAlbum = albums.map { album ->
            async {
                gate.withPermit {
                    val songs = runCatching { albumSongs(album) }.getOrNull()
                    synchronized(this@CollectionDownloadPlanner) { done++ }
                    onAlbumDone(done, albums.size)
                    songs
                }
            }
        }.awaitAll()

        var converting = 0
        var alreadyMp3 = 0
        val toDownload = ArrayList<Song>()
        // perAlbum keeps the review order and each album's track order, so the queue completes
        // whole albums one after another.
        perAlbum.filterNotNull().flatten().distinctBy { it.id }.forEach { song ->
            when {
                !song.song.isDownloaded -> toDownload += song
                converter.isStoredAsMp3(song) -> alreadyMp3++
                else -> {
                    converter.enqueue(song)
                    converting++
                }
            }
        }
        downloadUtil.downloadToMediaStoreAsMp3(toDownload)
        Result(
            albums = albums.size,
            failedAlbums = perAlbum.count { it.isNullOrEmpty() },
            queuedSongs = toDownload.size,
            convertedSongs = converting,
            alreadyMp3 = alreadyMp3,
        )
    }

    /** The album's complete, filtered track list, persisted so the download path can find each track. */
    private suspend fun albumSongs(album: CatalogAlbum): List<Song> = withContext(Dispatchers.IO) {
        val page = catalog.albumPage(album) ?: return@withContext emptyList()
        // insert(AlbumPage) is one @Transaction: it has committed when this call returns.
        database.insert(page)
        val order = page.songs.withIndex().associate { it.value.id to it.index }
        // The whitelist-joined read is the normal one; if an artist credit didn't map onto a whitelisted
        // row yet, fall back to the tracks the (already server-filtered) page itself listed.
        val songs = database.albumSongs(album.id).first().ifEmpty {
            page.songs.mapNotNull { database.song(it.id).first() }
        }
        songs
            .filter { !it.song.isVideo && !it.song.isEpisode }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    private companion object {
        const val PARALLEL_ALBUMS = 4
    }
}
