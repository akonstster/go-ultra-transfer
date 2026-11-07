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

    private const val WINDOW = 1024
    private const val HOP = 512
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** The tempo the eye and the foot expect most: the detector leans toward it between a tempo and its double. */
    private const val USUAL_BPM = 120.0

    /**
     * The grid of [samples] (mono, −1…1) at [sampleRate]; null when there is nothing to find (silence, or too short for
     * a few beats).
     */
    fun detect(samples: FloatArray, sampleRate: Int): Grid? {
        val raw = onsets(samples, sampleRate)
        // Smoothed over a few frames: a tempo whose period falls between frames still lines its hits up (otherwise
        // its double, a whole number of frames by chance, won).
        val onset = FloatArray(raw.size) { i ->
            var s = 0f
            var w = 0f
            for (d in -2..2) {
                val j = i + d
                if (j in raw.indices) { val k = (3 - abs(d)).toFloat(); s += raw[j] * k; w += k }
            }
            s / w
        }
        val fps = sampleRate.toDouble() / HOP
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
        val bpm = 60.0 * fps / period
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
        val periodMs = 60_000.0 / bpm
        // A frame stands for the middle of its window: a hit is heard there, not where the window opens.
        val offsetMs = ((phase * HOP + WINDOW / 2) * 1000.0 / sampleRate).roundToInt().toLong()
        val lengthMs = samples.size * 1000L / sampleRate
        val beats = generateSequence(0) { it + 1 }
            .map { (offsetMs + it * periodMs).roundToInt().toLong() }
            .takeWhile { it < lengthMs }
            .toList()
        val confidence = (ac[best] / ac[0]).coerceIn(0.0, 1.0)
        return Grid(bpm, offsetMs, beats, confidence)
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
