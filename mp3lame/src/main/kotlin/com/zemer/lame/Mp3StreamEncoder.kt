package com.zemer.lame

import com.zemer.lame.mp3.BitStream
import com.zemer.lame.mp3.GainAnalysis
import com.zemer.lame.mp3.GetAudio
import com.zemer.lame.mp3.ID3Tag
import com.zemer.lame.mp3.Lame
import com.zemer.lame.mp3.LameGlobalFlags
import com.zemer.lame.mp3.MPEGMode
import com.zemer.lame.mp3.Parse
import com.zemer.lame.mp3.Presets
import com.zemer.lame.mp3.Quantize
import com.zemer.lame.mp3.QuantizePVT
import com.zemer.lame.mp3.Reservoir
import com.zemer.lame.mp3.Takehiro
import com.zemer.lame.mp3.VBRTag
import com.zemer.lame.mp3.Version
import com.zemer.lame.mpg.Common
import com.zemer.lame.mpg.Interface
import com.zemer.lame.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream

/**
 * Streams interleaved 16-bit PCM into real MPEG Layer III frames on [output].
 *
 * Constant bitrate (LAME resamples any input rate it cannot store directly), no LAME/Xing info frame and no automatic ID3 tags: the caller writes its own ID3v2
 * header before the first [encode] (see [Id3v2Tag]). One instance encodes one file; it is not
 * thread-safe. [finish] flushes the encoder's last frames; [close] releases it (and is safe after
 * [finish]). Neither closes [output].
 */
class Mp3StreamEncoder(
    val sampleRate: Int,
    val channels: Int,
    private val output: OutputStream,
    bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    quality: Int = DEFAULT_QUALITY,
) : Closeable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var mp3Buffer = ByteArray(0)
    private var finished = false
    private var closed = false

    init {
        require(channels == 1 || channels == 2) { "MP3 supports mono or stereo, got $channels channels" }
        require(sampleRate in 8_000..192_000) { "Unsupported sample rate $sampleRate" }
        require(bitrateKbps in 32..320) { "Unsupported bitrate $bitrateKbps kbps" }
        require(quality in 0..9) { "LAME quality must be 0..9" }

        // Module wiring copied from jump3r's LameEncoder (the only part of it that is not tied to
        // javax.sound, which Android lacks).
        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val decoderInterface = Interface()
        val common = Common()
        lame.setModules(gainAnalysis, bitStream, presets, quantizePvt, quantize, vbrTag, version, id3, mpg)
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        GetAudio().setModules(parse, mpg)
        parse.setModules(version, id3, presets)
        mpg.setModules(decoderInterface, common)
        decoderInterface.setModules(vbrTag, common)

        flags = lame.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.brate = bitrateKbps
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.quality = quality
        id3.id3tag_init(flags)
        flags.write_id3tag_automatic = false
        flags.bWriteVbrTag = false
        flags.findReplayGain = false
        val rc = lame.lame_init_params(flags)
        check(rc >= 0) { "LAME rejected the encoder parameters (code $rc)" }
    }

    /** Encodes [frames] interleaved frames (frames x channels shorts) from [pcm] starting at [offset]. */
    fun encode(pcm: ShortArray, offset: Int = 0, frames: Int = (pcm.size - offset) / channels) {
        check(!finished && !closed) { "Encoder already finished" }
        require(offset >= 0 && frames >= 0 && offset + frames * channels <= pcm.size) { "PCM range out of bounds" }
        if (frames == 0) return
        // LAME consumes the PCM in one call; chunk so the per-call buffers stay small and bounded.
        var done = 0
        while (done < frames) {
            val chunk = minOf(MAX_FRAMES_PER_CALL, frames - done)
            encodeChunk(pcm, offset + done * channels, chunk)
            done += chunk
        }
    }

    private fun encodeChunk(pcm: ShortArray, offset: Int, frames: Int) {
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
        }
        // This port's lame_encode_buffer_int copies the ints straight into LAME's float buffer, which
        // expects +/-32768 (it does NOT rescale full-range 32-bit input like C LAME does), so plain
        // 16-bit sample values go in unshifted.
        if (channels == 2) {
            var src = offset
            for (i in 0 until frames) {
                left[i] = pcm[src].toInt()
                right[i] = pcm[src + 1].toInt()
                src += 2
            }
        } else {
            for (i in 0 until frames) {
                val sample = pcm[offset + i].toInt()
                left[i] = sample
                right[i] = sample
            }
        }
        val needed = worstCaseMp3Bytes(frames)
        if (mp3Buffer.size < needed) mp3Buffer = ByteArray(needed)
        val written = lame.lame_encode_buffer_int(flags, left, right, frames, mp3Buffer, 0, mp3Buffer.size)
        check(written >= 0) { "LAME encode failed (code $written)" }
        if (written > 0) output.write(mp3Buffer, 0, written)
    }

    /** Flushes the final frames. Must be called exactly once after the last [encode]. */
    fun finish() {
        check(!closed) { "Encoder closed" }
        if (finished) return
        finished = true
        if (mp3Buffer.size < FLUSH_BUFFER_BYTES) mp3Buffer = ByteArray(FLUSH_BUFFER_BYTES)
        val written = lame.lame_encode_flush(flags, mp3Buffer, 0, mp3Buffer.size)
        check(written >= 0) { "LAME flush failed (code $written)" }
        if (written > 0) output.write(mp3Buffer, 0, written)
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { lame.lame_close(flags) }
    }

    companion object {
        const val DEFAULT_BITRATE_KBPS = 192
        const val DEFAULT_QUALITY = 5

        private const val MAX_FRAMES_PER_CALL = 8192
        private const val FLUSH_BUFFER_BYTES = 16 * 1024

        // lame.h: "worst case" mp3buf size = 1.25 * num_samples + 7200.
        private fun worstCaseMp3Bytes(frames: Int): Int = (frames * 5 / 4) + 7200
    }
}
