package com.jtech.zemer.utils.mp3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.zemer.lame.Id3v2Tag
import com.zemer.lame.Mp3StreamEncoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Genuinely re-encodes a downloaded audio file (Opus in Ogg/WebM, AAC in M4A, ...) to an MP3 file:
 * the platform decoder produces PCM and the bundled LAME encoder writes real MPEG Layer III frames,
 * preceded by an ID3v2.3 tag. Nothing is ever renamed into an `.mp3`.
 *
 * Runs on the caller's (IO) dispatcher and is cooperative-cancellable between buffers.
 */
object Mp3Transcoder {

    class TranscodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun transcode(
        input: File,
        output: File,
        tag: Id3v2Tag,
        bitrateKbps: Int = Mp3StreamEncoder.DEFAULT_BITRATE_KBPS,
    ) {
        if (!input.exists() || input.length() == 0L) throw TranscodeException("Source audio is missing or empty")
        output.parentFile?.mkdirs()
        output.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: Mp3StreamEncoder? = null
        var completed = false
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw TranscodeException("No audio track in ${input.name}")
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw TranscodeException("Audio track has no MIME type")

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            BufferedOutputStream(FileOutputStream(output), OUTPUT_BUFFER_BYTES).use { sink ->
                sink.write(tag.toByteArray())

                var sampleRate = sourceFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                var channels = sourceFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmEncoding = PcmFrames.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                fun encoderFor(): Mp3StreamEncoder {
                    encoder?.let { return it }
                    val rate = sampleRate ?: throw TranscodeException("Decoder reported no sample rate")
                    val ch = channels ?: throw TranscodeException("Decoder reported no channel count")
                    return Mp3StreamEncoder(
                        sampleRate = rate,
                        channels = ch.coerceAtMost(2),
                        output = sink,
                        bitrateKbps = bitrateKbps,
                    ).also { encoder = it }
                }

                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)
                                ?: throw TranscodeException("Decoder input buffer unavailable")
                            inBuffer.clear()
                            val size = extractor.readSampleData(inBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            if (encoder == null) {
                                sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                                channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            }
                            pcmEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: PcmFrames.ENCODING_PCM_16BIT
                            if (!PcmFrames.isSupported(pcmEncoding)) {
                                throw TranscodeException("Decoder output encoding $pcmEncoding is not supported")
                            }
                        }
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                    ?: throw TranscodeException("Decoder output buffer unavailable")
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                val decodedChannels = channels ?: throw TranscodeException("Unknown channel count")
                                val pcm = PcmFrames.downmixToStereo(
                                    PcmFrames.toPcm16(outBuffer, pcmEncoding),
                                    decodedChannels,
                                )
                                val mp3 = encoderFor()
                                mp3.encode(pcm, 0, pcm.size / mp3.channels)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        // INFO_TRY_AGAIN_LATER and the deprecated INFO_OUTPUT_BUFFERS_CHANGED: just loop.
                    }
                }

                val mp3 = encoder ?: throw TranscodeException("Decoder produced no audio")
                mp3.finish()
                sink.flush()
            }
            if (output.length() <= MIN_PLAUSIBLE_BYTES) throw TranscodeException("MP3 output is implausibly small")
            completed = true
        } catch (e: TranscodeException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranscodeException("MP3 transcode failed: ${e.message}", e)
        } finally {
            runCatching { encoder?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (!completed) output.delete()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val OUTPUT_BUFFER_BYTES = 64 * 1024
    private const val MIN_PLAUSIBLE_BYTES = 4_096L
}
