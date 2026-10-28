package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.sin

/**
 * Fades at the joins of a clip whose sound is re-encoded: each piece's sound comes in over [inUs] at its start and goes
 * out over [outUs] at its end ([durationUs] long), a quarter sine (smooth both ends) — no click where two pieces meet,
 * the picture and the sound kept in step (nothing overlaps, nothing moves).
 */
class AudioFade(private val inUs: Long, private val outUs: Long, private val durationUs: Long) {
    /** The gain (0…1) at [tUs] from the piece's start. */
    fun gain(tUs: Long): Float {
        var g = 1f
        if (inUs > 0 && tUs < inUs) g = minOf(g, curve(tUs.toFloat() / inUs))
        val left = durationUs - tUs
        if (outUs > 0 && left < outUs) g = minOf(g, curve(left.toFloat() / outUs))
        return g
    }

    private fun curve(x: Float) = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    companion object {
        /** At a join between two pieces: 0.1 s out + 0.1 s in. */
        const val JOIN_US = 100_000L
        /** At the clip's very start and end: just enough to avoid a click. */
        const val EDGE_US = 20_000L

        /** The fades of piece [i] of [count], [durationUs] long. */
        fun of(i: Int, count: Int, durationUs: Long) =
            AudioFade(if (i > 0) JOIN_US else EDGE_US, if (i < count - 1) JOIN_US else EDGE_US, durationUs)
    }
}
