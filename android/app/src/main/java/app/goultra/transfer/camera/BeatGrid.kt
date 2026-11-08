package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The beat of a piece of music, found in its sound (see `memory-bank/projects-plan.md`, P3): where the notes start
 * (spectral flux), how often they repeat (autocorrelation of that, leaning toward a usual tempo) and where the grid
 * sits (the phase the starts fall on most). Pure arithmetic on samples — no library, so the app keeps its own licence.
 */
object BeatGrid {
    /** A grid: the tempo, the first beat, every beat (ms) and how sure the detector is of it (0…1). */
    data class Grid(val bpm: Double, val offsetMs: Long, val beats: List<Long>, val confidence: Double)

    /**
     * What the analysis says about a track beyond its grid (P3): how steady its tempo is, how busy it is, its energy
     * along the piece and where the drop is (plan §5).
     */
    class Profile(
        /** The tempo the grid mostly keeps, beats a minute. */
        val bpm: Double,
        /** 0…1: the share of the grid whose beats are the same period within 2 % (1 — a metronome). */
        val stability: Double,
        /** 0…1: how busy the piece is (the mean of the energy curve). */
        val drive: Double,
        /** The loudness of the starts, 0…1, one value per [ENERGY_STEP_MS]. */
        val energy: FloatArray,
        /** Where the energy leaps after a quiet stretch, ms of the track; null when it does not. */
        val dropMs: Long?,
        /** The grid's own certainty (0…1). */
        val confidence: Double,
    )

    private const val WINDOW = 1024
    private const val HOP = 512
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** The tempo the eye and the foot expect most: the detector leans toward it between a tempo and its double. */
    private const val USUAL_BPM = 120.0

    /** The seconds around a beat its own period is read from: a tempo that drifts is followed, not averaged away. */
    private const val LOCAL_SECONDS = 4.0

    /** The step of the energy curve, ms (one value a second is plenty to see a drop in). */
    internal const val ENERGY_STEP_MS = 1000L

    /** How far a beat walks off the grid to sit on the strongest start near it, ms. */
    private const val SNAP_MS = 30L

    /**
     * The grid of [samples] (mono, −1…1) at [sampleRate]; null when there is nothing to find (silence, or too short for
     * a few beats).
     */
    fun detect(samples: FloatArray, sampleRate: Int): Grid? {
        val onset = smoothed(onsets(samples, sampleRate))
        val fps = sampleRate.toDouble() / HOP
        val tempo = tempoOf(onset, fps) ?: return null
        // The period is in frames; a frame is HOP samples, so one period is period / fps seconds.
        val periodMs = 1000.0 * tempo.period / fps
        val offsetMs = frameMs(tempo.phase.toDouble(), sampleRate)
        val lengthMs = samples.size * 1000L / sampleRate
        val beats = generateSequence(0) { it + 1 }
            .map { (offsetMs + it * periodMs).roundToInt().toLong() }
            .takeWhile { it < lengthMs }
            .toList()
        return Grid(60.0 * fps / tempo.period, offsetMs, beats, tempo.confidence)
    }

    /**
     * The grid of a whole piece whose tempo may drift — a band that speeds up, a live take that wanders: the tempo is
     * found once, then every further beat takes the period of the four seconds around it, searched only within ±15 % of
     * the period before (so a tempo never jumps an octave, and a busy passage never doubles it).
     */
    fun detectDrifting(samples: FloatArray, sampleRate: Int): Grid? {
        val onset = smoothed(onsets(samples, sampleRate))
        val fps = sampleRate.toDouble() / HOP
        val opening = tempoOf(onset, fps) ?: return null
        val lengthMs = samples.size * 1000L / sampleRate
        val beats = ArrayList<Long>(1024)
        var at = opening.phase.toDouble()
        var period = opening.period
        val snap = (SNAP_MS * fps / 1000).toInt().coerceAtLeast(1)
        var lastFrame = -1
        while (frameMs(at, sampleRate) < lengthMs) {
            // The walk gives the tempo, the sound gives the moment: every beat sits on the strongest start near it.
            val frame = snapTo(onset, at.roundToInt(), snap).coerceAtLeast(lastFrame + 1)
            lastFrame = frame
            beats += frameMs(frame.toDouble(), sampleRate)
            period = localPeriod(onset, at, period, fps)
            at += period
        }
        if (beats.isEmpty()) return null
        return Grid(60_000.0 / medianGap(beats), beats.first(), beats, opening.confidence)
    }

