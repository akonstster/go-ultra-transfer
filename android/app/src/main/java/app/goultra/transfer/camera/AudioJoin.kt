package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sound of a clip of pieces, joined as a video editor does it: the picture is cut exactly at each piece's ends,
 * and at every join the sounds overlap — the first piece's sound runs on [JOIN_US] past its end, fading out, while the
 * next one's starts [JOIN_US] before its start, fading in (constant power, as editors default to: two different sounds pass at a steady loudness). The window is
 * centred on the cut, so the clip's sound is exactly as long as its picture and stays in step with it. At a short
 * piece the window narrows to half the piece. The clip's very start and end fade in / out over [EDGE_US] (no click).
 *
 * Works in frames (one sample per channel) at [rate], interleaved 16-bit PCM of [channels]; the source is read by
 * [render]'s caller in pieces of at most [CHUNK] frames.
 */
class AudioJoin(private val rate: Int, private val channels: Int) {
    /** A piece in source frames [start, end), with the half-window at its start ([inFrames]) and end ([outFrames]). */
    data class Part(val start: Long, val end: Long, val inFrames: Int, val outFrames: Int)

    fun frames(us: Long): Long = Math.round(us * rate / 1_000_000.0)

    /** The pieces (in order, as cut) with the windows of their joins. */
    fun plan(pieces: List<Highlight>): List<Part> {
        val spans = pieces.map { frames(it.startMs * 1000) to frames((it.endMs ?: it.startMs) * 1000) }
        val half = frames(JOIN_US)
        val joins = (0 until spans.size - 1).map { i ->
            val a = spans[i].second - spans[i].first
            val b = spans[i + 1].second - spans[i + 1].first
            min(half, min(a / 2, b / 2)).toInt()
        }
        return spans.mapIndexed { i, (s, e) ->
            Part(s, e, if (i > 0) joins[i - 1] else 0, if (i < joins.size) joins[i] else 0)
        }
    }

    /**
     * Writes the joined sound to [emit] in order. [read] (source frame, frame count) gives that many frames from the
     * source, interleaved — silence where the source has none (before its start or after its end).
     */
    fun render(parts: List<Part>, read: (Long, Int) -> ShortArray, emit: (ShortArray) -> Unit) {
        val total = parts.sumOf { it.end - it.start }
        val edge = frames(EDGE_US)
        var written = 0L // frames emitted so far: the clip's time
        var pending: ShortArray? = null // the last piece's end window, waiting for the next piece's start
        for (p in parts) {
            // 1. The join: the previous piece's window out, this one's in, over 2 × inFrames.
            if (p.inFrames > 0) {
                val n = 2 * p.inFrames
                val b = read(p.start - p.inFrames, n)
                val a = pending!!
                val out = ShortArray(n * channels)
                for (f in 0 until n) {
                    val x = (f + 0.5) / n
                    val gin = sin(x * PI / 2)
                    val gout = cos(x * PI / 2)
                    for (c in 0 until channels) {
                        val k = f * channels + c
                        out[k] = clip(a[k] * gout + b[k] * gin)
                    }
                }
                emit(edgeFade(out, written, total, edge))
                written += n
            }
            // 2. The piece itself, between its windows, in chunks.
            var at = p.start + p.inFrames
            val until = p.end - p.outFrames
            while (at < until) {
                val n = min(CHUNK.toLong(), until - at).toInt()
                emit(edgeFade(read(at, n), written, total, edge))
                written += n
                at += n
            }
            // 3. Its end window (past its end too) for the next join.
            pending = if (p.outFrames > 0) read(p.end - p.outFrames, 2 * p.outFrames) else null
        }
    }

    /** The clip's first and last [edge] frames faded in / out. */
    private fun edgeFade(buf: ShortArray, at: Long, total: Long, edge: Long): ShortArray {
        if (edge <= 0 || (at >= edge && at + buf.size / channels <= total - edge)) return buf
        for (f in 0 until buf.size / channels) {
            val t = at + f
            val g = minOf(1.0, (t + 0.5) / edge, (total - t - 0.5) / edge).coerceAtLeast(0.0)
            if (g < 1.0) for (c in 0 until channels) buf[f * channels + c] = clip(buf[f * channels + c] * g)
        }
        return buf
    }

    private fun clip(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        /** Half the crossfade: 0.1 s each side of the cut, 0.2 s in all. */
        const val JOIN_US = 100_000L
        /** The clip's very start and end. */
        const val EDGE_US = 10_000L
        const val CHUNK = 8192
    }
}
