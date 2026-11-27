package app.goultra.transfer.camera

import android.media.MediaExtractor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A project's sound for a save «as shot» with music (P2): the picture is copied untouched, only the sound is made anew —
 * the clips' own sound, piece by piece exactly where the picture was cut, under the music from its offset, each at its
 * own level. Everything is brought to [RATE] stereo (a camera's 48 kHz and a track's 44.1 kHz meet here) and encoded to
 * AAC — seconds of work, against minutes for the picture.
 */
object ProjectSound {
    const val RATE = 48_000
    const val CHANNELS = 2

    /** Out frames per step: a tenth of a second or so. */
    private const val CHUNK = 4_096
    private const val WAVE_HEADER_BYTES = 44
    private const val FRAME_BYTES = CHANNELS * 2

    /** One file's pieces as the picture really took them (source ms); [open] sets an extractor on the file. */
    class Part(val open: (MediaExtractor) -> Unit, val pieces: List<Highlight>)

    /**
     * The clips' sound at [clipsGain] under [music] into [file] as AAC; as long as the pieces together. [cancelled] is
     * checked between chunks. A file without sound is silence for its pieces, never a gap: the picture goes on.
     */
    internal fun mix(
        parts: List<Part>, clipsGain: Float, music: List<ProjectAudioClip>, musicGain: Float,
        open: (String) -> ClipAudio.PcmSource?, file: File, cancelled: () -> Boolean,
    ): ClipAudio.Encoded {
        val writer = ClipAudio.AacWriter(RATE, CHANNELS, file)
        val track = LaneReader(music, open)
        try {
            val clip = FloatArray(CHUNK * CHANNELS)
            val tune = FloatArray(CHUNK * CHANNELS)
            for (part in parts) {
                val reader = runCatching { ClipAudio.openPcm(part.open) }.getOrNull()
                try {
                    for (piece in part.pieces) {
                        val lengthMs = (piece.endMs ?: piece.startMs) - piece.startMs
                        var left = lengthMs * RATE / 1000
                        val feed = reader?.let { Feed(it, piece.startMs.toDouble()) }
                        while (left > 0) {
                            if (cancelled()) throw java.util.concurrent.CancellationException()
                            val n = minOf(CHUNK.toLong(), left).toInt()
                            if (feed != null) feed.take(n, clip) else clip.fill(0f, 0, n * CHANNELS)
                            track.take(n, tune)
                            writer.write(mixed(clip, clipsGain, tune, musicGain, n * CHANNELS))
                            left -= n
                        }
                    }
                } finally {
                    reader?.release()
                }
            }
            return writer.finish()
        } finally {
            writer.release()
            track.release()
        }
    }

    /** PCM keeps the playback clock and sample positions on the same time axis as the source analysis. */
    internal fun renderLane(
        clips: List<ProjectAudioClip>, lengthMs: Long, open: (String) -> ClipAudio.PcmSource?,
        file: File, cancelled: () -> Boolean,
    ) {
        Wave(clips, lengthMs, open).use { wave ->
            file.outputStream().buffered().use { stream ->
                val buffer = ByteArray(CHUNK * FRAME_BYTES)
                while (true) {
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    val count = wave.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    stream.write(buffer, 0, count)
                }
            }
        }
    }

    /** A seekable WAV byte stream generated on demand, with no intermediate file. */
    internal class Wave(
        clips: List<ProjectAudioClip>,
        lengthMs: Long,
        open: (String) -> ClipAudio.PcmSource?,
        position: Long = 0,
        private val mixPreview: ((ByteBuffer, Long) -> Unit)? = null,
    ) : java.io.Closeable {
        private val dataBytes = lengthMs * (RATE / 1000L) * FRAME_BYTES
        val length = WAVE_HEADER_BYTES + dataBytes
        private val header = ByteBuffer.allocate(WAVE_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            require(dataBytes in 1..0xffffffffL - 36L)
            put("RIFF".toByteArray()).putInt((dataBytes + 36).toInt())
            put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort())
            putInt(RATE).putInt(RATE * FRAME_BYTES).putShort(FRAME_BYTES.toShort()).putShort(16)
            put("data".toByteArray()).putInt(dataBytes.toInt())
        }.array()
        private val dataStart = (position - WAVE_HEADER_BYTES).coerceAtLeast(0L)
        private val track = LaneReader(clips, open, dataStart / FRAME_BYTES)
        private val samples = FloatArray(CHUNK * CHANNELS)
        private val pcm = ByteBuffer.allocate(CHUNK * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
        private var at = position

        init { require(position in 0..length) }

        fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (at == length) return -1
            if (at < WAVE_HEADER_BYTES) {
                val n = minOf(count, WAVE_HEADER_BYTES - at.toInt())
                header.copyInto(buffer, offset, at.toInt(), at.toInt() + n)
                at += n
                return n
            }
            if (!pcm.hasRemaining()) {
                val skip = ((at - WAVE_HEADER_BYTES) % FRAME_BYTES).toInt()
                val frames = minOf(CHUNK.toLong(), (length - at + skip) / FRAME_BYTES).toInt()
                track.take(frames, samples)
                pcm.clear()
                for (i in 0 until frames * CHANNELS) pcm.putShort((samples[i] * 32767f).roundToInt().toShort())
                pcm.flip()
                val firstFrame = (at - WAVE_HEADER_BYTES) / FRAME_BYTES
                mixPreview?.invoke(pcm, firstFrame)
                pcm.position(skip)
            }
            val n = minOf(count, pcm.remaining())
            pcm.get(buffer, offset, n)
            at += n
            return n
        }

