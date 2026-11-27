package app.goultra.transfer.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.log2

/** Spectral landmark pairs tolerate differing levels and room sound better than comparing the raw waveforms. */
internal object MulticamSync {
    private const val RATE = 8000
    private const val FFT = 512
    private const val HOP = 128
    private const val FRAME_MS = 16L
    private val window = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * Math.PI * it / (FFT - 1)) }
    private val bands = intArrayOf(7, 16, 32, 56, 100, 180)

    data class Landmark(val hash: Int, val frame: Int)
    data class Match(val offsetMs: Long, val driftMs: Long, val votes: Int)
    data class Recording @JvmOverloads constructor(
        val landmarks: List<Landmark>,
        val trace: List<FloatArray>,
        val peaks: FloatArray,
        val harmonics: List<FloatArray> = emptyList(),
    ) {
        fun index(): RecordingIndex = RecordingIndex(this)
    }

    class RecordingIndex(recording: Recording) {
        private val exact = Index(recording.landmarks)
        private val trace = recording.trace
        private val harmonics = recording.harmonics

        fun match(candidate: Recording): Match? {
            val landmarks = exact.match(candidate.landmarks)
            if (landmarks != null && abs(landmarks.driftMs) <= 40L) return landmarks
            val attacks = matchTrace(trace, candidate.trace)
            if (attacks != null) return attacks
            val harmony = matchTrace(harmonics, candidate.harmonics, harmonic = true)
            if (harmony == null) return landmarks
            return matchTrace(trace, candidate.trace, around = harmony.offsetMs) ?: harmony
        }

        internal data class Arrangement(val offsetMs: Long, val parts: List<MulticamSpan>)

        /** Missing evidence can be room noise. Split only when verified passages require different offsets. */
        internal fun partialAlignment(candidate: Recording, checkCancelled: () -> Unit): Arrangement? {
            val size = maxOf(candidate.trace.size, candidate.harmonics.size)
            val width = 96
            val edgeWidth = 64
            if (size < width * 2) return null
            val stride = maxOf(width, (size + 59) / 60)
            val probes = ((0..size - width step stride).map { it to (it + width) } +
                listOf(0 to edgeWidth, edgeWidth to edgeWidth * 2, size - width to size))
                .distinct().sortedBy { it.first }
            val marks = candidate.landmarks.sortedBy { it.frame }
            val runs = mutableListOf<MutableList<MulticamSpan>>()
            val reliableEdges = mutableSetOf<MulticamSpan>()
            fun locate(ref: List<FloatArray>, source: List<FloatArray>, from: Int, to: Int, harmonic: Boolean): Long? {
                if (to > source.size) return null
                return matchTrace(ref, source.subList(from, to), harmonic, strict = to - from == edgeWidth)
                    ?.offsetMs?.minus(from * 2 * FRAME_MS)
            }
            for ((from, to) in probes) {
                checkCancelled()
                val key = from * 2
                var index = marks.binarySearchBy(key) { it.frame }.let { if (it < 0) -it - 1 else it }
                while (index > 0 && marks[index - 1].frame == key) index--
                val shortProbe = to - from == edgeWidth
                val offset = exact.match(marks.subList(index, marks.size).takeWhile { it.frame < to * 2 })
                    ?.takeIf { abs(it.driftMs) <= 40L && (!shortProbe || it.votes >= 32) }?.offsetMs
                    ?: locate(trace, candidate.trace, from, to, harmonic = false)
                    ?: locate(harmonics, candidate.harmonics, from, to, harmonic = true)
                    ?: continue
                val hit = MulticamSpan(from * 2 * FRAME_MS, to * 2 * FRAME_MS, offset)
                if (shortProbe) reliableEdges += hit
                val run = runs.lastOrNull()?.takeIf { abs(it.first().offsetMs - offset) <= 112L }
                    ?: mutableListOf<MulticamSpan>().also { runs += it }
                run += hit
            }
            val stable = runs.filter { windows ->
                windows.last().fromMs >= windows.first().toMs || windows.any { it in reliableEdges }
            }.map { windows ->
                val offsets = windows.map { it.offsetMs }.sorted()
                MulticamSpan(windows.first().fromMs, windows.maxOf { it.toMs }, offsets[offsets.size / 2]) to windows.size
            }
            val strongest = stable.maxByOrNull { it.second }?.first ?: return null
            val parts = mutableListOf<MulticamSpan>()
            for ((next, _) in stable) {
                val last = parts.lastOrNull()
                if (last == null) {
                    parts += next
                } else if (abs(last.offsetMs - next.offsetMs) <= 250L) {
                    parts[parts.lastIndex] = last.copy(toMs = maxOf(last.toMs, next.toMs))
                } else {
                    val join = if (next.offsetMs > last.offsetMs && abs(next.fromMs - last.toMs) <= width * 2 * FRAME_MS) {
                        refineJoin(trace, candidate.trace, last, next, 0.34)
                            ?: refineJoin(harmonics, candidate.harmonics, last, next, 0.56)
                    } else null
                    if (join != null) {
                        parts[parts.lastIndex] = last.copy(toMs = join)
                        parts += next.copy(fromMs = join)
                        continue
                    }
                    val overlap = maxOf(last.toMs - next.fromMs, last.toMs + last.offsetMs - next.fromMs - next.offsetMs, 0L)
                    val left = last.copy(toMs = last.toMs - overlap / 2)
                    val right = next.copy(fromMs = next.fromMs + (overlap + 1) / 2)
                    if (left.toMs - left.fromMs < 1_000L || right.toMs - right.fromMs < 1_000L) continue
                    parts[parts.lastIndex] = left
                    parts += right
                }
            }
            return if (parts.size < 2) null else Arrangement(strongest.offsetMs, parts)
        }

        private fun refineJoin(reference: List<FloatArray>, source: List<FloatArray>, left: MulticamSpan,
                               right: MulticamSpan, minimum: Double): Long? {
            val frameMs = 2 * FRAME_MS
            val support = 48
            val old = (left.offsetMs.toDouble() / frameMs).roundToInt()
            val new = (right.offsetMs.toDouble() / frameMs).roundToInt()
            val first = maxOf((left.fromMs / frameMs).toInt() + support,
                (minOf(left.toMs, right.fromMs) / frameMs).toInt() - support)
            val last = minOf((right.toMs / frameMs).toInt() - support,
                (maxOf(left.toMs, right.fromMs) / frameMs).toInt() + support)
            fun score(start: Int, shift: Int): Double {
                if (start + shift < 0 || start + shift + support > reference.size || start + support > source.size) return 0.0
                var dot = 0.0
                var aSum = 0.0
                var bSum = 0.0
                for (i in start until start + support) {
                    val a = source[i]
                    val b = reference[i + shift]
                    for (k in a.indices) {
                        dot += a[k] * b[k]
                        aSum += a[k] * a[k]
                        bSum += b[k] * b[k]
                    }
                }
                return if (aSum * bSum > 0) dot / sqrt(aSum * bSum) else 0.0
            }
            var best = 0.0
            var cut: Long? = null
            for (at in first..last) {
                val before = score(at - support, old)
                val after = score(at, new)
                if (minOf(before, after) < minimum) continue
                if (before - score(at - support, new) < 0.12 || after - score(at, old) < 0.12) continue
                if (before + after > best) {
                    best = before + after
                    cut = at * frameMs
                }
            }
            return cut
        }
    }

    fun align(reference: List<Landmark>, takes: List<MulticamTake>, prints: Map<String, List<Landmark>>,
              checkCancelled: () -> Unit = {}): List<MulticamTake> = alignRecordings(
        Recording(reference, emptyList(), FloatArray(0)), takes,
        prints.mapValues { Recording(it.value, emptyList(), FloatArray(0)) }, checkCancelled,
    )

    fun alignRecordings(reference: Recording, takes: List<MulticamTake>, prints: Map<String, Recording>,
                        checkCancelled: () -> Unit = {}): List<MulticamTake> {
        val master = reference.index()
        val aligned = takes.map { take ->
            checkCancelled()
            val print = prints[take.file]
            if (take.startMs != null || print == null) return@map take
            val sections = master.partialAlignment(print, checkCancelled)
            if (sections != null) return@map take.copy(
                startMs = sections.offsetMs, needsReview = true,
                proposedSpans = sections.parts.mapNotNull { span ->
                    val end = minOf(span.toMs, take.lengthMs)
                    span.copy(toMs = end).takeIf { end - it.fromMs >= 100L }
                },
            )
            master.match(print)?.let { take.withMatch(it) } ?: take
        }.toMutableList()
        val bridges = mutableMapOf<String, RecordingIndex>()
        do {
            val established = aligned.filter { it.startMs != null && !it.needsReview }
            var changed = false
            for (i in aligned.indices) {
                checkCancelled()
                val take = aligned[i]
                if (take.startMs != null) continue
                val print = prints[take.file] ?: continue
                val matches = established.mapNotNull { bridge ->
                    val bridgePrint = prints[bridge.file] ?: return@mapNotNull null
                    val match = bridges.getOrPut(bridge.file, bridgePrint::index).match(print) ?: return@mapNotNull null
                    match.copy(offsetMs = checkNotNull(bridge.startMs) + match.offsetMs)
                }
                val best = matches.maxByOrNull { it.votes } ?: continue
                val conflict = matches.any { abs(it.offsetMs - best.offsetMs) > 100L && it.votes * 2 >= best.votes }
                aligned[i] = take.withMatch(best, conflict)
                changed = true
            }
        } while (changed)
        return aligned
    }

    private fun MulticamTake.withMatch(match: Match, conflict: Boolean = false): MulticamTake =
        copy(startMs = match.offsetMs, needsReview = conflict || abs(match.driftMs) > 40L)

    fun read(reader: ClipAudio.PcmSource, checkCancelled: () -> Unit): List<Landmark> =
        analyse(reader, checkCancelled).landmarks

    fun analyse(reader: ClipAudio.PcmSource, checkCancelled: () -> Unit, features: Boolean = true): Recording {
        if (reader.rate <= 0 || reader.channels <= 0 || reader.durationMs <= 0) {
            return Recording(emptyList(), emptyList(), FloatArray(0))
        }
        val analyser = Fingerprint(features)
        val count = reader.durationMs * RATE / 1000L
        var converted = 0L
        while (converted < count) {
            checkCancelled()
            val outputCount = minOf(2048L, count - converted).toInt()
            val begin = converted * reader.rate / RATE
            val end = (converted + outputCount) * reader.rate / RATE
            val pcm = reader.read(begin + reader.delayFrames, (end - begin).toInt())
            if (pcm.isEmpty()) break
            for (i in 0 until outputCount) {
                val from = ((converted + i) * reader.rate / RATE - begin).toInt()
                val to = (((converted + i + 1) * reader.rate / RATE - begin).toInt()).coerceAtLeast(from + 1)
                var sum = 0.0
                for (j in from * reader.channels until minOf(pcm.size, to * reader.channels)) sum += pcm[j]
                analyser.sample(sum / ((to - from) * reader.channels * 32768.0))
            }
            converted += outputCount
        }
        return Recording(analyser.landmarks, analyser.trace, analyser.peaks.toFloatArray(), analyser.harmonics)
    }

    private class Fingerprint(private val features: Boolean) {
        val landmarks = mutableListOf<Landmark>()
        val trace = mutableListOf<FloatArray>()
        val harmonics = mutableListOf<FloatArray>()
        val peaks = mutableListOf<Float>()
        private val ring = DoubleArray(FFT)
        private val re = DoubleArray(FFT)
        private val im = DoubleArray(FFT)
        private val energy = DoubleArray(FFT / 2)
        private val history = ArrayDeque<Pair<Int, IntArray>>()
        private var samples = 0
        private var frame = 0
        private var peak = 0f
        private val past = DoubleArray(bands.size - 1)
        private val pitchAverage = DoubleArray(12)
        private val pitchWork = DoubleArray(12)

        fun sample(value: Double) {
            ring[samples % FFT] = value
            samples++
            peak = maxOf(peak, abs(value).toFloat())
            if (samples % (RATE * TrackAudio.BUCKET_MS / 1000) == 0) {
                peaks += peak
                peak = 0f
            }
            if (!features) return
            if (samples < FFT || samples % HOP != 0) return
            frame++
            if (frame % 2 == 0) return
            for (i in re.indices) re[i] = ring[(samples + i) % FFT] * window[i]
            im.fill(0.0)
            BeatGrid.fft(re, im)
            for (i in energy.indices) energy[i] = re[i] * re[i] + im[i] * im[i]
            trace += FloatArray(past.size) { band ->
                var sum = 0.0
                for (bin in bands[band] until bands[band + 1]) sum += energy[bin]
                val level = ln(0.0001 + sum)
                val change = if (trace.isEmpty()) 0.0 else (level - past[band]).coerceIn(-2.5, 2.5)
                past[band] = level
                change.toFloat()
            }
            val strongest = energy.maxOrNull() ?: return
            harmonicVector(strongest)
            if (strongest < 0.002) return
            val peakBins = (0 until bands.lastIndex).mapNotNull { band ->
                val lower = bands[band]
                val upper = bands[band + 1]
                var index = lower
                for (i in lower + 1 until upper) if (energy[i] > energy[index]) index = i
                index.takeIf { energy[it] >= strongest * 0.012 }
            }.sortedByDescending { energy[it] }
            val peaks = peakBins.take(3).map { it / 2 }.toIntArray()
            while (history.isNotEmpty() && frame - history.first().first > 22) history.removeFirst()
            for ((at, anchor) in history) {
                val gap = frame - at
                if (gap != 6 && gap != 12 && gap != 20) continue
                for (a in anchor.take(2)) for (b in peaks) {
                    if (a == b) continue
                    landmarks += Landmark((a shl 16) or (b shl 8) or gap, at)
                }
            }
            history.addLast(frame to peaks)
        }

        private fun harmonicVector(strongest: Double) {
            if (strongest < 0.002) {
                pitchAverage.fill(0.0)
                harmonics += FloatArray(12)
                return
            }
            pitchWork.fill(0.0)
            for (bin in 13 until 185) {
                val power = energy[bin]
                if (power < strongest * 0.012 || power < energy[bin - 1] || power <= energy[bin + 1]) continue
                val left = ln(0.000001 + energy[bin - 1])
                val center = ln(0.000001 + power)
                val right = ln(0.000001 + energy[bin + 1])
                val delta = (0.5 * (left - right) / (left - 2 * center + right)).coerceIn(-0.5, 0.5)
                val hz = (bin + delta) * RATE.toDouble() / FFT
                val note = 69 + 12 * log2(hz / 440)
                val base = floor(note).toInt()
                val part = note - base
                val weight = sqrt(power / strongest)
                pitchWork[base % 12] += weight * (1 - part)
                pitchWork[(base + 1) % 12] += weight * part
            }
            val sum = pitchWork.sum().coerceAtLeast(1e-9)
            val bins = FloatArray(12) { i ->
                pitchAverage[i] += (pitchWork[i] / sum - pitchAverage[i]) * 0.24
                when {
                    pitchAverage[i] >= 0.24 -> 4f
                    pitchAverage[i] >= 0.12 -> 3f
                    pitchAverage[i] >= 0.06 -> 2f
                    pitchAverage[i] >= 0.03 -> 1f
                    else -> 0f
                }
            }
            val mean = bins.average().toFloat()
            var energy = 0f
            for (i in bins.indices) {
                bins[i] -= mean
                energy += bins[i] * bins[i]
            }
            if (energy > 0f) {
                val norm = sqrt(energy)
                for (i in bins.indices) bins[i] /= norm
            }
            harmonics += bins
        }
    }

    /** Compare relative attacks and releases across frequency bands when room acoustics changed the exact peaks. */
    private fun matchTrace(reference: List<FloatArray>, take: List<FloatArray>, harmonic: Boolean = false,
                           around: Long? = null, strict: Boolean = false): Match? {
        val span = minOf(reference.size, take.size, if (harmonic) 240 else 180)
        if (span < 55) return null
        val windows = if (take.size <= span) listOf(0) else
            (0..6).map { (take.size - span) * it / 6 }.distinct()
        data class Hit(val at: Int, val offset: Int, val score: Double, val margin: Double)
        val hits = windows.mapNotNull { start ->
            var trackEnergy = 0.0
            // Sparse comparisons make a whole concert searchable without keeping its PCM in memory.
            val sample = (0 until span step 2).toList()
            for (i in sample) for (v in take[start + i]) trackEnergy += v * v
            if (trackEnergy < sample.size * 0.06) return@mapNotNull null
            val center = around?.let { (it / (2 * FRAME_MS)).toInt() + start }
            val first = center?.let { maxOf(0, it - 6) } ?: 0
            val last = center?.let { minOf(reference.size - span, it + 6) } ?: reference.size - span
            if (last < first) return@mapNotNull null
            val scores = DoubleArray(last - first + 1)
            for (index in scores.indices) {
                val ref = first + index
                var dot = 0.0
                var refEnergy = 0.0
                for (i in sample) {
                    val a = take[start + i]
                    val b = reference[ref + i]
                    for (band in a.indices) {
                        dot += a[band] * b[band]
                        refEnergy += b[band] * b[band]
                    }
                }
                if (refEnergy > 0.0) scores[index] = dot / sqrt(trackEnergy * refEnergy)
            }
            val best = scores.indices.maxByOrNull { scores[it] } ?: return@mapNotNull null
            val rival = scores.indices.asSequence().filter { abs(it - best) > 7 }.maxOfOrNull { scores[it] } ?: 0.0
            val minimum = if (harmonic) 0.54 else if (around != null) 0.23 else 0.32
            if (scores[best] < minimum) null else Hit(start, first + best - start, scores[best], scores[best] - rival)
        }
        val anchored = hits.filter { it.margin >= 0.045 }
        val strongest = anchored.maxByOrNull { it.score } ?: return null
        if (strict && (strongest.score < (if (harmonic) 0.72 else 0.66) || strongest.margin < 0.14)) return null
        val agreement = hits.filter { abs(it.offset - strongest.offset) <= 3 }.sortedBy { it.at }
        if (agreement.size < 2 && (strongest.score < 0.53 || strongest.margin < 0.10)) return null
        val contradictory = anchored.any {
            abs(it.offset - strongest.offset) > 7 && it.score >= strongest.score - 0.035 &&
                agreement.count { hit -> hit.margin >= 0.045 } < 2
        }
        if (contradictory) return null
        val offset = agreement.map { it.offset }.sorted()[agreement.size / 2]
        val drift = (agreement.last().offset - agreement.first().offset) * 2 * FRAME_MS
        return Match(offset * 2 * FRAME_MS, drift, agreement.size * 24)
    }

    class Index(landmarks: List<Landmark>) {
        private val byHash = landmarks.groupBy({ it.hash }, { it.frame })
            .mapValues { it.value.distinct() }.filterValues { it.size <= 40 }

        fun match(landmarks: List<Landmark>): Match? {
            val votes = hashMapOf<Int, Int>()
            for (mark in landmarks) {
                for (ref in byHash[mark.hash].orEmpty()) {
                    val bucket = ((ref - mark.frame) / 4.0).roundToInt()
                    votes[bucket] = (votes[bucket] ?: 0) + 1
                }
            }
            val best = votes.maxByOrNull { it.value } ?: return null
            val competing = votes.filterKeys { abs(it - best.key) > 3 }.values.maxOrNull() ?: 0
            if (best.value < 24 || best.value < competing * 1.45) return null
            val anchors = hashMapOf<Int, Int>()
            for (mark in landmarks) {
                val found = byHash[mark.hash].orEmpty().minByOrNull { abs(it - mark.frame - best.key * 4) }
                if (found != null && abs(found - mark.frame - best.key * 4) <= 5) {
                    anchors[mark.frame] = found - mark.frame
                }
            }
            val ordered = anchors.entries.sortedBy { it.key }
            if (ordered.size < 16 || ordered.last().key - ordered.first().key < 42) return null
            val support = ordered.size / 3
            val early = ordered.take(support).map { it.value }.sorted()[support / 2]
            val late = ordered.takeLast(support).map { it.value }.sorted()[support / 2]
            val measuredSpan = ordered[ordered.size - support + support / 2].key - ordered[support / 2].key
            val slope = (late - early).toDouble() / measuredSpan
            val drift = (slope * (ordered.last().key - ordered.first().key) * FRAME_MS).roundToLong()
            val median = ordered.map { it.value }.sorted()[ordered.size / 2]
            val start = if (abs(drift) > 40L) median - slope * ordered[ordered.size / 2].key else median.toDouble()
            return Match((start * FRAME_MS).roundToLong(), drift, ordered.size)
        }
    }
}