    /** The tempo of an envelope (its period finer than a frame), where a bar starts, and how sure it is. */
    internal class Tempo(val period: Double, val phase: Int, val confidence: Double)

    /** The tempo of the whole envelope: the lag it repeats at, leaning toward [USUAL_BPM], and the phase it falls on. */
    internal fun tempoOf(onset: FloatArray, fps: Double): Tempo? {
        if (onset.size < fps * 4) return null
        val energy = onset.sumOf { it.toDouble() * it }
        if (energy <= 1e-9) return null
        val minLag = (60.0 * fps / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (60.0 * fps / MIN_BPM).toInt().coerceAtMost(onset.size / 2)
        if (maxLag <= minLag + 2) return null
        val ac = DoubleArray(maxLag + 2) { lag -> autocorrelation(onset, lag) }
        var best = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * fps / lag
            val octaves = ln(bpm / USUAL_BPM) / ln(2.0)
            val score = ac[lag] * exp(-0.5 * octaves * octaves)
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0) return null
        // Between frames: the peak's parabola gives the tempo finer than one frame.
        val l = ac[best - 1]; val c = ac[best]; val r = ac[best + 1]
        val shift = if (best > minLag && best < maxLag && l - 2 * c + r != 0.0) (0.5 * (l - r) / (l - 2 * c + r)).coerceIn(-0.5, 0.5) else 0.0
        val period = best + shift
        // The phase the starts fall on most, along the whole piece.
        var phase = 0
        var phaseScore = -1.0
        for (p in 0 until period.roundToInt().coerceAtLeast(1)) {
            var s = 0.0
            var k = 0
            while (true) {
                val at = (p + k * period).roundToInt()
                if (at >= onset.size) break
                s += onset[at]
                k++
            }
            if (s > phaseScore) { phaseScore = s; phase = p }
        }
        return Tempo(period, phase, (ac[best] / ac[0]).coerceIn(0.0, 1.0))
    }
    /**
     * The period of the seconds around [centre] (frames), searched near [previous]: the highest normalised
     * autocorrelation within ±15 % of it. A quiet or muddy stretch keeps the period it came with.
     */
    internal fun localPeriod(onset: FloatArray, centre: Double, previous: Double, fps: Double): Double {
        val half = (LOCAL_SECONDS * fps / 2).toInt().coerceAtLeast(4)
        val from = (centre.roundToInt() - half).coerceAtLeast(0)
        val to = (centre.roundToInt() + half).coerceAtMost(onset.size)
        if (to - from < half) return previous
        val low = (previous * 0.85).roundToInt().coerceAtLeast(1)
        val high = (previous * 1.15).roundToInt().coerceAtLeast(low + 2)
        fun score(lag: Int): Double {
            if (lag < 1) return 0.0
            var s = 0.0
            var e = 0.0
            for (i in from until to - lag) {
                val v = onset[i].toDouble()
                s += v * onset[i + lag]
                e += v * v
            }
            return if (e <= 0.0) 0.0 else s / e
        }
        var bestLag = -1
        var best = 0.0
        for (lag in low..high.coerceAtMost(to - from - 1)) {
            val s = score(lag)
            if (s > best) { best = s; bestLag = lag }
        }
        if (bestLag < 0 || best < 0.15) return previous
        // Between frames again: the peak's parabola.
        val l = score(bestLag - 1)
        val c = score(bestLag)
        val r = score(bestLag + 1)
        val shift = if (l - 2 * c + r != 0.0) (0.5 * (l - r) / (l - 2 * c + r)).coerceIn(-0.5, 0.5) else 0.0
        return bestLag + shift
    }

    /** The profile of [samples] with the [grid] found for them: stability, drive, the energy curve and the drop. */
    fun profile(samples: FloatArray, sampleRate: Int, grid: Grid?): Profile? {
        val onset = smoothed(onsets(samples, sampleRate))
        if (onset.isEmpty()) return null
        val seconds = (onset.size.toLong() * HOP / sampleRate).toInt().coerceAtLeast(1)
        val loudest = FloatArray(seconds)
        for (i in onset.indices) {
            val at = (i.toLong() * HOP / sampleRate).toInt().coerceIn(0, loudest.size - 1)
            if (onset[i] > loudest[at]) loudest[at] = onset[i]
        }
        val peak = loudest.maxOrNull() ?: 0f
        val energy = if (peak > 0f) FloatArray(loudest.size) { loudest[it] / peak } else loudest
        val gaps = grid?.beats?.zipWithNext { a, b -> b - a }.orEmpty()
        val median = medianGap(grid?.beats.orEmpty())
        val steady = if (gaps.isEmpty() || median <= 0.0) 0.0
        else gaps.count { abs(it - median) <= median * 0.02 }.toDouble() / gaps.size
        return Profile(
            bpm = if (median > 0.0) 60_000.0 / median else 0.0,
            stability = steady,
            drive = energy.average().coerceIn(0.0, 1.0),
            energy = energy,
            dropMs = dropOf(energy),
            confidence = grid?.confidence ?: 0.0,
        )
    }

