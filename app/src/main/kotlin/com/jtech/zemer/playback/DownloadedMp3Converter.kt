package com.jtech.zemer.playback

import android.content.Context
import android.net.Uri
import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.db.entities.Song
import com.jtech.zemer.utils.MediaStoreHelper
import com.jtech.zemer.utils.mp3.Mp3TrackMetadata
import com.jtech.zemer.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Build My Collection for a song that is ALREADY downloaded in another format (Opus/AAC from a normal
 * Zemer download): re-encodes the local file to MP3 without touching the network, saves it in the
 * album folder, repoints the song's row at it, and only then removes the old file. A failure leaves
 * the existing download exactly as it was.
 */
@Singleton
class DownloadedMp3Converter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val mediaStoreHelper = MediaStoreHelper(context)
    private val httpClient = OkHttpClient()

    // Encoding is CPU-bound; two at a time keeps a big batch from starving playback.
    private val encodeGate = Semaphore(MAX_PARALLEL_ENCODES)

    // Conversions outlive the screen that asked for them, like downloads do.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isStoredAsMp3(song: Song): Boolean =
        song.song.mediaStoreUri?.let { mediaStoreHelper.isMp3(Uri.parse(it)) } == true

    /** Converts in the background; a second request for a song already converting is ignored. */
    fun enqueue(song: Song) {
        if (!inFlight.add(song.id)) return
        scope.launch {
            try {
                ensureMp3(song)
            } finally {
                inFlight.remove(song.id)
            }
        }
    }

    /** True when the song is a stored MP3 after this call (already one, or converted now). */
    suspend fun ensureMp3(song: Song): Boolean = withContext(Dispatchers.IO) {
        val oldUri = song.song.mediaStoreUri?.let(Uri::parse) ?: return@withContext false
        if (mediaStoreHelper.isMp3(oldUri)) return@withContext true
        encodeGate.withPermit { convert(song, oldUri) }
    }

    private suspend fun convert(song: Song, oldUri: Uri): Boolean {
        val source = File(context.cacheDir, "convert_${song.id}.src")
        try {
            val copied = context.contentResolver.openInputStream(oldUri)?.use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) return false

            val albumArtist = song.album?.let { album ->
                database.albumUnfiltered(album.id).first()?.artists?.firstOrNull()?.name
            }
            val artist = albumArtist ?: song.artists.firstOrNull()?.name ?: UNKNOWN_ARTIST
            val metadata = Mp3TrackMetadata(
                title = song.song.title,
                artist = artist,
                album = song.album?.title,
                albumArtist = albumArtist,
                year = song.song.year ?: song.album?.year,
                trackNumber = song.album?.let { runCatching { database.songAlbumIndex(song.id, it.id) }.getOrNull() }
                    ?.plus(1),
                trackTotal = song.album?.songCount?.takeIf { it > 0 },
            )
            val mp3 = Mp3DownloadExport.transcode(
                context = context,
                httpClient = httpClient,
                songId = song.id,
                source = source,
                metadata = metadata,
                thumbnailUrl = song.song.thumbnailUrl,
            )
            val newUri = mediaStoreHelper.saveFileToMediaStore(
                tempFile = mp3,
                fileName = metadata.fileName(),
                mimeType = mediaStoreHelper.getMimeType("mp3"),
                title = metadata.title,
                artist = artist,
                album = metadata.album,
                durationMs = song.song.duration.takeIf { it > 0 }?.times(1000L),
            ) ?: return false

            // Repoint the row first, based on the CURRENT row so nothing the user changed meanwhile is
            // lost; only then delete the old file (worst case: a harmless duplicate, never a dangling URI).
            val current = database.song(song.id).first()?.song ?: return false
            database.transaction { upsert(current.copy(isDownloaded = true, mediaStoreUri = newUri.toString())) }
            // transaction{} is fire-and-forget: wait until the row really points at the MP3 before the
            // old file disappears, so a play in between never finds a dangling URI.
            val repointed = withTimeoutOrNull(DB_CONFIRM_TIMEOUT_MS) {
                database.song(song.id).first { it?.song?.mediaStoreUri == newUri.toString() }
            } != null
            if (repointed && oldUri != newUri) runCatching { mediaStoreHelper.deleteFromMediaStore(oldUri) }
            return repointed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "MP3 conversion failed for ${song.id}")
            reportException(e, "DownloadedMp3Converter")
            return false
        } finally {
            source.delete()
            Mp3DownloadExport.outputFileFor(context, song.id).delete()
        }
    }

    private companion object {
        const val MAX_PARALLEL_ENCODES = 2
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val DB_CONFIRM_TIMEOUT_MS = 10_000L
    }
}
