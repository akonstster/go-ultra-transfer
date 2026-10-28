package app.goultra.transfer.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteOrder

/**
 * The sound of a clip of pieces with crossfades ([AudioJoin]) put together with its picture: the original's sound is
 * decoded around each piece, joined, encoded as AAC and muxed with the clip's video (made without sound). Used when a
 * clip is re-encoded anyway (saved with colour), so the joins can overlap as in a video editor.
 */
object ClipAudio {
    const val BITRATE = 256_000
    private const val TIMEOUT_US = 10_000L

    /** The joined, encoded sound: its format and its samples in order. */
    class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /**
     * The sound of [pieces] from the original ([open] sets the extractor's source), or null when it has no sound.
     * [cancelled] is checked between chunks.
     */
    fun encode(open: (MediaExtractor) -> Unit, pieces: List<Highlight>, cancelled: () -> Boolean): Encoded? {
        val reader = PcmReader.open(open) ?: return null
        try {
            val join = AudioJoin(reader.rate, reader.channels)
            val writer = AacWriter(reader.rate, reader.channels)
            try {
                join.render(join.plan(pieces), reader::read) { pcm ->
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    writer.write(pcm)
                }
                return writer.finish()
            } finally {
                writer.release()
            }
        } finally {
            reader.release()
        }
    }

    /** The clip's [video] file (no sound) and [audio] into [out]: tracks interleaved by time, the video's rotation kept. */
    fun mux(video: java.io.File, audio: Encoded?, out: FileDescriptor) {
        val ex = MediaExtractor()
        ex.setDataSource(video.absolutePath)
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    videoFormat = f
                }
            }
            require(videoTrack >= 0) { "no video in the clip" }
            ex.selectTrack(videoTrack)
            if (videoFormat!!.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            val vOut = muxer.addTrack(videoFormat)
            val aOut = audio?.let { muxer.addTrack(it.format) } ?: -1
            muxer.start()
            val maxSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 8 shl 20
            val buf = java.nio.ByteBuffer.allocateDirect(maxOf(maxSize, 1 shl 20))
            val info = MediaCodec.BufferInfo()
            var a = 0
            val samples = audio?.samples.orEmpty()
            while (true) {
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                // The sound up to this frame's time first.
                while (a < samples.size && samples[a].second.presentationTimeUs <= info.presentationTimeUs) {
                    muxer.writeSampleData(aOut, java.nio.ByteBuffer.wrap(samples[a].first), samples[a].second)
                    a++
                }
                muxer.writeSampleData(vOut, buf, info)
                ex.advance()
            }
            while (a < samples.size) {
                muxer.writeSampleData(aOut, java.nio.ByteBuffer.wrap(samples[a].first), samples[a].second)
                a++
            }
            muxer.stop()
        } finally {
            muxer.release()
            ex.release()
        }
    }

    /**
     * The original's sound as 16-bit PCM by frame: [read] (frame, count), seeking when asked for another place than
     * where it is (from a little earlier, so the decoder has settled); silence before the start and after the end.
     */
    private class PcmReader(private val ex: MediaExtractor, private val codec: MediaCodec, val rate: Int, val channels: Int) {
        private var next = Long.MIN_VALUE // the frame [buffer] continues from
        private var buffer = ShortArray(0)
        private var bufferAt = 0 // frames of [buffer] already given out
        private var inputDone = false
        private var outputDone = false

        fun read(from: Long, count: Int): ShortArray {
            if (from != next) seek(from)
            val out = ShortArray(count * channels)
            var filled = 0
            while (filled < count) {
                val have = buffer.size / channels - bufferAt
                if (have > 0) {
                    val n = minOf(have, count - filled)
                    System.arraycopy(buffer, bufferAt * channels, out, filled * channels, n * channels)
                    bufferAt += n
                    filled += n
                } else if (!decodeMore()) {
                    break // the end: the rest stays silent
                }
            }
            next = from + count
            return out
        }

        /** To [frame]: the decoder from a little before it, the frames before it dropped. */
        private fun seek(frame: Long) {
            val us = frame * 1_000_000L / rate
            ex.seekTo(maxOf(0L, us - 100_000L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            buffer = ShortArray(0)
            bufferAt = 0
            if (frame < 0) {
                // Before the original's start: silence up to it.
                buffer = ShortArray((-frame).toInt() * channels)
                return
            }
            while (true) {
                if (!decodeMore()) return
                val start = lastFrame
                val end = start + buffer.size / channels
                if (end > frame) {
                    bufferAt = maxOf(0L, frame - start).toInt()
                    if (start > frame) {
                        // A gap (the stream starts later): silence before it.
                        val gap = ShortArray(((start - frame) * channels).toInt())
                        buffer = gap + buffer
                        bufferAt = 0
                    }
                    return
                }
            }
        }

        private var lastFrame = 0L // the first frame of the last decoded buffer

        /** One more decoded buffer into [buffer]; false at the end. */
        private fun decodeMore(): Boolean {
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val input = codec.getInputBuffer(i)!!
                        val size = ex.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0) {
                        val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val data = ShortArray(info.size / 2)
                        pcm.position(info.offset / 2)
                        pcm.get(data)
                        codec.releaseOutputBuffer(o, false)
                        lastFrame = Math.round(info.presentationTimeUs * rate / 1_000_000.0)
                        buffer = data
                        bufferAt = 0
                        return true
                    }
                    codec.releaseOutputBuffer(o, false)
                }
            }
            return false
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }

        companion object {
            fun open(open: (MediaExtractor) -> Unit): PcmReader? {
                val ex = MediaExtractor()
                open(ex)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    ex.selectTrack(i)
                    val codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(f, null, null, 0)
                    codec.start()
                    return PcmReader(ex, codec, f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                ex.release()
                return null
            }
        }
    }

    /** 16-bit PCM in, AAC-LC at [BITRATE] out, kept in memory (a clip's sound: megabytes). */
    private class AacWriter(private val rate: Int, private val channels: Int) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        private var format: MediaFormat? = null
        private val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        private var frames = 0L

        fun write(pcm: ShortArray) {
            var at = 0
            while (at < pcm.size) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val input = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                    val n = minOf(pcm.size - at, input.capacity() / 2 / channels * channels)
                    input.asShortBuffer().put(pcm, at, n)
                    codec.queueInputBuffer(i, 0, n * 2, frames * 1_000_000L / rate, 0)
                    frames += n / channels
                    at += n
                }
                drain(false)
            }
        }

        fun finish(): Encoded {
            while (true) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    codec.queueInputBuffer(i, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            return Encoded(format ?: error("no AAC format"), samples)
        }

        private fun drain(toEnd: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                    o >= 0 -> {
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val out = codec.getOutputBuffer(o)!!
                            val data = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(data)
                            samples += data to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (end) return
                    }
                    !toEnd -> return
                }
            }
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
