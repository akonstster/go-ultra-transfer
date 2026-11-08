package app.goultra.transfer.camera

import kotlin.math.roundToLong

/**
 * The grid edited by hand (P3, plan §1): a tap tempo against what the analysis found, the tempo nudged a beat a minute,
 * doubled or halved, the whole grid shifted a few ms. Pure arithmetic on the beats' moments, so a hand-made grid is as
 * testable as a found one — and nothing here touches the track's own file.
 */
object MusicGrid {
    /** How much the tempo may move before it is called variable: a twentieth of it is a rounding of the beats. */
    private const val VARIABLE_SHARE = 0.05

    /** Every beat moved by [byMs]: a grid that sits a little early or late against the sound. Never before the start. */
    fun shifted(beats: List<Long>, byMs: Long): List<Long> = beats.map { it + byMs }.filter { it >= 0L }

    /**
     * The same grid at another pace ([factor] 0.5 doubles the tempo, 2 halves it; the first beat stays where it is). A
     * slower grid that stops before the end goes on with its own last gap, so the track stays covered.
     */
    fun stretched(beats: List<Long>, factor: Double, lengthMs: Long): List<Long> {
        val first = beats.firstOrNull() ?: return emptyList()
        if (factor <= 0.0 || !factor.isFinite()) return beats
        val out = ArrayList<Long>(beats.size * 2)
        for (b in beats) {
            val at = first + ((b - first) * factor).roundToLong()
            if (at >= lengthMs) break
            out += at
        }
        if (out.size >= 2 && out.last() < lengthMs) {
            val gap = out.last() - out[out.size - 2]
            var at = out.last() + gap
            while (gap > 0L && at < lengthMs) {
                out += at
                at += gap
            }
        }
        return out
    }

    /**
     * A grid from taps: the middle gap between them is the beat, and the grid starts at [firstMs] — the track's first
     * beat as it stands — running on to [lengthMs]. A tap faster than 100 ms is a slip, not a beat.
     */
    fun fromTap(firstMs: Long, taps: List<Long>, lengthMs: Long): List<Long> {
        if (taps.size < 2) return emptyList()
        val gaps = taps.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
        if (gaps.isEmpty()) return emptyList()
        val period = gaps[gaps.size / 2]
        if (period < 100L) return emptyList()
        val out = ArrayList<Long>(1024)
        var at = firstMs.coerceAtLeast(0L)
        while (at < lengthMs) {
            out += at
            at += period
        }
        return out
    }

    /** The tempo of a grid, beats a minute (0 when it has fewer than two beats). */
    fun bpm(beats: List<Long>): Double {
        val gap = BeatGrid.medianGap(beats)
        return if (gap > 0.0) 60_000.0 / gap else 0.0
    }

    /**
     * The tempo as a range, beats a minute — the slowest and the fastest the grid holds — read from the ends of its gaps
     * rather than from the widest and the narrowest of them: one lagging beat is not a tempo, one slip is not either.
     * Null when there are too few beats to say anything.
     */
    fun tempoRange(beats: List<Long>): Pair<Double, Double>? {
        val gaps = beats.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
        if (gaps.size < 4) return null
        val quick = gaps[(gaps.size * 0.05).toInt().coerceIn(0, gaps.lastIndex)]
        val slow = gaps[(gaps.size * 0.95).toInt().coerceIn(0, gaps.lastIndex)]
        if (quick <= 0L) return null
        return 60_000.0 / slow to 60_000.0 / quick
    }

    /**
     * Whether the track's tempo changes along it: a twentieth between its slowest and its fastest beat is a rounding, a
     * fifth is a tempo that moves. A moving tempo cannot be nudged as one number — the whole grid would only be stretched
     * about its first beat — so the tempo controls are off for it and only the shift is left.
     */
    fun isVariable(beats: List<Long>): Boolean {
        val (slow, fast) = tempoRange(beats) ?: return false
        return fast - slow > slow * VARIABLE_SHARE
    }
}