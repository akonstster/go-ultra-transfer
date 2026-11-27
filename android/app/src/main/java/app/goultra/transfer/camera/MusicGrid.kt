package app.goultra.transfer.camera

import kotlin.math.roundToLong

/**
 * The grid edited by hand (P3, plan §1): a tap tempo against what the analysis found, the tempo nudged a beat a minute,
 * doubled or halved, the whole grid shifted a few ms. Pure arithmetic on the beats' moments, so a hand-made grid is as
 * testable as a found one — and nothing here touches the track's own file.
 */
object MusicGrid {
    /** Sustained changes under five per cent are treated as one tempo. */
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

    /** The typical tempo across short sections, in beats a minute. */
    fun bpm(beats: List<Long>): Double {
        val periods = sectionPeriods(beats)
        val gap = periods.getOrNull(periods.size / 2) ?: 0.0
        return if (gap > 0.0) 60_000.0 / gap else 0.0
    }

    /** Estimate one BPM when every mark agrees with a rigid grid to within timestamp rounding. */
    fun rigidBpm(beats: List<Long>): Double? {
        if (beats.size < 3) return null
        val first = beats.first()
        val period = (beats.last() - first).toDouble() / beats.lastIndex
        if (period <= 0.0 || beats.indices.any { i -> kotlin.math.abs((beats[i] - first) - i * period) > 1.5 }) return null
        return 60_000.0 / period
    }

    /** Tempo around the playhead, ignoring a missed or extra mark inside the local window. */
    fun bpmAt(beats: List<Long>, atMs: Long): Double {
        if (beats.size < 2) return 0.0
        val found = beats.binarySearch(atMs)
        val index = if (found < 0) -found - 1 else found
        val count = minOf(8, beats.lastIndex)
        val from = (index - count / 2).coerceIn(0, beats.lastIndex - count)
        val gaps = (from until from + count).map { beats[it + 1] - beats[it] }.sorted()
        val median = gaps[gaps.size / 2].toDouble()
        if (median <= 0.0) return 0.0
        val period = gaps.filter { it >= median * 0.70 && it <= median * 1.35 }.average()
        return 60_000.0 / period
    }

    /** One exact tempo through the nearest existing beat, or through the playhead when there are no beats yet. */
    fun constantAt(beats: List<Long>, lengthMs: Long, bpm: Double, atMs: Long): List<Long> {
        if (bpm !in 20.0..400.0 || lengthMs <= 0L) return beats
        val period = 60_000.0 / bpm
        val anchor = beats.minByOrNull { kotlin.math.abs(it - atMs) } ?: atMs.coerceIn(0L, lengthMs - 1)
        val first = anchor % period
        return buildList {
            var index = 0
            while (true) {
                val beat = (first + index * period).roundToLong()
                if (beat >= lengthMs) break
                add(beat)
                index++
            }
        }
    }

    /**
     * Tempo sustained across sections. Individual beat placements can move without changing the underlying tempo.
     */
    fun tempoRange(beats: List<Long>): Pair<Double, Double>? {
        if (beats.size < 5) return null
        val periods = sectionPeriods(beats)
        if (periods.size < 4) return null
        val quick = periods[(periods.size * 0.05).toInt()]
        val slow = periods[(periods.size * 0.95).toInt()]
        return 60_000.0 / slow to 60_000.0 / quick
    }

    private fun sectionPeriods(beats: List<Long>): List<Double> {
        val span = ((beats.size - 1) / 5).coerceIn(1, 8)
        return (0 until beats.size - span).mapNotNull { start ->
            val length = beats[start + span] - beats[start]
            (length.toDouble() / span).takeIf { it > 0.0 }
        }.sorted()
    }

    /**
     * A moving tempo cannot be nudged as one number: stretching the whole grid around its first beat would move the
     * other sections too. The tempo controls are off for it and only the shift is left.
     */
    fun isVariable(beats: List<Long>): Boolean {
        val (slow, fast) = tempoRange(beats) ?: return false
        return fast - slow > slow * VARIABLE_SHARE
    }
}
