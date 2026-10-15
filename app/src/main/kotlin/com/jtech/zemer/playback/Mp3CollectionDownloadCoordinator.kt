package com.jtech.zemer.playback

import android.content.Context
import android.net.Uri
import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.db.entities.Song
import com.jtech.zemer.utils.MediaStoreHelper
import com.jtech.zemer.utils.Mp3Transcoder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Build My Collection asks for ordinary .mp3 files. Zemer's normal downloader intentionally keeps
 * the original high-quality Opus/AAC container, so this coordinator waits for that legitimate
 * download to finish, converts it to real MPEG Layer III, saves the MP3 through the same
 * MediaStoreHelper folder policy, updates the DB URI, and only then removes the intermediate file.
 *
 * The normal Zemer download path is otherwise untouched. Conversion is serialized so selecting a
 * large album collection doesn't run several CPU-heavy encoders at once.
 */
object Mp3CollectionDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encodeSemaphore = Semaphore(1)
    private val jobs = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(
        context: Context,
        database: MusicDatabase,
        downloadUtil: DownloadUtil,
        song: Song,
    ) {
        if (!jobs.add(song.id)) return

        // Reuse the app's normal, filtered, retrying download mechanism first.
        downloadUtil.downloadToMediaStore(song)

        scope.launch {
            try {
                val terminal = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    downloadUtil.getMediaStoreDownload(song.id)
                        .filterNotNull()
                        .first { state ->
                            state.status == MediaStoreDownloadManager.DownloadState.Status.COMPLETED ||
                                state.status == MediaStoreDownloadManager.DownloadState.Status.FAILED ||
                                state.status == MediaStoreDownloadManager.DownloadState.Status.CANCELLED
                        }
                }
                if (terminal?.status != MediaStoreDownloadManager.DownloadState.Status.COMPLETED) {
                    Timber.w("MP3 conversion skipped; source download did not complete for ${song.id}")
                    return@launch
                }

                encodeSemaphore.withPermit {
                    convertDownloadedSong(context.applicationContext, database, song.id)
                }
            } catch (t: Throwable) {
                Timber.e(t, "MP3 conversion failed for ${song.id}")
            } finally {
                jobs.remove(song.id)
            }
        }
    }

    private suspend fun convertDownloadedSong(
        context: Context,
        database: MusicDatabase,
        songId: String,
    ) {
        // The download manager updates Room immediately after MediaStore save. Give that transaction
        // a small grace window in case its COMPLETED state reaches us a few milliseconds first.
        var current = database.song(songId).first()
        repeat(DB_READY_RETRIES) {
            if (!current?.song?.mediaStoreUri.isNullOrBlank()) return@repeat
            delay(DB_READY_RETRY_MS)
            current = database.song(songId).first()
        }
        val downloaded = current ?: return
        val originalUriString = downloaded.song.mediaStoreUri ?: return
        val originalUri = Uri.parse(originalUriString)

        // Already a true MP3: keep it as-is.
        if (context.contentResolver.getType(originalUri)?.equals("audio/mpeg", ignoreCase = true) == true) {
            return
        }

        val sourceFile = File.createTempFile("zemer_mp3_source_", ".audio", context.cacheDir)
        val mp3File = File.createTempFile("zemer_mp3_output_", ".mp3", context.cacheDir)
        try {
            context.contentResolver.openInputStream(originalUri)?.use { input ->
                sourceFile.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("Could not read downloaded audio")

            val albumArtist = downloaded.album?.let { album ->
                database.albumUnfiltered(album.id).first()?.artists?.firstOrNull()?.name
            }
            val artist = albumArtist
                ?: downloaded.artists.firstOrNull()?.name
                ?: "Unknown Artist"
            val trackNumber = downloaded.album?.let { album ->
                runCatching { database.songAlbumIndex(downloaded.id, album.id) }.getOrNull()
            }?.plus(1)

            Mp3Transcoder.transcode(
                inputFile = sourceFile,
                outputFile = mp3File,
                tags = Mp3Transcoder.Tags(
                    title = downloaded.song.title,
                    artist = artist,
                    album = downloaded.album?.title,
                    albumArtist = albumArtist,
                    year = downloaded.song.year ?: downloaded.album?.year,
                    trackNumber = trackNumber,
                ),
            )

            val helper = MediaStoreHelper(context)
            val newUri = mp3File.inputStream().buffered().use { input ->
                helper.saveToMediaStore(
                    inputStream = input,
                    fileName = "${downloaded.song.title}.mp3",
                    mimeType = "audio/mpeg",
                    title = downloaded.song.title,
                    artist = artist,
                    album = downloaded.album?.title,
                    durationMs = downloaded.song.duration.takeIf { it > 0 }?.times(1000L),
                )
            } ?: error("Could not save MP3 to device storage")

            // Point Zemer at the MP3 first. If old-file cleanup ever fails, the user merely has a
            // harmless duplicate instead of a DB entry pointing at a deleted file.
            database.query {
                upsert(
                    downloaded.song.copy(
                        isDownloaded = true,
                        mediaStoreUri = newUri.toString(),
                    )
                )
            }
            runCatching { helper.deleteFromMediaStore(originalUri) }
            Timber.i("Converted collection download to MP3: ${downloaded.id}")
        } finally {
            sourceFile.delete()
            mp3File.delete()
        }
    }

    private const val DOWNLOAD_TIMEOUT_MS = 30L * 60L * 1000L
    private const val DB_READY_RETRIES = 20
    private const val DB_READY_RETRY_MS = 75L
}
