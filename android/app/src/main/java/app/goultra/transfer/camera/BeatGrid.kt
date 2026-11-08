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

    /** The step of the loudness curve the drop is looked for in, ms: a tenth of a second puts it on the right beat. */
    internal const val LOUD_STEP_MS = 100L

    /** The seconds a drop is read across: a step is felt inside half a second, and the loud part must hold for four. */
    private const val STEP_SECONDS = 0.5
    private const val LOUD_SECONDS = 4.0

    /** How loud the part after a drop must be against the loudest of the piece, and how much louder than the part before. */
    private const val LOUD_SHARE = 0.5f
    private const val QUIET_SHARE = 0.8f

    /** How far a beat walks off the grid to sit on the strongest start near it, ms. */
    private const val SNAP_MS = 30L

    /** How many frames the whole grid may be shifted to sit on the sound itself: a bias of the envelope, taken out. */
    private const val BIAS_FRAMES = 3

    /** How far a beat's step may stand off the tempo, as a share of it: a tempo that drifts is followed, a jump is not. */
    private const val TEMPO_SCAN = 0.5

    /** What one step off the tempo costs, against a beat's own worth (1 for the strongest start in the piece). */
    private const val TIGHTNESS = 10.0

    /** Where a start must stand above the envelope's mean, as a share of the way to its loudest, to be a candidate. */
    private const val START_FLOOR = 0.25f

    /** The starts a piece needs before a path is chosen through them; fewer, and the old walk has the last word. */
    private const val MIN_STARTS = 8

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
     * The grid of a whole piece whose tempo may drift — a band that speeds up, a live take that wanders: every beat is
     * put on a real start of the sound. The starts are the candidates, and the path through them that best fits a steady
     * pace (a beat is worth what its start is worth, a step costs how far it stands off the tempo) is chosen for the
     * whole piece at once — dynamic programming, not one step at a time. That is what was wrong before: a walk that
     * searched 30 ms around its own guess could not come back once the sound had moved on, and the tempo it walked by
     * was an average of four seconds, so a passage that sped up was left behind for good — the grid stood later and
     * later, and the click with it.
     */
    fun detectDrifting(samples: FloatArray, sampleRate: Int): Grid? {
        val onset = smoothed(onsets(samples, sampleRate))
        if (onset.isEmpty()) return null
        val fps = sampleRate.toDouble() / HOP
        val opening = tempoOf(onset, fps) ?: return null
        val lengthMs = samples.size * 1000L / sampleRate
        val started = starts(onset, opening.period)
        val path = if (started.size >= MIN_STARTS) track(onset, started, opening.period) else emptyList()
        // Too few starts to choose a path through (a wash of sound, a spoken piece): the walk still makes a grid.
        val beats = if (path.isNotEmpty()) beatsOf(path, sampleRate, lengthMs)
        else walk(onset, opening, sampleRate, lengthMs)
        if (beats.isEmpty()) return null
        // What the envelope itself is early or late by, taken out of the whole grid: the *middle* of what the beats stand
        // off their starts, so one beat on the wrong start cannot drag the rest of the grid along (a sum could).
        val tuned = aligned(beats, started, sampleRate)
        return Grid(60_000.0 / medianGap(tuned), tuned.first(), tuned, opening.confidence)
    }

    /**
     * The greedy walk the path replaced: the tempo once, then every further beat takes the period of the four seconds
     * around it and sits on the strongest start within [SNAP_MS]. Kept for a piece whose starts are too few to track.
     */
    private fun walk(onset: FloatArray, opening: Tempo, sampleRate: Int, lengthMs: Long): List<Long> {
        val fps = sampleRate.toDouble() / HOP
        val beats = ArrayList<Long>(1024)
        var at = opening.phase.toDouble()
        var period = opening.period
        val snap = (SNAP_MS * fps / 1000).toInt().coerceAtLeast(1)
        var last = -1.0
        while (frameMs(at, sampleRate) < lengthMs) {
            // The walk gives the tempo, the sound gives the moment: every beat sits on the strongest start near it — and
            // stays between frames, or the beats jump in whole steps of 23 ms and a steady 120 reads as 118.
            val exact = snapExact(onset, at, snap).coerceAtLeast(last + 0.01)
            last = exact
            beats += frameMs(exact, sampleRate)
            period = localPeriod(onset, at, period, fps)
            at += period
        }
        return beats
    }

    /**
     * Every start the sound has, as a frame (between frames): the frames where the onset envelope peaks above a floor a
     * quarter of the way from its mean to its loudest — a rise too small to be a note is no candidate for a beat.
     */
    private fun starts(onset: FloatArray, period: Double): List<Double> {
        if (onset.size < 3) return emptyList()
        val mean = onset.sumOf { it.toDouble() } / onset.size
        val top = onset.max().toDouble()
        if (top <= 0.0) return emptyList()
        val floor = mean + (top - mean) * START_FLOOR
        // A third of a beat: the envelope rings for a frame or two, and that is one start, not two.
        val apart = (period * 0.3).coerceAtLeast(2.0)
        val out = ArrayList<Double>(onset.size / 4 + 1)
        for (i in 1 until onset.size - 1) {
            val v = onset[i]
            if (v < floor) continue
            // A peak, not a slope: the frame after is no higher (two equal frames are one peak).
            if (v < onset[i - 1] || v < onset[i + 1]) continue
            val frame = peakFrame(onset, i)
            if (out.isNotEmpty() && frame - out.last() < apart) {
                // Two peaks a moment apart are one start, and the stronger of them stands for it.
                val was = out.removeAt(out.lastIndex)
                out += if (v >= onset[was.roundToInt().coerceIn(0, onset.lastIndex)]) frame else was
            } else {
                out += frame
            }
        }
        return out
    }

    /** The peak of the envelope between [i] and its neighbours: the parabola through the three frames. */
    private fun peakFrame(onset: FloatArray, i: Int): Double {
        if (i <= 0 || i >= onset.size - 1) return i.toDouble()
        val l = onset[i - 1].toDouble()
        val c = onset[i].toDouble()
        val r = onset[i + 1].toDouble()
        val bend = l - 2 * c + r
        val shift = if (bend != 0.0) (0.5 * (l - r) / bend).coerceIn(-0.5, 0.5) else 0.0
        return i + shift
    }

    /**
     * The path through the starts (frames) that best fits a steady pace: a beat is worth as much as its start is strong,
     * and every step costs how far it stands off the tempo, squared and weighted — so a path that skips a start, or takes
     * two steps where the sound has one, earns less than it pays. Chosen for the whole piece at once, from the last beat
     * back: a beat can always be the next one, never only the one the walk guessed.
     */
    private fun track(onset: FloatArray, started: List<Double>, period: Double): List<Double> {
        val n = started.size
        if (n < 2) return emptyList()
        val worth = DoubleArray(n) { i -> onset[started[i].roundToInt().coerceIn(0, onset.lastIndex)].toDouble() }
        val top = worth.max()
        if (top <= 0.0) return emptyList()
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val near = period * (1.0 - TEMPO_SCAN)
        val far = period * (1.0 + TEMPO_SCAN)
        for (j in 0 until n) {
            // A beat earns what its start is worth, and may be the first of the path (a piece does not always open on a
            // beat it can hear): the step before it adds what the path up to there had earned, less its own lean.
            var along = 0.0
            var from = -1
            // Only the starts within one step can be the beat before this one; the walk back stops at the first too far.
            var i = j - 1
            while (i >= 0) {
                val step = started[j] - started[i]
                if (step > far) break
                if (step >= near) {
                    val lean = ln(step / period)
                    val earned = score[i] - TIGHTNESS * lean * lean
                    if (earned > along) {
                        along = earned
                        from = i
                    }
                }
                i--
            }
            score[j] = worth[j] / top + along
            back[j] = from
        }
        var best = 0
        for (i in 1 until n) if (score[i] > score[best]) best = i
        val path = ArrayList<Double>(n)
        var at = best
        while (at >= 0) {
            path += started[at]
            at = back[at]
        }
        path.reverse()
        return path
    }

    /**
     * The path's frames as beat moments, ms. A gap too wide for one step — a quiet stretch the path stepped over, a
     * passage with no starts in it — is filled in evenly, so the grid covers the whole piece and the click goes on.
     */
    private fun beatsOf(path: List<Double>, sampleRate: Int, lengthMs: Long): List<Long> {
        if (path.isEmpty()) return emptyList()
        val gaps = path.zipWithNext { a, b -> b - a }.sorted()
        val step = if (gaps.isEmpty()) 0.0 else gaps[gaps.size / 2]
        val out = ArrayList<Long>(path.size + 8)
        var was: Double? = null
        for (frame in path) {
            val before = was
            if (before != null && step > 0.0) {
                val gap = frame - before
                if (gap > step * 1.5) {
                    val pieces = (gap / step).roundToInt().coerceAtLeast(2)
                    for (k in 1 until pieces) out += frameMs(before + gap * k / pieces, sampleRate)
                }
            }
            out += frameMs(frame, sampleRate)
            was = frame
        }
        return out.filter { it in 0L until lengthMs }.distinct().sorted()
    }

    /**
     * The grid moved by the middle of what its beats stand off their starts, within [BIAS_FRAMES]: the envelope is a
     * little early or late by its own making (its window's middle, a click's decay), and one number for the whole grid is
     * the honest cure. The middle, not a sum: a grid that drifts cannot be tuned by one that adds up several beats.
     */
    private fun aligned(beats: List<Long>, started: List<Double>, sampleRate: Int): List<Long> {
        if (beats.isEmpty() || started.isEmpty()) return beats
        val offs = ArrayList<Long>(beats.size)
        for (beat in beats) {
            val frame = frameOf(beat, sampleRate).toDouble()
            val near = started.minByOrNull { abs(it - frame) } ?: continue
            offs += beat - frameMs(near, sampleRate)
        }
        if (offs.isEmpty()) return beats
        offs.sort()
        val frameStep = HOP * 1000L / sampleRate
        val shift = offs[offs.size / 2].coerceIn(-BIAS_FRAMES * frameStep, BIAS_FRAMES * frameStep)
        if (shift == 0L) return beats
        return beats.map { it + shift }.filter { it >= 0L }
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
            dropMs = dropOf(loudness(samples, sampleRate), LOUD_STEP_MS, grid?.beats.orEmpty()),
            confidence = grid?.confidence ?: 0.0,
        )
    }

    /**
     * Where the track's loud part begins (the plan's «дроп»): the moment the loudness *steps up* — a loud stretch after a
     * clearly quieter one — put on the beat that opens it. What is searched is the step itself (what stands after the
     * moment against what stands before it), not the start of the brightest stretch: a rise before a drop (a roll, a
     * riser) is quieter than the drop but already bright, and a search for «where the bright part begins» walked back into
     * it — the drop then stood two beats early. A beat too early is worse than one a hair late.
     */
    internal fun dropOf(energy: FloatArray, stepMs: Long = ENERGY_STEP_MS, beats: List<Long> = emptyList()): Long? {
        if (stepMs <= 0L) return null
        // The step is read across half a second — a drop jumps inside a beat, a riser climbs for seconds — and the loud
        // part it opens must hold for seconds, so one loud hit is never a drop.
        val near = (STEP_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(1)
        val far = (LOUD_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(near + 1)
        if (energy.size < 2 * near + far) return null
        val peak = energy.max()
        if (peak <= 0f) return null
        var bestAt = -1
        var bestScore = 0.0
        for (t in near until energy.size - far) {
            val later = mean(energy, t, t + near)
            // Loud after the moment — at once, and for the seconds that follow — and clearly quieter right before it.
            if (later < peak * LOUD_SHARE) continue
            if (mean(energy, t, t + far) < peak * LOUD_SHARE) continue
            val earlier = mean(energy, t - near, t)
            if (earlier > later * QUIET_SHARE) continue
            if (later - earlier > bestScore) {
                bestScore = later - earlier
                bestAt = t
            }
        }
        if (bestAt < 0) return null
        return onBeat(bestAt * stepMs, beats)
    }

    /** [ms] put on the grid: the beat that opens the loud part (the next one when it is a moment away, else the one before). */
    private fun onBeat(ms: Long, beats: List<Long>): Long {
        if (beats.isEmpty()) return ms
        val period = medianGap(beats).takeIf { it > 0.0 } ?: return ms
        val next = beats.firstOrNull { it >= ms } ?: return beats.lastOrNull() ?: ms
        val was = beats.lastOrNull { it < ms }
        return if (was == null || next - ms <= period * 0.35) next else was
    }

    /** The mean of [from] until [to] of [values] (0 when the range is empty). */
    private fun mean(values: FloatArray, from: Int, to: Int): Double {
        val low = from.coerceAtLeast(0)
        val high = to.coerceAtMost(values.size)
        if (high <= low) return 0.0
        var sum = 0.0
        for (i in low until high) sum += values[i]
        return sum / (high - low)
    }

    /**
     * The loudness of [samples] in [stepMs] steps, 0…1: the root mean square, which hears a chorus as loud and a quiet
     * verse as quiet — an envelope of *starts* does not (a quiet passage full of drums is all starts and no loudness).
     */
    internal fun loudness(samples: FloatArray, sampleRate: Int, stepMs: Long = LOUD_STEP_MS): FloatArray {
        if (stepMs <= 0L) return FloatArray(0)
        val step = (sampleRate.toLong() * stepMs / 1000).toInt().coerceAtLeast(1)
        val steps = samples.size / step
        if (steps <= 0) return FloatArray(0)
        val out = FloatArray(steps)
        var top = 0.0
        for (s in 0 until steps) {
            var sum = 0.0
            for (i in s * step until (s + 1) * step) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / step)
            out[s] = rms.toFloat()
            if (rms > top) top = rms
        }
        if (top <= 0.0) return out
        for (s in out.indices) out[s] = (out[s] / top).toFloat()
        return out
    }

    /** The middle gap between the beats, ms (0 when there are fewer than two). */
    internal fun medianGap(beats: List<Long>): Double {
        if (beats.size < 2) return 0.0
        val gaps = beats.zipWithNext { a, b -> b - a }.sorted()
        return gaps[gaps.size / 2].toDouble()
    }

    /**
     * The strongest start within [window] frames of [frame], between frames: the walk gives the tempo, the sound gives
     * the moment, and the peak's parabola gives the moment finer than one frame (23 ms of a frame would put a steady
     * 120 at 118).
     */
    private fun snapExact(onset: FloatArray, frame: Double, window: Int): Double {
        if (onset.isEmpty()) return 0.0
        val centre = frame.roundToInt().coerceIn(0, onset.size - 1)
        var best = centre
        var top = onset[centre]
        for (f in (centre - window).coerceAtLeast(1)..(centre + window).coerceAtMost(onset.size - 2)) {
            if (onset[f] > top) {
                top = onset[f]
                best = f
            }
        }
        // The parabola needs a neighbour on each side: at the very edges of the track there is none (the last frame of a
        // long track once walked off the end and lost the whole analysis).
        if (best <= 0 || best >= onset.size - 1) return best.toDouble()
        val l = onset[best - 1].toDouble()
        val c = onset[best].toDouble()
        val r = onset[best + 1].toDouble()
        val shift = if (l - 2 * c + r != 0.0) (0.5 * (l - r) / (l - 2 * c + r)).coerceIn(-0.5, 0.5) else 0.0
        return best + shift
    }

    /** A frame's moment in the track: a frame stands for its window's middle — a hit is heard there, not at its start. */
    internal fun frameMs(frame: Double, sampleRate: Int): Long =
        ((frame * HOP + WINDOW / 2) * 1000.0 / sampleRate).roundToInt().toLong()

    /** The frame a moment of the track falls on: the inverse of [frameMs]. */
    internal fun frameOf(ms: Long, sampleRate: Int): Int =
        ((ms.toDouble() * sampleRate / 1000.0 - WINDOW / 2) / HOP).roundToInt()

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
