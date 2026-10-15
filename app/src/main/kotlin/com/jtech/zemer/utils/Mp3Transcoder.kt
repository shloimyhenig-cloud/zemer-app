package com.jtech.zemer.utils

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import net.qiujuer.lame.Lame
import net.qiujuer.lame.LameOutputStream

/**
 * Converts a downloaded audio container (AAC/M4A, Opus/WebM/Ogg, etc.) to a real MPEG Layer III file.
 *
 * Android supplies the decoder; LAME supplies the MP3 encoder. This never renames a non-MP3 file to
 * `.mp3`: [outputFile] contains actual MPEG Layer III frames and an ID3v2.4 metadata header.
 */
object Mp3Transcoder {
    data class Tags(
        val title: String,
        val artist: String,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
    )

    fun transcode(
        inputFile: File,
        outputFile: File,
        tags: Tags,
        bitrateKbps: Int = 192,
    ) {
        require(inputFile.exists() && inputFile.length() > 0L) { "Source audio is empty" }
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var lameStream: LameOutputStream? = null
        var rawOutput: BufferedOutputStream? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track found")

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type missing")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            rawOutput = BufferedOutputStream(FileOutputStream(outputFile))
            rawOutput.write(Id3Writer.build(tags))

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var sampleRate = sourceFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var channelCount = sourceFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2

            fun ensureEncoder() {
                if (lameStream != null) return
                require(channelCount in 1..2) { "MP3 export supports mono or stereo audio" }
                val lame = Lame(
                    sampleRate,
                    channelCount,
                    sampleRate,
                    bitrateKbps,
                    Lame.LameModel.JOINT_STEREO,
                    Lame.LameQuality.NEAR_BEST,
                )
                lameStream = LameOutputStream(lame, rawOutput, PCM_SHORT_BUFFER_SIZE)
            }

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer missing")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                        pcmEncoding = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        ensureEncoder()
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            ensureEncoder()
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("Decoder output buffer missing")
                            val slice = outputBuffer.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }.slice().order(ByteOrder.nativeOrder())
                            val pcm = slice.toPcm16(pcmEncoding)
                            if (pcm.isNotEmpty()) {
                                lameStream?.write(pcm, pcm.size)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            ensureEncoder()
            lameStream?.close()
            lameStream = null
            rawOutput = null // closed by LameOutputStream

            require(outputFile.exists() && outputFile.length() > 0L) { "MP3 encoder produced an empty file" }
        } finally {
            runCatching { lameStream?.close() }
            runCatching { rawOutput?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (!outputFile.exists() || outputFile.length() == 0L) outputFile.delete()
        }
    }

    private fun ByteBuffer.toPcm16(encoding: Int): ShortArray = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            val shortBuffer = asShortBuffer()
            ShortArray(shortBuffer.remaining()).also(shortBuffer::get)
        }

        AudioFormat.ENCODING_PCM_FLOAT -> {
            val floatBuffer = asFloatBuffer()
            ShortArray(floatBuffer.remaining()) { index ->
                val sample = floatBuffer.get(index).coerceIn(-1f, 1f)
                (sample * Short.MAX_VALUE).roundToInt().toShort()
            }
        }

        AudioFormat.ENCODING_PCM_8BIT -> {
            ShortArray(remaining()) {
                (((get().toInt() and 0xff) - 128) shl 8).toShort()
            }
        }

        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val samples = remaining() / 3
            ShortArray(samples) {
                val b0 = get().toInt() and 0xff
                val b1 = get().toInt() and 0xff
                val b2 = get().toInt()
                val value24 = b0 or (b1 shl 8) or (b2 shl 16)
                (value24 shr 8).toShort()
            }
        }

        AudioFormat.ENCODING_PCM_32BIT -> {
            val intBuffer = asIntBuffer()
            ShortArray(intBuffer.remaining()) { index ->
                (intBuffer.get(index) shr 16).toShort()
            }
        }

        else -> error("Unsupported decoded PCM encoding: $encoding")
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private object Id3Writer {
        fun build(tags: Tags): ByteArray {
            val frames = buildList {
                add(textFrame("TIT2", tags.title))
                add(textFrame("TPE1", tags.artist))
                tags.album?.takeIf(String::isNotBlank)?.let { add(textFrame("TALB", it)) }
                tags.albumArtist?.takeIf(String::isNotBlank)?.let { add(textFrame("TPE2", it)) }
                tags.year?.let { add(textFrame("TDRC", it.toString())) }
                tags.trackNumber?.let { add(textFrame("TRCK", it.toString())) }
            }
            val payloadSize = frames.sumOf { it.size }
            return ByteArray(ID3_HEADER_SIZE + payloadSize).also { output ->
                output[0] = 'I'.code.toByte()
                output[1] = 'D'.code.toByte()
                output[2] = '3'.code.toByte()
                output[3] = 4 // ID3v2.4
                output[4] = 0
                output[5] = 0
                syncSafe(payloadSize).copyInto(output, 6)
                var offset = ID3_HEADER_SIZE
                frames.forEach { frame ->
                    frame.copyInto(output, offset)
                    offset += frame.size
                }
            }
        }

        private fun textFrame(id: String, value: String): ByteArray {
            val text = value.toByteArray(Charsets.UTF_8)
            val body = byteArrayOf(3) + text // ID3v2.4 UTF-8 encoding marker
            return ByteArray(10 + body.size).also { frame ->
                id.toByteArray(Charsets.US_ASCII).copyInto(frame, 0)
                syncSafe(body.size).copyInto(frame, 4)
                frame[8] = 0
                frame[9] = 0
                body.copyInto(frame, 10)
            }
        }

        private fun syncSafe(value: Int): ByteArray = byteArrayOf(
            ((value shr 21) and 0x7f).toByte(),
            ((value shr 14) and 0x7f).toByte(),
            ((value shr 7) and 0x7f).toByte(),
            (value and 0x7f).toByte(),
        )
    }

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val PCM_SHORT_BUFFER_SIZE = 16_384
    private const val ID3_HEADER_SIZE = 10
}
