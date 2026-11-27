package app.goultra.transfer.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

enum class AudioAnalysisMode { AUTO, STRUCTURE, RHYTHM, DYNAMICS, SPEECH }

/** Montage accents are source timestamps. Only the rhythm mode needs a reliable beat to snap to. */
internal object AudioAccents {
    const val VERSION = 17
    private const val LEVEL_MS = 50L
    private const val FRAME_MS = 500L
    private const val FFT_SIZE = 2048
    private val bands = doubleArrayOf(100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0)
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1)) }

    data class Result(val mode: AudioAnalysisMode, val drops: List<Long>)
    private data class Accent(val at: Long, val score: Double)

    fun analyse(
        samples: FloatArray,
        rate: Int,
        grid: BeatGrid.Grid?,
        requested: AudioAnalysisMode,
    ): Result {
        val length = samples.size * 1000L / rate.coerceAtLeast(1)
        val loud = BeatGrid.loudness(samples, rate, LEVEL_MS)
        val beats = grid?.beats.orEmpty()
        val gaps = beats.zipWithNext { a, b -> b - a }
        val period = BeatGrid.medianGap(beats)
        val steady = period > 0 && gaps.count { abs(it - period) <= period * 0.06 } >= gaps.size * 0.72
        val pulse = beats.size >= 16 && grid != null && grid.confidence >= 0.5 &&
            beats.last() - beats.first() >= length * 0.65 && steady
        val pauses = if (requested == AudioAnalysisMode.AUTO || requested == AudioAnalysisMode.SPEECH) phrases(loud) else emptyList()
        val sections = if (requested in listOf(AudioAnalysisMode.AUTO, AudioAnalysisMode.STRUCTURE, AudioAnalysisMode.RHYTHM)) {
            boundaries(spectralFrames(samples, rate), loud)
        } else emptyList()
        val mode = if (requested != AudioAnalysisMode.AUTO) requested else when {
            pulse -> AudioAnalysisMode.RHYTHM
            pauses.count { it.score in 0.35..1.4 } >= maxOf(5, (length / 9_000L).toInt()) &&
                sections.count { it.score >= 0.24 } <= 1 -> AudioAnalysisMode.SPEECH
            sections.isNotEmpty() -> AudioAnalysisMode.STRUCTURE
            else -> AudioAnalysisMode.DYNAMICS
        }
        val energy by lazy { rises(loud) }
        val raw = when (mode) {
            AudioAnalysisMode.STRUCTURE -> sections
            AudioAnalysisMode.RHYTHM -> sections + energy.map { it.copy(score = it.score * 0.25) }
            AudioAnalysisMode.DYNAMICS -> energy
            AudioAnalysisMode.SPEECH -> pauses
            AudioAnalysisMode.AUTO -> error("Automatic mode must resolve to a detector")
        }
        val chosen = mutableListOf<Accent>()
        val spacing = if (mode == AudioAnalysisMode.SPEECH) 4_000L else 8_000L
        val budget = if (mode == AudioAnalysisMode.SPEECH) maxOf(1, (length / 7_000L).toInt())
            else maxOf(1, (length / 24_000L).toInt())
        val ranked = raw.sortedByDescending { it.score }
        // Give clearly detected changes in each third a chance before filling the remaining slots by strength.
        val representatives = if (length >= 90_000L && budget >= 3 && mode != AudioAnalysisMode.SPEECH) {
            (0L..2L).mapNotNull { third ->
                ranked.firstOrNull { it.at * 3 / length == third }
            }.sortedByDescending { it.score }
        } else emptyList()
        for (candidate in representatives + ranked) {
            val at = if (mode == AudioAnalysisMode.RHYTHM && beats.isNotEmpty()) {
                val i = beats.binarySearch(candidate.at)
                val nearest = if (i >= 0) beats[i] else {
                    val right = -i - 1
                    listOfNotNull(beats.getOrNull(right - 1), beats.getOrNull(right)).minByOrNull { abs(it - candidate.at) }
                }
                nearest?.takeIf { abs(it - candidate.at) <= 350L } ?: candidate.at
            } else candidate.at
            if (at in 1 until length && chosen.none { abs(it.at - at) < spacing }) {
                chosen += candidate.copy(at = at)
                if (chosen.size >= budget) break
            }
        }
        return Result(mode, chosen.map { it.at }.sorted())
    }

    private fun rises(loud: FloatArray): List<Accent> {
        if (loud.size < 60) return emptyList()
        val levels = loud.sorted()
        val reference = levels[levels.size * 9 / 10].toDouble()
        if (reference <= 0.002) return emptyList()
        val sum = DoubleArray(loud.size + 1)
        for (i in loud.indices) sum[i + 1] = sum[i] + loud[i]
        fun mean(from: Int, to: Int): Double = (sum[to] - sum[from]) / (to - from)
        val score = DoubleArray(loud.size)
        for (i in 32 until loud.size - 30) {
            val before = mean(i - 28, i - 3)
            val after = mean(i + 3, i + 28)
            val jump = mean(i, i + 5) - mean(i - 5, i)
            val baseline = maxOf(before, reference * 0.12)
            if (after >= reference * 0.24 && after - before >= reference * 0.065 &&
                after >= baseline * 1.21 && jump >= reference * 0.035
            ) {
                // Local contrast finds strong entries in quieter sections. The short edge places the cut.
                score[i] = ln(after / baseline) + 0.85 * jump / reference
            }
        }
        val local = (32 until loud.size - 30).mapNotNull { i ->
            if (score[i] <= 0.0 || score[i] < score[i - 1] || score[i] <= score[i + 1]) null
            else Accent(i * LEVEL_MS, score[i])
        }
        val returns = BeatGrid.dropsOf(loud, LEVEL_MS).map { at ->
            val index = (at / LEVEL_MS).toInt()
            val edges = maxOf(2, index - 9)..minOf(loud.size - 2, index + 4)
            val attack = edges.maxByOrNull { mean(it, it + 2) - mean(it - 2, it) }
            if (attack == null) Accent(at, 0.0) else {
                val rise = (mean(attack, attack + 2) - mean(attack - 2, attack)) / reference
                if (rise >= 0.03) Accent(attack * LEVEL_MS, rise) else Accent(at, 0.0)
            }
        }
        // A pause-and-return and an abrupt change can find different events. Keep the strongest return and put its
        // marker on the local attack, since the sustained level that found it can be reached after the attack itself.
        if (local.isEmpty()) return returns
        val strongest = local.maxOf { it.score }
        val bestReturn = returns.maxByOrNull { it.score }?.takeIf { it.score >= 0.03 }
        return local.filter { it.score >= maxOf(0.27, strongest * 0.36) } +
            listOfNotNull(bestReturn?.copy(score = strongest * 1.01))
    }

    private fun phrases(loud: FloatArray): List<Accent> {
        val audible = loud.filter { it > 0.001f }.sorted()
        if (audible.isEmpty()) return emptyList()
        val floor = maxOf(0.002f, audible[audible.size * 3 / 4] * 0.16f)
        val out = mutableListOf<Accent>()
        var quiet = 0
        for (i in loud.indices) {
            if (loud[i] <= floor) {
                quiet++
                continue
            }
            if (quiet >= 6 && i > quiet && i + 8 <= loud.size && loud.sliceArray(i until i + 8).count { it > floor } >= 5) {
                out += Accent(i * LEVEL_MS, quiet * LEVEL_MS / 1000.0)
            }
            quiet = 0
        }
        return out
    }

    private fun spectralFrames(samples: FloatArray, rate: Int): List<DoubleArray> {
        if (rate <= 0) return emptyList()
        val hop = (rate * FRAME_MS / 1000L).toInt().coerceAtLeast(1)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val spectrum = DoubleArray(FFT_SIZE / 2)
        val pitch = IntArray(FFT_SIZE / 2) { k ->
            val hz = k.toDouble() * rate / FFT_SIZE
            if (hz in 80.0..4000.0) Math.floorMod((69 + 12 * log2(hz / 440)).roundToInt(), 12) else -1
        }
        val band = IntArray(FFT_SIZE / 2) { k -> bands.count { it <= k.toDouble() * rate / FFT_SIZE } }
        return (0 until samples.size step hop).map { start ->
            val frame = DoubleArray(20)
            for (offset in 1..3) {
                val center = start + hop * offset / 4
                for (i in re.indices) re[i] = (samples.getOrElse(center - FFT_SIZE / 2 + i) { 0f } * window[i])
                im.fill(0.0)
                BeatGrid.fft(re, im)
                for (k in spectrum.indices) spectrum[k] = re[k] * re[k] + im[k] * im[k]
                val powerScale = spectrum.sum()
                if (powerScale <= 1e-12) continue
                for (k in 2 until spectrum.lastIndex) {
                    val power = spectrum[k]
                    val relative = ln(1 + 128 * power / powerScale)
                    frame[band[k]] += relative
                    if (pitch[k] >= 0 && power > spectrum[k - 1] && power >= spectrum[k + 1]) {
                        frame[8 + pitch[k]] += relative
                    }
                }
            }
            normalize(frame, 0, 8)
            normalize(frame, 8, 20)
            frame
        }
    }

    private fun normalize(values: DoubleArray, from: Int, to: Int) {
        var sum = 0.0
        for (i in from until to) sum += values[i]
        if (sum > 0.0) for (i in from until to) values[i] /= sum
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var diff = 0.0
        for (i in a.indices) diff += abs(a[i] - b[i]) * if (i < 8) 0.25 else 0.30
        return diff
    }

    private fun boundaries(frames: List<DoubleArray>, loud: FloatArray): List<Accent> {
        val span = 6
        if (frames.size < 2 * span + 3) return emptyList()
        fun mean(from: Int): DoubleArray = DoubleArray(20) { band ->
            (from until from + span).sumOf { frames[it][band] } / span
        }
        val means = (0..frames.size - span).map(::mean)
        val score = DoubleArray(means.size) { i -> if (i < span) 0.0 else distance(means[i - span], means[i]) }
        val region = 60
        val thresholds = (0 until score.size step region).map { from ->
            val local = score.sliceArray(maxOf(span, from - region / 2) until minOf(score.size, from + region * 3 / 2)).sorted()
            val median = local[local.size / 2]
            val upper = local[local.size * 9 / 10]
            maxOf(0.045, median + (upper - median) * 0.85)
        }
        return (span + 1 until score.lastIndex).mapNotNull { i ->
            val threshold = thresholds[i / region]
            val start = i * (FRAME_MS / LEVEL_MS).toInt()
            val after = minOf(loud.size, start + span * (FRAME_MS / LEVEL_MS).toInt())
            if (score[i] < threshold || score[i] <= score[i - 1] || score[i] < score[i + 1] ||
                (start until after).count { loud[it] > 0.035f } < (after - start) / 2 ||
                means[i].all { it == 0.0 }) return@mapNotNull null
            // A similar phrase elsewhere adds weight when its entry also changes the preceding section.
            // Check a fixed number of strong transitions so long recordings stay cheap.
            Accent(i * FRAME_MS, 0.1 * score[i] / threshold)
        }.sortedByDescending { it.score }.take(40).map { accent ->
            val i = (accent.at / FRAME_MS).toInt()
            var repeat = 0.0
            for (j in span until score.size step 2) {
                if (abs(i - j) < 20 || score[j] < thresholds[j / region] * 0.85) continue
                val after = distance(means[i], means[j])
                if (after < 0.16) repeat = maxOf(repeat, (0.16 - after) * 0.5)
            }
            accent.copy(score = accent.score + repeat)
        }
    }
}
