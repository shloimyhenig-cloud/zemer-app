package com.jtech.zemer.playback

import android.content.Context
import com.jtech.zemer.utils.CoverArtEmbedder
import com.jtech.zemer.utils.mp3.Mp3TrackMetadata
import com.jtech.zemer.utils.mp3.Mp3Transcoder
import java.io.File
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
        Mp3Transcoder.transcode(input = source, output = output, tag = metadata.toTag(cover))
        Timber.d("MP3 export: $songId -> ${output.length()} bytes")
        return output
    }
}
