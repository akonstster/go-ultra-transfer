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
        /** Every place the energy returns after a quiet stretch, ms of the track, in order. */
        val drops: List<Long>,
        /** The grid's own certainty (0…1). */
        val confidence: Double,
    ) {
        /** The first drop: what an older project and the montage reach for when one place is enough. */
        val dropMs: Long? get() = drops.firstOrNull()
    }

    private const val WINDOW = 1024
    private const val HOP = 512
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** The band a kick and a bass drum live in: the grid's phase is checked against it, while the tempo is read from all
     *  the sound (a track without a drum still has a beat). 70–120 Hz is deliberate: a sub-bass swell and a bright synth
     *  at 160–300 Hz both sit outside it, and that synth was exactly the offbeat the grid used to land on. */
    private const val LOW_FROM_HZ = 70
    private const val LOW_TO_HZ = 120

    /** How much of a beat either side of a beat the low drum may stand and still be its own hit. */
    private const val LOW_PHASE_SHARE = 0.45

    /** The tempo the eye and the foot expect most: the detector leans toward it between a tempo and its double. */
    private const val USUAL_BPM = 120.0

    /** The seconds around a beat its own period is read from: a tempo that drifts is followed, not averaged away. */
    private const val LOCAL_SECONDS = 4.0

    /** How far the steady grid may stand off the starts of its own piece: a hit inside a tenth of a beat is on it. */
    private const val STEADY_SHARE = 0.10

    /** The weighted agreement the starts need before one fixed grid is trusted for the whole piece. A constant pulse
     *  keeps its phase (1.0); a tempo that really drifts spreads the starts around the circle and falls away from it. */
    private const val STEADY_AGREEMENT = 0.62

    /** The share of the fixed grid that must have a real start on it, so a short loud passage cannot speak for a track. */
    private const val STEADY_COVERAGE = 0.35

    /** The step of the energy curve, ms (one value a second is plenty to see a drop in). */
    internal const val ENERGY_STEP_MS = 1000L

    /** The step of the loudness curve the drop is looked for in, ms: a tenth of a second puts it on the right beat. */
    internal const val LOUD_STEP_MS = 100L

    /** The seconds a drop is read across: a step is felt inside half a second, and the loud part must hold for four. */
    private const val STEP_SECONDS = 0.5
    private const val LOUD_SECONDS = 4.0

    /** A quiet passage a drop must come out of: at least a second and a half within the six before the return, and its
     *  own loudness must be well under the level the track returns to. */
    private const val QUIET_SECONDS = 1.5
    private const val QUIET_LOOK_SECONDS = 6.0
    private const val QUIET_SHARE = 0.60f

    /** The returning loudness, against the piece's mean and against the quiet it left: a pause must really come back. */
    private const val AVERAGE_SHARE = 0.90f
    private const val RETURN_SHARE = 0.90f

    /** The loudness must still be climbing at the return — a plateau after an earlier drop is not another drop. */
    private const val RISE_SHARE = 1.05f

    /** The fallback for a piece with no pause at all: its loudest stretch must stand above its mean this much, and the
     *  drop is where it reaches most of the way there. */
    private const val FLAT_SHARE = 1.15f
    private const val REACHED_SHARE = 0.8f

    /** Two drops closer than this are one return: eight seconds also puts the previous quiet passage out of the look-back
     *  before another rise can be counted. */
    private const val DROP_APART_SECONDS = 8.0

    /** How far into the returning level a beat must be to count as the first beat out of the pause. */
    private const val RETURN_EDGE_SHARE = 0.4

    /** One drop candidate: where the returning level is reached, the quiet it left (and where that quiet ended), and
     *  that level. */
    private class DropPoint(val edge: Int, val quiet: Double, val quietEnd: Int, val level: Double)

    /** How far a beat walks off the grid to sit on the strongest start near it, ms. */
    private const val SNAP_MS = 30L

    /** How many frames the whole grid may be shifted to sit on the sound itself: a bias of the envelope, taken out. */
    private const val BIAS_FRAMES = 3

    /** How far a beat's step may stand off the tempo, as a share of it: a tempo that drifts is followed, a jump is not. */
    private const val TEMPO_SCAN = 0.5

    /** The furthest a path may leap without a start between: about a minute of beats — a breakdown, not a lost track. */
    private const val MAX_LEAPS = 96

    /** What one beat with no start between costs. A real start earns up to one, so the path prefers every beat it can
     *  hear and only leaps when the sound is genuinely absent — never because leaping was cheaper. */
    private const val SKIP_COST = 0.75

    /** What one step off the tempo costs, against a beat's own worth (1 for the strongest start in the piece). */
    private const val TIGHTNESS = 10.0

    /** Where a start must stand above the envelope's mean, as a share of the way to its loudest, to be a candidate. */
    private const val START_FLOOR = 0.25f

    /** The seconds whose own mean and loudest frame set the floor for a start: a loud chorus must not hide a quiet intro. */
    private const val START_LOCAL_SECONDS = 8.0

    /** The starts a piece needs before a path is chosen through them; fewer, and the old walk has the last word. */
    private const val MIN_STARTS = 8

    /** The analysis generation saved with a grid: a project from an older detector is analysed again (unless edited). */
    const val ANALYSIS_VERSION = 9

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
        val low = lowOnsets(samples, sampleRate)
        // Every band votes. The low drum is weighted a little higher (it is where the foot lands), but a piece without a
        // kick keeps the full spectrum, and the synth that used to pull the grid onto the offbeat cannot outvote both.
        val fused = fuse(onset, low)
        val fps = sampleRate.toDouble() / HOP
        val opening = tempoOf(fused, fps) ?: return null
        val lengthMs = samples.size * 1000L / sampleRate

        // A dance track has one pulse from its first bar to its last: estimate that single period and phase from the
        // whole piece, then draw every beat from them. A pause has no starts, so it neither changes the period nor votes
        // for another phase. This is tried first; the drifting path below is only for a piece that proves it moves.
        val steady = steadyGrid(fused, low, opening, fps, sampleRate, lengthMs)
        if (steady != null && steadySupport(fused, low, steady, fps) >= STEADY_AGREEMENT) return steady

        val started = starts(fused, opening.period, fps)
        val path = if (started.size >= MIN_STARTS) track(fused, started, opening.period, fps) else emptyList()
        // The path is chosen for its earned score, so it may begin where the piece is loudest — 51 s into one real track,
        // 84 s into another. The introduction is still part of the track: walk the same period back from the first beat to
        // the beginning, following the local period and taking a real start when one stands where the grid expects it.
        val whole = if (path.isNotEmpty()) backfilled(path, fused, opening.period, fps) else path
        // Too few starts to choose a path through (a wash of sound, a spoken piece): the walk still makes a grid.
        val beats = if (whole.isNotEmpty()) beatsOf(whole, sampleRate, lengthMs)
        else walk(fused, opening, sampleRate, lengthMs)
        if (beats.isEmpty()) return null
        // What the envelope itself is early or late by, taken out of the whole grid: the *middle* of what the beats stand
        // off their starts, so one beat on the wrong start cannot drag the rest of the grid along (a sum could).
        val tuned = aligned(beats, started, sampleRate)
        // The tempo is right, but the phase can sit on the synth or the offbeat. The kick/bass band says where the foot
        // lands; the whole grid moves by its median, never by more than half a beat.
        val phased = phaseToLow(tuned, low, sampleRate)
        val confidence = gridConfidence(opening.confidence, whole, phased)
        return Grid(60_000.0 / medianGap(phased), phased.first(), phased, confidence)
    }

    /** A pulse read from the whole piece: its period and the first start of its grid, both finer than a frame. */
    private class Pulse(val period: Double, val phase: Double)

    /**
     * Every band as one envelope: each is scaled to its own loudest start first, so a loud chorus or a soft intro cannot
     * decide which band matters. The low band gets 55 % because it is the drum's body, the full spectrum 45 % because a
     * track may have no drum at all.
     */
    private fun fuse(onset: FloatArray, low: FloatArray): FloatArray {
        val top = onset.maxOrNull() ?: 0f
        val lowTop = low.maxOrNull() ?: 0f
        if (low.isEmpty() || lowTop <= 0f) {
            return if (top > 0f) FloatArray(onset.size) { onset[it] / top } else onset
        }
        if (top <= 0f) return FloatArray(low.size) { low[it] / lowTop }
        val n = minOf(onset.size, low.size)
        return FloatArray(n) { 0.45f * onset[it] / top + 0.55f * low[it] / lowTop }
    }

    /**
     * The one period and phase of the whole piece, from a comb filter: the atomic pulse is compared with every beat of
     * a trial grid through a narrow Gaussian window, and the grid that collects the most start energy wins. This is the
     * local score of Ellis's beat tracker and Scheirer's resonator bank in one: unlike the raw Fourier coefficient, it
     * does not mistake the shape of a kick (or its ringing) for a different tempo, and a silent gap contributes zero.
     * The coarse autocorrelation gives the octave; the comb gives the fraction of a frame and the phase.
     */
    private fun steadyPulse(onset: FloatArray, opening: Tempo, fps: Double): Pulse? {
        val base = opening.period
        if (base <= 1.0 || onset.isEmpty()) return null
        // One frame is 23 ms; the kernel is about two frames wide, so a hit need not fall exactly on a sampled frame.
        val smooth = gaussian(onset, 1.0)
        var from = base * 0.98
        var to = base * 1.02
        var best = Pulse(base, 0.0)
        var bestScore = -1.0
        // Coarse to fine: the period is settled in milli-frames, the phase with it.
        repeat(3) {
            val step = (to - from) / 24
            if (step <= 0.0) return@repeat
            var at = from
            while (at <= to) {
                var phase = 0.0
                while (phase < at) {
                    var score = 0.0
                    var count = 0
                    var beat = phase
                    while (beat < smooth.size) {
                        score += sample(smooth, beat)
                        count++
                        beat += at
                    }
                    if (count > 0) {
                        val mean = score / count
                        if (mean > bestScore) {
                            bestScore = mean
                            best = Pulse(at, phase)
                        }
                    }
                    // A tenth of a frame is 2.3 ms: finer than the ear's placement of a click.
                    phase += (at / 96).coerceAtLeast(0.05)
                }
                at += step
            }
            val span = step * 2.0
            from = (best.period - span).coerceAtLeast(2.0)
            to = best.period + span
        }
        return best.takeIf { bestScore > 0.0 }
    }

    /** The envelope smoothed by a Gaussian of [sigma] frames: a hit is a small hill, not a one-frame spike. */
    private fun gaussian(raw: FloatArray, sigma: Double): DoubleArray {
        val radius = kotlin.math.ceil(sigma * 3.0).toInt().coerceAtLeast(1)
        val kernel = DoubleArray(radius * 2 + 1) { i ->
            val d = i - radius
            exp(-0.5 * d * d / (sigma * sigma))
        }
        val total = kernel.sum()
        return DoubleArray(raw.size) { i ->
            var sum = 0.0
            for (d in -radius..radius) {
                val j = i + d
                if (j in raw.indices) sum += raw[j] * kernel[d + radius]
            }
            sum / total
        }
    }

    /** An envelope at a moment between frames. */
    private fun sample(values: DoubleArray, at: Double): Double {
        if (values.isEmpty() || at < 0.0 || at > values.lastIndex) return 0.0
        val i = at.toInt()
        val j = (i + 1).coerceAtMost(values.lastIndex)
        return values[i] + (values[j] - values[i]) * (at - i)
    }

    /** The fixed grid [pulse] stands for, with one global phase shift onto the low drum. */
    private fun steadyGrid(
        onset: FloatArray,
        low: FloatArray,
        opening: Tempo,
        fps: Double,
        sampleRate: Int,
        lengthMs: Long,
    ): Grid? {
        val pulse = steadyPulse(onset, opening, fps) ?: return null
        val beats = ArrayList<Long>(1024)
        var at = pulse.phase
        while (frameMs(at, sampleRate) < lengthMs) {
            beats += frameMs(at, sampleRate)
            at += pulse.period
        }
        if (beats.size < 4) return null
        // The Fourier phase is the middle of the envelope's fundamental; the low drum's actual peak is the foot. One
        // median shift for the whole grid keeps every interval exactly as the model drew it.
        val phased = phaseToLow(beats, low, sampleRate)
        if (phased.size < 4) return null
        return Grid(60.0 * fps / pulse.period, phased.first(), phased, opening.confidence)
    }

    /**
     * How much the bands agree with one fixed grid, 0…1. The low drum has the stronger vote (70 %): the offbeat synth of
     * a real dance track is a perfectly steady pulse too, but it stands half a beat away and must not be able to veto the
     * kick. The full spectrum still contributes, and it is all there is for a track without a drum.
     */
    private fun steadySupport(fused: FloatArray, low: FloatArray, grid: Grid, fps: Double): Double {
        val broad = steadyAgreement(fused, grid, fps)
        val lowTop = low.maxOrNull() ?: 0f
        if (low.isEmpty() || lowTop <= 0f) return broad
        return (0.70 * steadyAgreement(low, grid, fps) + 0.30 * broad).coerceIn(0.0, 1.0)
    }

    /**
     * How much the starts of one envelope agree with a fixed grid, 0…1. Each start votes at its phase within the beat it
     * belongs to; a constant pulse puts all votes in one place, a tempo that drifts spreads them around the circle.
     * Coverage is folded in so a short loud passage that fits cannot speak for a whole track.
     */
    private fun steadyAgreement(onset: FloatArray, grid: Grid, fps: Double): Double {
        if (grid.beats.size < 4) return 0.0
        val periodMs = medianGap(grid.beats)
        if (periodMs <= 0.0) return 0.0
        val period = periodMs * fps / 1000.0
        if (period <= 1.0) return 0.0
        val started = starts(onset, period, fps)
        if (started.isEmpty()) return 0.0
        val base = grid.beats.first().toDouble() * fps / 1000.0
        var sumRe = 0.0
        var sumIm = 0.0
        var weight = 0.0
        val covered = BooleanArray(grid.beats.size)
        for (frame in started) {
            val beatsAway = ((frame - base) / period).roundToInt()
            val residual = frame - (base + beatsAway * period)
            if (abs(residual) > period * 0.5) continue
            val value = onset[frame.roundToInt().coerceIn(0, onset.lastIndex)].toDouble()
            if (value <= 0.0) continue
            val angle = 2.0 * PI * residual / period
            sumRe += value * cos(angle)
            sumIm += value * sin(angle)
            weight += value
            if (abs(residual) <= period * STEADY_SHARE && beatsAway in covered.indices) {
                covered[beatsAway] = true
            }
        }
        if (weight <= 0.0) return 0.0
        val agreement = sqrt(sumRe * sumRe + sumIm * sumIm) / weight
        val coverage = covered.count { it }.toDouble() / covered.size
        return (agreement * (0.5 + 0.5 * coverage)).coerceIn(0.0, 1.0)
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
    private fun starts(onset: FloatArray, period: Double, fps: Double): List<Double> {
        if (onset.size < 3) return emptyList()
        val top = onset.max().toDouble()
        if (top <= 0.0) return emptyList()
        // The floor is local: eight seconds of the piece set their own mean and their own loudest start. The old global
        // fraction turned one loud chorus into a gate the quiet intro could never pass, and the beat grid began there.
        val cell = (2.0 * fps).roundToInt().coerceAtLeast(1)
        val cells = (onset.size + cell - 1) / cell
        val tops = FloatArray(cells)
        for (i in onset.indices) {
            val c = i / cell
            if (onset[i] > tops[c]) tops[c] = onset[i]
        }
        val prefix = DoubleArray(onset.size + 1)
        for (i in onset.indices) prefix[i + 1] = prefix[i] + onset[i]
        val span = (START_LOCAL_SECONDS * fps / 2).roundToInt().coerceAtLeast(cell)
        // A third of a beat: the envelope rings for a frame or two, and that is one start, not two.
        val apart = (period * 0.3).coerceAtLeast(2.0)
        val out = ArrayList<Double>(onset.size / 4 + 1)
        for (i in 1 until onset.size - 1) {
            val v = onset[i]
            val from = (i - span).coerceAtLeast(0)
            val to = (i + span).coerceAtMost(onset.size - 1)
            val mean = (prefix[to + 1] - prefix[from]) / (to - from + 1)
            val firstCell = from / cell
            val lastCell = to / cell
            var localTop = 0f
            for (c in firstCell..lastCell) if (tops[c] > localTop) localTop = tops[c]
            if (localTop <= mean || localTop <= 0f) continue
            val floor = mean + (localTop - mean) * START_FLOOR
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
    private fun track(onset: FloatArray, started: List<Double>, period: Double, fps: Double): List<Double> {
        val n = started.size
        if (n < 2) return emptyList()
        val worth = DoubleArray(n) { i -> onset[started[i].roundToInt().coerceIn(0, onset.lastIndex)].toDouble() }
        val top = worth.max()
        if (top <= 0.0) return emptyList()
        // The four seconds around each start have their own period; a step is judged against that, so a live take or a
        // band that speeds up is followed instead of being pulled back to the opening tempo all the way through.
        val local = DoubleArray(n) { i -> localPeriod(onset, started[i], period, fps) }
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (j in 0 until n) {
            // A beat earns what its start is worth, and may be the first of the path (a piece does not always open on a
            // beat it can hear): the step before it adds what the path up to there had earned, less its own lean.
            var along = 0.0
            var from = -1
            // A gap may hold several beats with no start in it (a breakdown): the path leaps over it as a whole number
            // of beats, so the pulse and phase continue instead of a new grid beginning after the pause. Every skipped
            // beat costs something, so a real start between the two is always preferred when the sound has one.
            var i = j - 1
            while (i >= 0) {
                val step = started[j] - started[i]
                val expected = ((local[i] + local[j]) / 2).coerceAtLeast(1.0)
                if (step > expected * MAX_LEAPS) break
                val leaps = (step / expected).roundToInt().coerceAtLeast(1)
                val per = step / leaps
                val lean = ln(per / expected)
                if (abs(lean) <= ln(1.0 + TEMPO_SCAN)) {
                    val earned = score[i] - TIGHTNESS * lean * lean * leaps - SKIP_COST * (leaps - 1)
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
     * The path walked back from its first beat to the beginning. The DP chooses where a path *earns* most, not where the
     * piece begins; a first beat 51 s in is a correct maximum and a useless grid. The opening period is followed back
     * through the local tempo, and the grid takes a real start when one stands within an eighth of a beat of where it
     * expects it. Silence stays on the grid rather than inventing notes.
     */
    private fun backfilled(path: List<Double>, onset: FloatArray, period: Double, fps: Double): List<Double> {
        if (path.isEmpty()) return path
        val gaps = path.zipWithNext { a, b -> b - a }.sorted()
        var step = if (gaps.isEmpty()) period else gaps[gaps.size / 2]
        if (step <= 1.0) step = period
        val window = (step * 0.12).roundToInt().coerceAtLeast(1)
        val before = ArrayList<Double>()
        var predicted = path.first()
        // The period the path itself was walked at: the introduction may bend a little around it, never halve it. A
        // per-step limit alone still accumulates (8 % a beat reaches half the period in seven beats), which turned a
        // 126 BPM track into a 252 BPM grid.
        val anchor = step
        while (predicted - step >= 0.0) {
            val expected = predicted - step
            // A start near the expected moment is the truth; otherwise the grid itself is the best statement we have.
            val snapped = snapExact(onset, expected, window)
            val at = if (abs(snapped - expected) <= window + 0.5) snapped else expected
            before += at
            val local = localPeriod(onset, at, step, fps)
            step = local.coerceIn(anchor * 0.85, anchor * 1.15)
            predicted = at
        }
        before.reverse()
        return before + path
    }

    /**
     * How much of the grid is backed by a path through real starts, folded into the tempo's own certainty: a path that
     * skipped most beats, or a piece with no repeats, must not look as sure as a metronome. The UI can then say «check
     * the grid» instead of presenting a guess as fact.
     */
    private fun gridConfidence(tempo: Double, path: List<Double>, beats: List<Long>): Double {
        if (path.size < 2 || beats.size < 2) return 0.0
        val gaps = path.zipWithNext { a, b -> b - a }.sorted()
        val step = gaps[gaps.size / 2].coerceAtLeast(1.0)
        val expected = ((path.last() - path.first()) / step + 1.0).coerceAtLeast(1.0)
        val covered = (path.size / expected).coerceIn(0.0, 1.0)
        return (tempo * (0.65 + 0.35 * covered)).coerceIn(0.0, 1.0)
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

    /** The profile of [samples] with the [grid] found for them: stability, drive, the energy curve and the drops. [beats]
     *  is the grid over the whole track — the detector's own may end where its path earned most, and a drop after that
     *  must still land on a beat, not on the last one of a short grid. */
    fun profile(
        samples: FloatArray,
        sampleRate: Int,
        grid: Grid?,
        beats: List<Long> = grid?.beats.orEmpty(),
    ): Profile? {
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
        val gaps = beats.zipWithNext { a, b -> b - a }
        val median = medianGap(beats)
        val steady = if (gaps.isEmpty() || median <= 0.0) 0.0
        else gaps.count { abs(it - median) <= median * 0.02 }.toDouble() / gaps.size
        return Profile(
            bpm = if (median > 0.0) 60_000.0 / median else 0.0,
            stability = steady,
            drive = energy.average().coerceIn(0.0, 1.0),
            energy = energy,
            drops = dropsOf(loudness(samples, sampleRate), LOUD_STEP_MS, beats),
            confidence = grid?.confidence ?: 0.0,
        )
    }

    /**
     * Every place the track's loud part returns after a quiet passage (the plan's «дропы»), each on the first beat that
     * opens it. The search starts from the pauses, not from the loudest leap: every quiet stretch of a second and a half
     * within six seconds of a rise is a candidate, and the marker stands where the loudness is still climbing back to its
     * own level. A leap inside an already loud part has no pause behind it, so it is not a drop — that is what put the
     * second marker before a pause instead of after it. Every pause is looked at on its own, so a later, gentler return is
     * found too (the old single «strongest step» lost all of them once one leap was found).
     *
     * Everything is asked in the track's *own* terms — above the piece's mean, well above the quiet it left, and loud for
     * seconds after — never «half as loud as the loudest moment»: the loudest moment of a track with drums is one drum,
     * and a drop never reaches half of it.
     */
    internal fun dropsOf(energy: FloatArray, stepMs: Long = ENERGY_STEP_MS, beats: List<Long> = emptyList()): List<Long> {
        if (stepMs <= 0L) return emptyList()
        // The curve is averaged over a beat where the grid says what a beat is: the loudness of a drum track jumps from a
        // hit to near silence every few tenths of a second, and drops read on those jumps were found wherever two windows
        // happened to hold a different number of hits.
        val beat = medianGap(beats)
        val span = if (beat > 0.0) (beat / stepMs).roundToInt().coerceIn(1, 40) else 1
        val loud = averaged(energy, span)
        val near = (STEP_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(1)
        val far = (LOUD_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(near + 1)
        val quiet = (QUIET_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(1)
        val lookBack = (QUIET_LOOK_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(quiet + near)
        val apart = (DROP_APART_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(far)
        if (loud.size < quiet + far) return emptyList()
        val mean = loud.average()
        if (mean <= 0.0) return emptyList()
        val found = ArrayList<DropPoint>()
        for (t in quiet until loud.size - far) {
            // The level the track is returning to: the four seconds that follow, so a later quiet stretch cannot lower it.
            val level = mean(loud, t, t + far)
            if (level < mean * AVERAGE_SHARE) continue
            // The return itself, read over half a second (one drum hit inside a quiet passage is not a return), and it must still
            // be a climb: a plateau after an earlier drop is not another drop. The exact edge is the first moment in that
            // window where the loudness reaches the returning level — not the window's start, which stands a few tenths early.
            val after = mean(loud, t, t + near)
            if (after < level * RETURN_SHARE) continue
            val edge = (t..minOf(t + near, loud.lastIndex)).firstOrNull { loud[it] >= level * RETURN_SHARE } ?: t
            val before = mean(loud, edge - near, edge)
            if (loud[edge] < before * RISE_SHARE) continue
            // The pause behind it: a second and a half somewhere in the six before the rise, well under the return level.
            // It may lie before a riser, which is why the whole span is searched rather than only the half second before.
            var quietest = Double.MAX_VALUE
            var quietEnd = 0
            var q = (edge - lookBack).coerceAtLeast(0)
            while (q + quiet <= edge) {
                val was = mean(loud, q, q + quiet)
                if (was < quietest) {
                    quietest = was
                    quietEnd = q + quiet
                }
                q += (quiet / 4).coerceAtLeast(1)
            }
            if (quietest > level * QUIET_SHARE) continue
            // One pause is one drop: a rise seen over a few steps belongs to the same return, and eight seconds also put
            // the previous pause out of the look-back before another rise can count.
            if (found.isNotEmpty() && edge - found.last().edge < apart) continue
            found += DropPoint(edge, quietest, quietEnd, level)
        }
        if (found.isNotEmpty()) {
            return found.map { dropBeat(it, energy, stepMs, beats) }.distinct()
        }
        // A piece that opens loud or builds smoothly has no pause at all: one broad arrival stands in for the drops.
        return listOfNotNull(loudStart(loud, far)?.let { onBeat(it * stepMs, beats) })
    }

    /** The first drop, for the callers that only need one (the UI offers all of them through [dropsOf]). */
    internal fun dropOf(energy: FloatArray, stepMs: Long = ENERGY_STEP_MS, beats: List<Long> = emptyList()): Long? =
        dropsOf(energy, stepMs, beats).firstOrNull()

    /** [values] averaged over [span] places (centred, so the moment of a step is not moved by the averaging). */
    private fun averaged(values: FloatArray, span: Int): FloatArray {
        if (span <= 1) return values
        val half = span / 2
        return FloatArray(values.size) { i -> mean(values, i - half, i + half + 1).toFloat() }
    }

    /**
     * The fallback for a piece that has no step in it (it opens loud, or builds without a leap): where the loudness first
     * reaches most of the way to its loudest *sustained* stretch and stays there, taken forward — never back into a build.
     * Null when the piece is flat: a drop that is not in the sound cannot be guessed at.
     */
    private fun loudStart(energy: FloatArray, far: Int): Int? {
        val holds = FloatArray(energy.size - far + 1) { i -> mean(energy, i, i + far).toFloat() }
        if (holds.isEmpty()) return null
        val top = holds.max()
        if (top <= 0f) return null
        val mean = holds.average()
        if (top < mean * FLAT_SHARE) return null
        val level = top * REACHED_SHARE
        return holds.indexOfFirst { it >= level }.takeIf { it >= 0 }
    }

    /**
     * The beat a drop opens on. The returning level is reached a little after the first beat out of the pause (33.2 s on
     * one real track, the level at 33.4), so the beat *before* the detected edge is checked too: if it has already left
     * the quiet, it is the first beat of the return. The previous beat of a leap inside an already loud part is still loud
     * before the leap, so that one stays on the following beat.
     */
    private fun dropBeat(point: DropPoint, energy: FloatArray, stepMs: Long, beats: List<Long>): Long =
        beatOfReturn(point.edge * stepMs, point.quietEnd * stepMs, point.level, energy, stepMs, beats)

    /**
     * The beat a return opens on, from the detected [edgeMs] (where the returning level is reached), the end of the pause
     * and the level. The return can reach its level one beat after the sound came back, so the beat before [edgeMs] is
     * checked: when the pause ends at that beat and it is already well out of the quiet, it is the first beat of the return.
     */
    internal fun beatOfReturn(
        edgeMs: Long,
        quietEndMs: Long,
        level: Double,
        energy: FloatArray,
        stepMs: Long,
        beats: List<Long>,
    ): Long {
        if (beats.isEmpty()) return edgeMs
        val next = beats.firstOrNull { it >= edgeMs } ?: return beats.lastOrNull() ?: edgeMs
        val prev = beats.lastOrNull { it < edgeMs } ?: return next
        val period = medianGap(beats)
        if (period > 0.0 && edgeMs - prev > period * 1.5) return next
        // The pause must have ended right at that beat: a beat deep inside a riser is not the first beat of the return, and a
        // beat before a still-silent passage is not either.
        if (abs(quietEndMs - prev) > period * 0.75) return next
        val at = (prev / stepMs).toInt().coerceIn(0, energy.lastIndex)
        // A single drum hit in a quiet passage is not a return: listen across a couple of steps, not one sample.
        val around = mean(energy, at - 1, at + 2)
        // The quiet is read from the raw curve again: the beat-long average carries the first hit back into it and would
        // make a real return look too weak to count.
        val quietSteps = (QUIET_SECONDS * 1000 / stepMs).toInt().coerceAtLeast(1)
        val rawQuiet = mean(energy, (quietEndMs / stepMs).toInt() - quietSteps, (quietEndMs / stepMs).toInt())
        val leftTheQuiet = rawQuiet + (level - rawQuiet) * RETURN_EDGE_SHARE
        return if (around >= leftTheQuiet) prev else next
    }

    /**
     * [ms] put on the grid: the first beat at or after the step. The step's own edge is seen only once its half-second
     * window has filled, so the window can lag the sound; taking the beat *before* it put the marker on the last loud beat
     * of the quiet passage. The first beat of the returning loud part is what the ear hears as the drop.
     */
    private fun onBeat(ms: Long, beats: List<Long>): Long {
        if (beats.isEmpty()) return ms
        return beats.firstOrNull { it >= ms } ?: beats.lastOrNull() ?: ms
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

    /**
     * How strongly the kick/bass band (30–160 Hz) starts in each frame: the same spectral rise, measured only where a
     * drum's body is. The full spectrum can favour a bright synth or a hi-hat; this envelope says where the foot lands.
     */
    internal fun lowOnsets(samples: FloatArray, sampleRate: Int): FloatArray {
        val frames = if (samples.size < WINDOW) 0 else (samples.size - WINDOW) / HOP + 1
        if (frames <= 0) return FloatArray(0)
        val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
        val re = DoubleArray(WINDOW)
        val im = DoubleArray(WINDOW)
        val from = (LOW_FROM_HZ.toDouble() * WINDOW / sampleRate).roundToInt().coerceIn(1, WINDOW / 2 - 1)
        val to = (LOW_TO_HZ.toDouble() * WINDOW / sampleRate).roundToInt().coerceIn(from + 1, WINDOW / 2 - 1)
        var prev = 0.0
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val at = f * HOP
            for (i in 0 until WINDOW) { re[i] = samples[at + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            // Energy, not a per-bin flux: a bass note changes many bins without beginning, while a drum's body lifts the
            // whole low band at once. The rise of that energy is the hit.
            var energy = 0.0
            for (b in from..to) energy += re[b] * re[b] + im[b] * im[b]
            energy = sqrt(energy)
            flux[f] = if (f == 0) 0f else (energy - prev).coerceAtLeast(0.0).toFloat()
            prev = energy
        }
        val half = 8
        return FloatArray(frames) { f ->
            var s = 0.0
            var n = 0
            for (j in maxOf(0, f - half)..minOf(frames - 1, f + half)) { s += flux[j]; n++ }
            (flux[f] - s / n).toFloat().coerceAtLeast(0f)
        }
    }

    /**
     * The grid [beats] moved onto the low drum: for every beat the strongest low onset within [LOW_PHASE_SHARE] of a beat
     * is found, and the median of those offsets moves the whole grid at once. A median, not a mean: a missed kick or a
     * second bass note must not drag the beat. Fewer than a quarter of the beats with a low hit leaves the grid as it is.
     */
    internal fun phaseToLow(beats: List<Long>, low: FloatArray, sampleRate: Int): List<Long> {
        if (beats.size < 4 || low.isEmpty()) return beats
        val period = medianGap(beats)
        if (period <= 0.0) return beats
        val framePeriod = period * sampleRate / (HOP * 1000.0)
        val span = (framePeriod * LOW_PHASE_SHARE).roundToInt().coerceIn(1, low.size / 2)
        val centres = IntArray(beats.size) { frameOf(beats[it], sampleRate).coerceIn(0, low.lastIndex) }
        // Real peaks of the low drum, not the broad energy between them: a sum of the envelope picked a phase halfway
        // between two hits. Each beat votes for its nearest peak with that peak's strength, and the winning offset moves
        // the whole grid.
        val top = low.maxOrNull() ?: 0f
        if (top <= 0f) return beats
        val peaks = ArrayList<Int>()
        for (f in 1 until low.lastIndex) {
            if (low[f] < top * 0.08f) continue
            if (low[f] >= low[f - 1] && low[f] >= low[f + 1]) peaks += f
        }
        if (peaks.size < 4) return beats
        val weight = DoubleArray(span * 2 + 1)
        for (centre in centres) {
            var nearest = -1
            var distance = Int.MAX_VALUE
            for (p in peaks) {
                val d = abs(p - centre)
                if (d < distance) {
                    distance = d
                    nearest = p
                }
            }
            if (nearest < 0 || distance > span) continue
            val at = (nearest - centre + span).coerceIn(0, weight.lastIndex)
            weight[at] += low[nearest].toDouble()
        }
        var bestShift = 0
        var bestScore = 0.0
        for (i in weight.indices) {
            if (weight[i] > bestScore) {
                bestScore = weight[i]
                bestShift = i - span
            }
        }
        if (bestScore <= 0.0 || bestShift == 0) return beats
        val shiftMs = (bestShift * HOP * 1000.0 / sampleRate).roundToInt()
        return beats.map { it + shiftMs }.filter { it >= 0L }
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