    /** Where the energy leaps out of a quiet stretch: the biggest rise over the four seconds before it, if it is one. */
    internal fun dropOf(energy: FloatArray): Long? {
        var best = 0f
        var at: Long? = null
        for (i in 4 until energy.size) {
            val quiet = (i - 4 until i).minOf { energy[it] }
            val rise = energy[i] - quiet
            if (rise > best && rise > 0.25f) {
                best = rise
                at = i * ENERGY_STEP_MS
            }
        }
        return at
    }

    /** The middle gap between the beats, ms (0 when there are fewer than two). */
    internal fun medianGap(beats: List<Long>): Double {
        if (beats.size < 2) return 0.0
        val gaps = beats.zipWithNext { a, b -> b - a }.sorted()
        return gaps[gaps.size / 2].toDouble()
    }

    /** The strongest start within [window] frames of [frame]: the walk gives the tempo, the sound gives the moment. */
    private fun snapTo(onset: FloatArray, frame: Int, window: Int): Int {
        if (onset.isEmpty()) return 0
        var best = frame.coerceIn(0, onset.size - 1)
        var top = onset[best]
        for (f in (frame - window).coerceAtLeast(0)..(frame + window).coerceAtMost(onset.size - 1)) {
            if (onset[f] > top) {
                top = onset[f]
                best = f
            }
        }
        return best
    }

    /** A frame's moment in the track: a frame stands for its window's middle — a hit is heard there, not at its start. */
    internal fun frameMs(frame: Double, sampleRate: Int): Long =
        ((frame * HOP + WINDOW / 2) * 1000.0 / sampleRate).roundToInt().toLong()

    /** The envelope smoothed over a few frames: a tempo whose period falls between frames still lines its hits up. */
    internal fun smoothed(raw: FloatArray): FloatArray = FloatArray(raw.size) { i ->
        var s = 0f
        var w = 0f
        for (d in -2..2) {
            val j = i + d
            if (j in raw.indices) {
                val k = (3 - abs(d)).toFloat()
                s += raw[j] * k
                w += k
            }
        }
        s / w
    }

    /** How strongly notes start in each frame: the spectrum's rise from the frame before, with the local average taken off. */
    internal fun onsets(samples: FloatArray, sampleRate: Int): FloatArray {
        val frames = if (samples.size < WINDOW) 0 else (samples.size - WINDOW) / HOP + 1
        val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
        val re = DoubleArray(WINDOW)
        val im = DoubleArray(WINDOW)
        var prev = DoubleArray(WINDOW / 2)
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val at = f * HOP
            for (i in 0 until WINDOW) { re[i] = samples[at + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val mag = DoubleArray(WINDOW / 2) { ln(1.0 + 10.0 * sqrt(re[it] * re[it] + im[it] * im[it])) }
            var rise = 0.0
            for (b in mag.indices) { val d = mag[b] - prev[b]; if (d > 0) rise += d }
            flux[f] = if (f == 0) 0f else rise.toFloat()
            prev = mag
        }
        // A steady loudness is not a start: only what stands out of the frames around it counts.
        val half = 8
        return FloatArray(frames) { f ->
            var s = 0.0
            var n = 0
            for (j in maxOf(0, f - half)..minOf(frames - 1, f + half)) { s += flux[j]; n++ }
            (flux[f] - s / n).toFloat().coerceAtLeast(0f)
        }
    }

    private fun autocorrelation(x: FloatArray, lag: Int): Double {
        var s = 0.0
        for (i in 0 until x.size - lag) s += x[i].toDouble() * x[i + lag]
        return s / (x.size - lag).coerceAtLeast(1) * x.size
    }

    /** In-place radix-2 FFT ([re].size a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            for (i in 0 until n step len) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
            }
            len = len shl 1
        }
    }

    /** The beat of [grid] nearest [ms] (a cut snaps to it); [ms] itself when the grid has none. */
    fun nearest(grid: Grid, ms: Long): Long = grid.beats.minByOrNull { abs(it - ms) } ?: ms
}