        override fun close() = track.release()
    }

    private class LaneReader(clips: List<ProjectAudioClip>, val open: (String) -> ClipAudio.PcmSource?, startFrame: Long = 0) {
        private val lane = AudioLane(clips)
        private val feeds = mutableMapOf<Int, Feed>()
        private val gains = FloatArray(lane.clips.size)
        private val starts = lane.clips.map { it.startMs * RATE / 1000 }
        private val ends = lane.clips.map { it.endMs * RATE / 1000 }
        private val samples = mutableMapOf<Int, FloatArray>()
        private var frame = startFrame
        private var active = IntArray(0)
        private var nextBoundary = 0L

        fun take(count: Int, out: FloatArray) {
            out.fill(0f, 0, count * CHANNELS)
            var done = 0
            while (done < count) {
                if (frame >= nextBoundary) {
                    active = lane.clips.indices.filter { frame in starts[it] until ends[it] }.toIntArray()
                    val ended = feeds.keys.filter { ends[it] <= frame }
                    ended.forEach { index -> feeds.remove(index)?.release(); samples.remove(index) }
                    nextBoundary = minOf(
                        starts.firstOrNull { it > frame } ?: Long.MAX_VALUE,
                        active.minOfOrNull { ends[it] } ?: Long.MAX_VALUE,
                    )
                }
                val n = minOf((count - done).toLong(), nextBoundary - frame).toInt()
                for (index in active) {
                    val clip = lane.clips[index]
                    val feed = feeds.getOrPut(index) {
                        val reader = checkNotNull(open(clip.source.uri)) { clip.source.name }
                        val from = clip.fromMs + (frame - starts[index]) * 1000.0 / RATE
                        Feed(reader, from, skipPriming = true)
                    }
                    feed.take(n, samples.getOrPut(index) { FloatArray(CHUNK * CHANNELS) })
                }
                if (active.isNotEmpty()) for (i in 0 until n) {
                    lane.gains((frame + i) * 1000.0 / RATE, active, gains)
                    for (index in active) {
                        val input = samples.getValue(index)
                        for (channel in 0 until CHANNELS) {
                            out[(done + i) * CHANNELS + channel] += input[i * CHANNELS + channel] * gains[index]
                        }
                    }
                }
                frame += n
                done += n
            }
            for (i in 0 until count * CHANNELS) out[i] = out[i].coerceIn(-1f, 1f)
        }

        fun release() = feeds.values.forEach { it.release() }
    }

    /** [a]·[ga] + [b]·[gb] for the first [count] values, as 16-bit, clipped rather than wrapped round. */
    internal fun mixed(a: FloatArray, ga: Float, b: FloatArray, gb: Float, count: Int): ShortArray =
        ShortArray(count) { i -> ((a[i] * ga + b[i] * gb).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }

    /**
     * A source read on from [fromMs] and given out at [RATE] stereo: linear interpolation between its own frames (the
     * reader is asked for whole chunks one after another — a read anywhere else is a seek, and a seek is a click).
     */
    private class Feed(private val reader: ClipAudio.PcmSource, fromMs: Double, skipPriming: Boolean = false) {
        private val step = reader.rate.toDouble() / RATE
        private var pos = fromMs * reader.rate / 1000.0 + if (skipPriming) reader.delayFrames else 0
        private var start = floor(pos).toLong()
        private var frames = ShortArray(0)
        private var carry = ShortArray(CHANNELS) // the frame just before [start], to interpolate across a chunk's edge
        fun take(n: Int, out: FloatArray) {
            for (i in 0 until n) {
                val f = floor(pos).toLong()
                val t = (pos - f).toFloat()
                for (c in 0 until CHANNELS) {
                    val x0 = at(f, c)
                    val x1 = at(f + 1, c)
                    out[i * CHANNELS + c] = (x0 + (x1 - x0) * t) / 32768f
                }
                pos += step
            }
        }

        /** Channel [c] of source frame [f] (mono goes to both sides; a third channel and on are left out). */
        private fun at(f: Long, c: Int): Float {
            while (f >= start + frames.size / reader.channels) next()
            if (f < start) return carry[c].toFloat()
            val ch = if (reader.channels == 1) 0 else minOf(c, reader.channels - 1)
            return frames[((f - start) * reader.channels + ch).toInt()].toFloat()
        }

        private fun next() {
            val have = frames.size / reader.channels
            if (have > 0) {
                val last = have - 1
                for (c in 0 until CHANNELS) {
                    val ch = if (reader.channels == 1) 0 else minOf(c, reader.channels - 1)
                    carry[c] = frames[last * reader.channels + ch]
                }
            }
            start += have
            frames = reader.read(start, CHUNK)
        }

        fun release() = reader.release()
    }
}
