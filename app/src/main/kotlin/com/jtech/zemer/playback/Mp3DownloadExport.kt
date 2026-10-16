package com.jtech.zemer.playback

import android.content.Context
import com.jtech.zemer.utils.CoverArtEmbedder
import com.jtech.zemer.utils.mp3.Mp3TrackMetadata
import com.jtech.zemer.utils.mp3.Mp3Transcoder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * The MP3 step of a "Build My Collection" download: a finished temp download (whatever container
 * Zemer's normal path fetched) is genuinely re-encoded into a tagged MP3 next to it. The download
 * manager then saves that MP3 through its usual MediaStore path, so state, retries and the
 * Downloaded library are exactly those of any other download.
 */
internal object Mp3DownloadExport {

    fun outputFileFor(context: Context, songId: String): File = File(context.cacheDir, "export_$songId.mp3")

    // Encoding is CPU-bound: run as many at once as the phone has spare cores (at most 4), leaving
    // one core for playback and the UI.
    private val parallelEncodes = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    private val encodeGate = Semaphore(parallelEncodes)
    private val waitingOrEncoding = AtomicInteger(0)

    /** False once enough songs are already waiting to encode (bounds the cache used by temp files). */
    fun hasEncodeCapacity(): Boolean = waitingOrEncoding.get() < parallelEncodes * 2

    suspend fun transcode(
        context: Context,
        httpClient: OkHttpClient,
        songId: String,
        source: File,
        metadata: Mp3TrackMetadata,
        thumbnailUrl: String?,
    ): File {
        val output = outputFileFor(context, songId)
        // Cover art is a nicety: a failed fetch tags the file without a picture rather than failing it.
        val cover = runCatching { CoverArtEmbedder.downloadArtworkOrNull(thumbnailUrl, httpClient) }
            .onFailure { Timber.w(it, "MP3 export: cover art unavailable for $songId") }
            .getOrNull()
        waitingOrEncoding.incrementAndGet()
        try {
            encodeGate.withPermit {
                Mp3Transcoder.transcode(input = source, output = output, tag = metadata.toTag(cover))
            }
        } finally {
            waitingOrEncoding.decrementAndGet()
        }
        Timber.d("MP3 export: $songId -> ${output.length()} bytes")
        return output
    }
}
