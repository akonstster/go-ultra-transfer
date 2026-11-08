package app.goultra.transfer.camera

import android.media.MediaExtractor
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

    /** One file's pieces as the picture really took them (source ms); [open] sets an extractor on the file. */
    class Part(val open: (MediaExtractor) -> Unit, val pieces: List<Highlight>)

    /** The music: its file, where in it the project starts (ms), and its level. */
    class Music(val open: (MediaExtractor) -> Unit, val fromMs: Long, val gain: Float)

    /**
     * The clips' sound at [clipsGain] under [music] into [file] as AAC; as long as the pieces together. [cancelled] is
     * checked between chunks. A file without sound is silence for its pieces, never a gap: the picture goes on.
     */
    fun mix(parts: List<Part>, clipsGain: Float, music: Music?, file: java.io.File, cancelled: () -> Boolean): ClipAudio.Encoded {
        val writer = ClipAudio.AacWriter(RATE, CHANNELS, file)
        val track = music?.let { m -> runCatching { ClipAudio.openPcm(m.open) }.getOrNull()?.let { Feed(it, m.fromMs) } }
        try {
            val clip = FloatArray(CHUNK * CHANNELS)
            val tune = FloatArray(CHUNK * CHANNELS)
            for (part in parts) {
                val reader = runCatching { ClipAudio.openPcm(part.open) }.getOrNull()
                try {
                    for (piece in part.pieces) {
                        val lengthMs = (piece.endMs ?: piece.startMs) - piece.startMs
                        var left = lengthMs * RATE / 1000
                        val feed = reader?.let { Feed(it, piece.startMs) }
                        while (left > 0) {
                            if (cancelled()) throw java.util.concurrent.CancellationException()
                            val n = minOf(CHUNK.toLong(), left).toInt()
                            if (feed != null) feed.take(n, clip) else clip.fill(0f, 0, n * CHANNELS)
                            if (track != null) track.take(n, tune) else tune.fill(0f, 0, n * CHANNELS)
                            writer.write(mixed(clip, clipsGain, tune, music?.gain ?: 0f, n * CHANNELS))
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
            track?.release()
        }
    }

    /** [a]·[ga] + [b]·[gb] for the first [count] values, as 16-bit, clipped rather than wrapped round. */
    internal fun mixed(a: FloatArray, ga: Float, b: FloatArray, gb: Float, count: Int): ShortArray =
        ShortArray(count) { i -> ((a[i] * ga + b[i] * gb).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }

    /**
     * A source read on from [fromMs] and given out at [RATE] stereo: linear interpolation between its own frames (the
     * reader is asked for whole chunks one after another — a read anywhere else is a seek, and a seek is a click).
     */
    private class Feed(private val reader: ClipAudio.PcmReader, fromMs: Long) {
        private val step = reader.rate.toDouble() / RATE
        private var pos = fromMs * reader.rate / 1000.0
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
