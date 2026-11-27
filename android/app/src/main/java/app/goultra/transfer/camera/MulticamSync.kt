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
    private val spectralEdges = (0..32).map { (4 * Math.pow(60.0, it / 32.0)).roundToInt() }.distinct()

    data class Landmark(val hash: Int, val frame: Int)
    data class Match(val offsetMs: Long, val driftMs: Long, val votes: Int)
    data class Recording @JvmOverloads constructor(
        val landmarks: List<Landmark>,
        val trace: List<FloatArray>,
        val peaks: FloatArray,
        val harmonics: List<FloatArray> = emptyList(),
        val spectrum: List<FloatArray> = emptyList(),
    ) {
        fun index(): RecordingIndex = RecordingIndex(this)
    }

    class RecordingIndex(recording: Recording) {
        private val exact = Index(recording.landmarks)
        private val trace = recording.trace
        private val harmonics = recording.harmonics
        private val spectrum = recording.spectrum
        private val timingSpectrum by lazy { spectralChanges(spectrum, 1) }

        fun match(candidate: Recording): Match? {
            val landmarks = exact.match(candidate.landmarks)
            if (landmarks != null && abs(landmarks.driftMs) <= 40L) return landmarks
            val attacks = matchTrace(trace, candidate.trace)
            if (attacks != null) return attacks
            val harmony = matchTrace(harmonics, candidate.harmonics, harmonic = true)
            if (harmony == null) return landmarks
            return matchTrace(trace, candidate.trace, around = harmony.offsetMs) ?: harmony
        }

        internal data class Arrangement(val offsetMs: Long, val parts: List<MulticamSpan>, val driftMs: Long? = null,
                                        val whole: Boolean = false)

        private data class Choice(val offset: Long, val score: Double)
        private data class Probe(val from: Int, val to: Int, val short: Boolean, val choices: List<Choice>)
        private data class Observation(val probe: Probe, val choice: Choice, val alternatives: List<Long>)

        // Local alignment of chroma sequences (Ewert et al., IEEE TMM 2012).
        // Each editable section plays at unit speed, so paths follow diagonals. A local
        // restart anywhere in either recording permits missing passages and reordered takes.
        private fun harmonicChanges(frames: List<FloatArray>, block: Int): List<FloatArray> {
            val means = List(frames.size / block) { i ->
                FloatArray(frames[i * block].size) { k ->
                    var sum = 0f
                    for (t in 0 until block) sum += frames[i * block + t][k]
                    sum / block
                }
            }
            return means.mapIndexed { i, mean ->
                val prior = means[(i - 2).coerceAtLeast(0)]
                val delta = FloatArray(mean.size) { k -> mean[k] - prior[k] }
                val norm = sqrt(delta.sumOf { (it * it).toDouble() }).coerceAtLeast(0.15).toFloat()
                for (k in delta.indices) delta[k] /= norm
                delta
            }
        }

        private fun spectralChanges(frames: List<FloatArray>, block: Int): List<FloatArray> {
            if (frames.isEmpty()) return emptyList()
            val sums = DoubleArray(frames.first().size)
            var left = 0
            var right = 0
            return List(frames.size / block) { i ->
                val start = i * block
                val lo = maxOf(0, start - 32)
                val hi = minOf(frames.size, start + block + 32)
                val delta = FloatArray(frames[start].size)
                while (left < lo) {
                    val frame = frames[left++]
                    for (k in delta.indices) sums[k] -= frame[k].toDouble()
                }
                while (right < hi) {
                    val frame = frames[right++]
                    for (k in delta.indices) sums[k] += frame[k].toDouble()
                }
                for (t in start until start + block) for (k in delta.indices) delta[k] += frames[t][k]
                for (k in delta.indices) delta[k] = (delta[k] / block - sums[k] / (hi - lo)).toFloat()
                val norm = sqrt(delta.sumOf { (it * it).toDouble() }).coerceAtLeast(0.25).toFloat()
                for (k in delta.indices) delta[k] /= norm
                delta
            }
        }

        private fun harmonicPaths(candidate: Recording, block: Int, checkCancelled: () -> Unit): List<Int?> {
            val source = candidate.harmonics
            if (source.isEmpty() || harmonics.isEmpty()) return emptyList()
            val ref = harmonicChanges(harmonics, block)
            val take = harmonicChanges(source, block)
            if (ref.isEmpty() || take.isEmpty()) return emptyList()
            val bandRef = spectralChanges(spectrum, block)
            val bandTake = spectralChanges(candidate.spectrum, block)
            val hasBands = bandRef.size == ref.size && bandTake.size == take.size
            // Viterbi over rigid placements with a penalty for opening a new section.
            // At a restart every previous placement is eligible (even a later refrain).
            // A shorter forward jump represents a passage skipped in the camera recording.
            var scores = DoubleArray(ref.size)
            var next = DoubleArray(ref.size)
            val gapPredecessor = IntArray(take.size)
            var gapScore = 0.0
            val jumps = Array(take.size) { ByteArray(ref.size) }
            val nearby = IntArray(ref.size)
            val reach = minOf(125, 1000 / block)
            var best = -1
            var bestScore = 0.0
            for ((i, frame) in take.withIndex()) {
                checkCancelled()
                val jumpScore = bestScore - 10.0
                val restartScore = maxOf(gapScore, jumpScore)
                gapPredecessor[i] = if (jumpScore > gapScore) best else -1
                gapScore = restartScore
                var head = 0
                var tail = 0
                best = -1
                bestScore = restartScore
                for (j in ref.indices) {
                    var similarity = 0.0
                    for (k in frame.indices) similarity += frame[k] * ref[j][k]
                    if (hasBands) {
                        var spectral = 0.0
                        val a = bandTake[i]
                        val b = bandRef[j]
                        for (k in a.indices) spectral += a[k] * b[k]
                        similarity = 0.35 * similarity + 0.65 * spectral
                    }
                    val prior = if (j == 0) Double.NEGATIVE_INFINITY else scores[j - 1]
                    while (head < tail && nearby[head] < j - reach) head++
                    if (j > 0) {
                        while (head < tail && scores[nearby[tail - 1]] <= scores[j - 1]) tail--
                        nearby[tail++] = j - 1
                    }
                    val local = if (head < tail) scores[nearby[head]] - 4.5 else Double.NEGATIVE_INFINITY
                    if (prior < maxOf(local, restartScore)) jumps[i][j] =
                        if (local > restartScore) (j - nearby[head]).toByte() else (-1).toByte()
                    next[j] = maxOf(prior, local, restartScore) + similarity - 0.20
                    if (next[j] > bestScore) {
                        bestScore = next[j]
                        best = j
                    }
                }
                val swap = scores
                scores = next
                next = swap
            }
            val result = MutableList<Int?>(take.size) { null }
            for (i in take.indices.reversed()) {
                if (best < 0) best = gapPredecessor[i] else {
                    result[i] = (best - i) * block
                    val jump = jumps[i][best].toInt()
                    best = if (jump < 0) gapPredecessor[i] else best - maxOf(1, jump)
                }
            }
            return result
        }
        /** Internal gaps can be room noise. An uncovered edge cannot inherit the offset of a distant passage. */
        internal fun partialAlignment(candidate: Recording, checkCancelled: () -> Unit): Arrangement? {
            val size = maxOf(candidate.trace.size, candidate.harmonics.size)
            val width = 96
            val edgeWidth = 64
            if (size < edgeWidth * 2) return null
            val stride = width
            val edges = (0 until 8).flatMap { listOf(it * edgeWidth, size - (it + 1) * edgeWidth) }
                .filter { it in 0..size - edgeWidth }.map { it to (it + edgeWidth) }
            val probes = ((0..size - width step stride).map { it to (it + width) } +
                edges + listOf(size - width to size))
                .distinct().sortedBy { it.first }
            val marks = candidate.landmarks.sortedBy { it.frame }
            val block = maxOf(4, kotlin.math.ceil(sqrt(harmonics.size.toDouble() * candidate.harmonics.size / 8_000_000)).toInt())
            val paths = harmonicPaths(candidate, block, checkCancelled)
            val runs = mutableListOf<MutableList<MulticamSpan>>()
            val reliableEdges = mutableSetOf<MulticamSpan>()
            fun locate(ref: List<FloatArray>, source: List<FloatArray>, from: Int, to: Int, harmonic: Boolean): List<Choice> {
                if (to > source.size || to - from > ref.size) return emptyList()
                val scores = traceScores(ref, source, from, to - from, 0, ref.size - (to - from))
                val threshold = if (harmonic) 0.69 else if (to - from == edgeWidth) 0.65 else 0.52
                return peaks(scores, 7, threshold).map { at ->
                    Choice((at - from) * 2 * FRAME_MS, scores[at] - if (harmonic) 0.11 else 0.0)
                }
            }
            fun observe(from: Int, to: Int): Probe? {
                checkCancelled()
                val key = from * 2
                var index = marks.binarySearchBy(key) { it.frame }.let { if (it < 0) -it - 1 else it }
                while (index > 0 && marks[index - 1].frame == key) index--
                val shortProbe = to - from == edgeWidth
                val landmarks = exact.regional(marks.subList(index, marks.size).takeWhile { it.frame < to * 2 })
                    .filter { abs(it.first.driftMs) <= 64L && (!shortProbe || it.first.votes >= 32) }
                    .map { (match, strength) -> Choice(match.offsetMs, strength) }
                val attacks = locate(trace, candidate.trace, from, to, harmonic = false)
                val harmonic = listOfNotNull(paths.getOrNull((to - 1) / block)).mapNotNull { shift ->
                    if (to > candidate.harmonics.size) return@mapNotNull null
                    val begin = maxOf(0, from + shift - block)
                    val end = minOf(harmonics.size - (to - from), from + shift + block)
                    if (begin > end) return@mapNotNull null
                    val scores = traceScores(harmonics, candidate.harmonics, from, to - from, begin, end)
                    val best = scores.indices.maxByOrNull { scores[it] } ?: return@mapNotNull null
                    if (scores[best] < 0.30) null else Choice((begin + best - from) * 2 * FRAME_MS, 0.48 + scores[best] * 0.65)
                }
                val harmonyChoices = when {
                    // An identical refrain has multiple equally good paths. Traceback picks
                    // one on ties. Give the sequence resolver the other exact repeats too.
                    harmonic.any { it.score >= 1.08 } -> harmonic +
                        locate(harmonics, candidate.harmonics, from, to, true)
                            .filter { it.score >= 0.82 }
                            .map { it.copy(score = 0.48 + (it.score + 0.11) * 0.65) }
                    harmonic.isEmpty() && landmarks.isEmpty() && attacks.isEmpty() ->
                        locate(harmonics, candidate.harmonics, from, to, true)
                    else -> harmonic
                }
                val channels = listOf(landmarks, attacks, harmonyChoices)
                // Independent evidence at the same position reinforces the candidate. Keep
                // distinct repetitions available to the sequence resolver.
                val ranked = channels.flatten().map { choice ->
                    val support = channels.count { channel -> channel.any { abs(it.offset - choice.offset) <= 96 } }
                    choice.copy(score = choice.score + 0.14 * (support - 1))
                }.sortedByDescending { it.score }
                val choices = mutableListOf<Choice>()
                for (choice in ranked) if (choices.none { abs(it.offset - choice.offset) <= 112 }) choices += choice
                return if (choices.isEmpty()) null else Probe(from, to, shortProbe, choices)
            }
            val sampled = probes.associateWith { (from, to) -> observe(from, to) }.toMutableMap()
            val wide = probes.filter { (from, to) -> to - from == width }
            for ((left, right) in wide.zipWithNext()) {
                val first = sampled[left]?.choices?.firstOrNull()
                val second = sampled[right]?.choices?.firstOrNull()
                if (first != null && second != null && abs(first.offset - second.offset) <= 112L) continue
                val start = (left.first + right.first) / 2
                val key = start to start + edgeWidth
                if (key !in sampled) sampled[key] = observe(key.first, key.second)
            }
            val observations = sampled.values.filterNotNull().sortedBy { it.from }
            val resolved = resolveProbes(observations)
            val drift = detectDrift(resolved)
            if (drift != null) return Arrangement(drift.first, emptyList(), drift.second)
            val offsets = resolved.sortedBy { it.choice.offset }
            for ((probe, choice, alternatives) in resolved) {
                val offset = choice.offset
                val nearEnd = probe.from < width || size - probe.to < width
                val edge = probe.short && nearEnd && choice.score >= 0.84 && alternatives.isEmpty()
                val lower = offsets.binarySearchBy(offset - 113L) { it.choice.offset }
                val start = if (lower < 0) -lower - 1 else lower + 1
                val corroborated = offsets.subList(start, offsets.size).asSequence()
                    .takeWhile { it.choice.offset <= offset + 112L }
                    .any { other -> other.probe.to <= probe.from || other.probe.from >= probe.to }
                if (!edge && !corroborated) continue
                val hit = MulticamSpan(probe.from * 2 * FRAME_MS, probe.to * 2 * FRAME_MS, offset, alternatives)
                if (edge) reliableEdges += hit
                val run = runs.lastOrNull()?.takeIf { abs(it.first().offsetMs - offset) <= 112L }
                    ?: mutableListOf<MulticamSpan>().also { runs += it }
                run += hit
            }
            val stable = runs.filter { windows ->
                windows.last().fromMs >= windows.first().toMs || windows.any { it in reliableEdges }
            }.map { windows ->
                val offsets = windows.map { it.offsetMs }.sorted()
                val alternatives = windows.flatMap { it.alternatives }.distinct().filter { offset ->
                    windows.count { hit -> hit.alternatives.any { abs(it - offset) <= 112L } } * 2 >= windows.size
                }.fold(mutableListOf<Long>()) { merged, offset ->
                    if (merged.none { abs(it - offset) <= 112L }) merged += offset
                    merged
                }
                MulticamSpan(windows.first().fromMs, windows.maxOf { it.toMs }, offsets[offsets.size / 2], alternatives) to windows.size
            }
            val strongestRaw = stable.maxByOrNull { it.second }?.first ?: return null
            val fineSource = spectralChanges(candidate.spectrum, 1)
            val strongest = refineOffset(strongestRaw, fineSource)
            val ranges = mutableListOf<MulticamSpan>()
            for ((raw, _) in stable) {
                val next = refineOffset(raw, fineSource)
                val last = ranges.lastOrNull()
                if (last != null && abs(last.offsetMs - next.offsetMs) <= 250L) {
                    val choices = last.alternatives.filter { offset -> next.alternatives.any { abs(it - offset) <= 112 } }
                    ranges[ranges.lastIndex] = last.copy(toMs = maxOf(last.toMs, next.toMs), alternatives = choices)
                } else ranges += next
            }
            val expanded = ranges.mapIndexed { i, span ->
                if (span.alternatives.isNotEmpty()) return@mapIndexed span
                val before = ranges.getOrNull(i - 1)?.toMs ?: 0L
                val after = ranges.getOrNull(i + 1)?.fromMs ?: size * 2 * FRAME_MS
                growSupportedRange(candidate, span, before, after, checkCancelled)
            }
            val parts = mutableListOf<MulticamSpan>()
            var lastRangeIndex = 0
            for (i in expanded.indices) {
                val next = expanded[i]
                val last = parts.lastOrNull()
                if (last == null) {
                    parts += next
                } else {
                    val extra = (last.offsetMs - next.offsetMs).coerceAtLeast(0L)
                    val gaps = if (last.alternatives.isEmpty() && next.alternatives.isEmpty()) listOf(extra, 0L).distinct()
                        else emptyList()
                    val join = gaps.firstNotNullOfOrNull { gap ->
                        val originalLeft = ranges[lastRangeIndex]
                        val originalRight = ranges[i]
                        val expectedGap = originalRight.fromMs - originalLeft.toMs
                        if (extra > 0 && abs(expectedGap - gap) > width * 2 * FRAME_MS) null
                        else {
                            // Keep confirmed anchors as bounds even when the unverified interval in between is long.
                            val left = last.copy(toMs = originalLeft.toMs)
                            val right = next.copy(fromMs = originalRight.fromMs)
                            val hint = if (gap != 0L) null else {
                                val near = block * 2 * FRAME_MS
                                val flank = maxOf(2, (1500L / near).toInt())
                                val first = maxOf(flank, (minOf(left.toMs, right.fromMs) / near).toInt() - flank)
                                val end = minOf(paths.size - flank, (maxOf(left.toMs, right.fromMs) / near).toInt() + flank)
                                (first..end).firstOrNull { at ->
                                    val prior = paths[at - 1] ?: return@firstOrNull false
                                    val nextShift = paths[at] ?: return@firstOrNull false
                                    abs(prior * 2 * FRAME_MS - left.offsetMs) <= near &&
                                        abs(nextShift * 2 * FRAME_MS - right.offsetMs) <= near &&
                                        (1..flank).all { paths[at - it] == prior && paths[at + it - 1] == nextShift }
                                }?.times(near)
                            }
                            val cut = spectralJoin(fineSource, left, right, gap, hint, checkCancelled)
                                ?: refineJoin(trace, candidate.trace, left, right, gap, 0.32, checkCancelled)
                                ?: refineJoin(harmonics, candidate.harmonics, left, right, gap, 0.56, checkCancelled)
                            (cut?.takeIf { (stop, resume) ->
                                hint == null || abs(stop - hint) <= 1500 && abs(resume - hint) <= 1500
                            } ?: hint?.let { it to it })?.takeIf { (stop, resume) ->
                                stop <= last.toMs + width * 2 * FRAME_MS &&
                                    resume >= next.fromMs - width * 2 * FRAME_MS
                            }
                        }
                    }
                    if (join != null) {
                        parts[parts.lastIndex] = last.copy(toMs = join.first)
                        parts += next.copy(fromMs = join.second)
                        lastRangeIndex = i
                        continue
                    }
                    val overlap = (last.toMs - next.fromMs).coerceAtLeast(0L)
                    val left = last.copy(toMs = last.toMs - overlap / 2)
                    val right = next.copy(fromMs = next.fromMs + (overlap + 1) / 2)
                    if (left.toMs - left.fromMs < 1_000L || right.toMs - right.fromMs < 1_000L) continue
                    parts[parts.lastIndex] = left
                    parts += right
                }
                lastRangeIndex = i
            }
            if (parts.any { span -> parts.any { it != span && it.overlaps(span) } } &&
                supportsWholePlacement(candidate, strongest.offsetMs, checkCancelled)) {
                return Arrangement(strongest.offsetMs, emptyList(), whole = true)
            }
            if (parts.size > 1 || parts.any { it.alternatives.isNotEmpty() }) return Arrangement(strongest.offsetMs, parts)
            val part = parts.singleOrNull() ?: return null
            val frameMs = 2 * FRAME_MS
            val referenceMs = maxOf(trace.size, harmonics.size) * frameMs
            val expectedFrom = maxOf(0L, -part.offsetMs)
            val expectedTo = minOf(size * frameMs, referenceMs - part.offsetMs)
            val unconfirmed = maxOf(part.fromMs - expectedFrom, expectedTo - part.toMs)
            return if (part.toMs - part.fromMs >= width * 2 * frameMs && unconfirmed >= stride * 2 * frameMs) {
                Arrangement(part.offsetMs, parts)
            } else if (candidate.landmarks.isNotEmpty() && unconfirmed < width * frameMs && supportsWholePlacement(candidate, part.offsetMs, checkCancelled)) {
                Arrangement(part.offsetMs, emptyList(), whole = true)
            } else null
        }

        private fun refineOffset(span: MulticamSpan, source: List<FloatArray>): MulticamSpan {
            if (source.isEmpty() || spectrum.isEmpty() || span.alternatives.isNotEmpty()) return span
            val frameMs = 2 * FRAME_MS
            val shift = (span.offsetMs.toDouble() / frameMs).roundToInt()
            val first = maxOf((span.fromMs / frameMs).toInt(), 6 - shift)
            val end = minOf((span.toMs / frameMs).toInt(), source.size, timingSpectrum.size - shift - 6)
            if (end - first < 96) return span
            // Pool independent onsets across the section so a sustained note or a loud
            // audience member cannot determine the final offset on their own.
            val scores = DoubleArray(13)
            val step = maxOf(64, (end - first) / 24)
            for (i in first..end - 64 step step) {
                val local = traceScores(timingSpectrum, source, i, 64, i + shift - 6, i + shift + 6)
                for (k in local.indices) scores[k] += local[k]
            }
            val best = scores.indices.maxBy { scores[it] }
            return if (scores[best] > scores[6] + 0.05) span.copy(offsetMs = (shift + best - 6) * frameMs) else span
        }

        /** Estimate the edit by accumulating the relative fit to both placements around it. */
        private fun spectralJoin(source: List<FloatArray>, left: MulticamSpan, right: MulticamSpan,
                                 gapMs: Long, hintMs: Long?, checkCancelled: () -> Unit): Pair<Long, Long>? {
            if (source.isEmpty() || spectrum.isEmpty()) return null
            val tick = 2 * FRAME_MS
            val old = (left.offsetMs.toDouble() / tick).roundToInt()
            val new = (right.offsetMs.toDouble() / tick).roundToInt()
            val gap = (gapMs / tick).toInt()
            val a = (left.toMs / tick).toInt()
            val b = (right.fromMs / tick).toInt() - gap
            val hint = hintMs?.div(tick)?.toInt()
            val lo = maxOf((left.fromMs / tick).toInt(), hint?.minus(48) ?: (minOf(a, b) - 128), -old, -new - gap)
            val hi = minOf((right.toMs / tick).toInt() - gap, hint?.plus(48) ?: (maxOf(a, b) + 128),
                source.size - gap, timingSpectrum.size - old, timingSpectrum.size - new - gap)
            if (hi - lo < 48) return null
            var cumulative = 0.0
            var maximum = 0.0
            var cut = lo
            for (i in lo until hi) {
                if (i % 128 == 0) checkCancelled()
                for (k in source[i].indices) cumulative +=
                    source[i][k] * timingSpectrum[i + old][k] -
                        source[i + gap][k] * timingSpectrum[i + gap + new][k]
                if (cumulative > maximum) {
                    maximum = cumulative
                    cut = i + 1
                }
            }
            if (cut - lo < 16 || hi - cut < 16 || maximum < 1.2 || maximum - cumulative < 1.2) return null
            return cut * tick to (cut + gap) * tick
        }

        private fun locallySupported(candidate: Recording, from: Int, to: Int, shift: Int): Boolean {
            val span = to - from
            if (span < 24) return false
            fun compare(reference: List<FloatArray>, source: List<FloatArray>): Double {
                if (to > source.size) return 0.0
                val begin = maxOf(0, from + shift - 2)
                val end = minOf(reference.size - span, from + shift + 2)
                if (end < begin) return 0.0
                return traceScores(reference, source, from, span, begin, end).maxOrNull() ?: 0.0
            }
            val attack = compare(trace, candidate.trace)
            if (attack >= 0.30) return true
            val melody = compare(harmonics, candidate.harmonics)
            return melody >= 0.67 || melody >= 0.52 && attack >= 0.13
        }

        private fun growSupportedRange(candidate: Recording, span: MulticamSpan, before: Long, after: Long,
                                       checkCancelled: () -> Unit): MulticamSpan {
            val frameMs = 2 * FRAME_MS
            val shift = (span.offsetMs.toDouble() / frameMs).roundToInt()
            val start = (span.fromMs / frameMs).toInt()
            val end = (span.toMs / frameMs).toInt()
            val earliest = maxOf(0, -shift, (before / frameMs).toInt())
            val latest = minOf(maxOf(trace.size, harmonics.size) - shift,
                maxOf(candidate.trace.size, candidate.harmonics.size), (after / frameMs).toInt())
            fun walk(edge: Int, limit: Int, direction: Int): Int {
                var cursor = edge
                var confirmed = edge
                var consecutive = 0
                var misses = 0
                while (cursor != limit) {
                    checkCancelled()
                    val next = if (direction < 0) maxOf(cursor - 64, limit) else minOf(cursor + 64, limit)
                    if (locallySupported(candidate, minOf(cursor, next), maxOf(cursor, next), shift)) {
                        consecutive++
                        misses = 0
                        if (consecutive >= 2) confirmed = next
                    } else {
                        consecutive = 0
                        if (++misses >= 2) break
                    }
                    cursor = next
                }
                return confirmed
            }
            val startAt = if (earliest < start) walk(start, earliest, -1) * frameMs else span.fromMs
            val endAt = if (latest > end) walk(end, latest, 1) * frameMs else span.toMs
            return span.copy(fromMs = startAt, toMs = endAt)
        }

        private fun supportsWholePlacement(candidate: Recording, offset: Long, checkCancelled: () -> Unit): Boolean {
            val shift = (offset.toDouble() / (2 * FRAME_MS)).roundToInt()
            val from = maxOf(0, -shift)
            val until = minOf(maxOf(trace.size, harmonics.size) - shift, maxOf(candidate.trace.size, candidate.harmonics.size))
            val thirds = (0..3).map { from + (until - from) * it / 3 }
            return thirds.zipWithNext().all { (start, end) ->
                val positions = (start..end - 48 step 48).toList()
                positions.size >= 2 && positions.count { at ->
                    checkCancelled()
                    locallySupported(candidate, at, at + 48, shift)
                } * 4 >= positions.size * 3
            }
        }

        private fun resolveProbes(probes: List<Probe>): List<Observation> {
            if (probes.isEmpty()) return emptyList()
            fun transition(a: Choice, b: Choice): Double = if (abs(a.offset - b.offset) <= 112L) 0.34 else 0.0
            val forward = probes.map { DoubleArray(it.choices.size) }
            val back = probes.map { DoubleArray(it.choices.size) }
            for (i in probes.indices) {
                for ((j, choice) in probes[i].choices.withIndex()) {
                    forward[i][j] = choice.score + if (i == 0) 0.0 else probes[i - 1].choices.mapIndexed { k, prior ->
                        forward[i - 1][k] + transition(prior, choice)
                    }.max()
                }
            }
            for (i in probes.lastIndex - 1 downTo 0) {
                for ((j, choice) in probes[i].choices.withIndex()) {
                    back[i][j] = probes[i + 1].choices.mapIndexed { k, next ->
                        back[i + 1][k] + next.score + transition(choice, next)
                    }.max()
                }
            }
            var previous: Choice? = null
            return probes.mapIndexed { i, probe ->
                val scores = probe.choices.indices.map { forward[i][it] + back[i][it] }
                val preferred = probe.choices.indices.maxBy { j ->
                    probe.choices[j].score + back[i][j] + (previous?.let { transition(it, probe.choices[j]) } ?: 0.0)
                }
                val choice = probe.choices[preferred]
                previous = choice
                val alternatives = probe.choices.indices.filter { it != preferred && scores[preferred] - scores[it] <= 0.15 }
                    .map { probe.choices[it].offset }
                Observation(probe, choice, alternatives)
            }
        }

        private fun detectDrift(resolved: List<Observation>): Pair<Long, Long>? {
            val clear = resolved.filter { it.alternatives.isEmpty() }.distinctBy { it.probe.from }
            if (clear.size < 6) return null
            val begin = clear.first().probe.from * 2 * FRAME_MS
            val end = clear.last().probe.from * 2 * FRAME_MS
            val startOffset = clear.first().choice.offset
            val drift = clear.last().choice.offset - startOffset
            if (end - begin < 12_000 || abs(drift) < 80 || abs(drift) > (end - begin) * 0.008) return null
            val slope = drift.toDouble() / (end - begin)
            val residual = clear.maxOf { (probe, choice) ->
                abs(choice.offset - startOffset - slope * (probe.from * 2 * FRAME_MS - begin))
            }
            if (residual > maxOf(40.0, abs(drift) * 0.23)) return null
            return (startOffset - slope * begin).roundToLong() to drift
        }

        private fun refineJoin(reference: List<FloatArray>, source: List<FloatArray>, left: MulticamSpan,
                               right: MulticamSpan, gap: Long, minimum: Double,
                               checkCancelled: () -> Unit): Pair<Long, Long>? {
            val frameMs = 2 * FRAME_MS
            val support = 48
            val old = (left.offsetMs.toDouble() / frameMs).roundToInt()
            val new = (right.offsetMs.toDouble() / frameMs).roundToInt()
            val skipped = (gap.toDouble() / frameMs).roundToInt()
            val nextStart = right.fromMs - skipped * frameMs
            val first = maxOf((left.fromMs / frameMs).toInt() + support,
                (minOf(left.toMs, nextStart) / frameMs).toInt() - support)
            val last = minOf((right.toMs / frameMs).toInt() - support - skipped,
                (maxOf(left.toMs, nextStart) / frameMs).toInt() + support)
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
            var cut: Pair<Long, Long>? = null
            for (at in first..last) {
                if (at % 48 == 0) checkCancelled()
                val before = score(at - support, old)
                val after = score(at + skipped, new)
                if (minOf(before, after) < minimum) continue
                if (before - score(at - support, new) < 0.12 || after - score(at + skipped, old) < 0.12) continue
                if (before + after > best) {
                    best = before + after
                    cut = (at * frameMs) to ((at + skipped) * frameMs)
                }
            }
            return cut
        }
    }

    fun align(reference: List<Landmark>, takes: List<MulticamTake>, prints: Map<String, List<Landmark>>,
              checkCancelled: () -> Unit = {}): List<MulticamTake> = alignRecordings(
        Recording(reference, emptyList(), FloatArray(0)), takes,
        prints.mapValues { Recording(it.value, emptyList(), FloatArray(0)) }, checkCancelled = checkCancelled,
    )

    fun alignRecordings(reference: Recording, takes: List<MulticamTake>, prints: Map<String, Recording>,
                        onCompared: (Int) -> Unit = {},
                        checkCancelled: () -> Unit = {}): List<MulticamTake> {
        val master = reference.index()
        val aligned = takes.mapIndexed { i, take ->
            checkCancelled()
            val print = prints[take.file]
            val placed = if (take.cutaway || take.startMs != null || print == null) take else {
                val sections = master.partialAlignment(print, checkCancelled)
                when {
                    sections != null -> take.withArrangement(sections)
                    else -> master.match(print)?.let { take.withMatch(it) } ?: take
                }
            }
            onCompared(i + 1)
            placed
        }.toMutableList()
        val bridges = mutableMapOf<String, RecordingIndex>()
        val bridgeMatches = mutableMapOf<Pair<String, String>, Match?>()
        do {
            val established = aligned.filter { it.startMs != null && !it.needsReview }
            var changed = false
            for (i in aligned.indices) {
                checkCancelled()
                val take = aligned[i]
                if (take.cutaway) continue
                if (take.startMs != null && take.proposedSpans.none { it.alternatives.isNotEmpty() }) continue
                val print = prints[take.file] ?: continue
                val matches = established.mapNotNull { bridge ->
                    val bridgePrint = prints[bridge.file] ?: return@mapNotNull null
                    val key = bridge.file to take.file
                    if (key !in bridgeMatches) bridgeMatches[key] = bridges.getOrPut(bridge.file, bridgePrint::index).match(print)
                    val match = bridgeMatches[key] ?: return@mapNotNull null
                    bridge to match.copy(offsetMs = checkNotNull(bridge.startMs) - bridge.sourceFromMs + match.offsetMs)
                }
                if (take.proposedSpans.isNotEmpty()) {
                    val resolved = take.proposedSpans.map { span ->
                        if (span.alternatives.isEmpty()) return@map span
                        val choices = (listOf(span.offsetMs) + span.alternatives).filter { offset ->
                            matches.any { (bridge, match) ->
                                abs(match.driftMs) <= 40L && abs(match.offsetMs - offset) <= 112L &&
                                    span.fromMs + offset < checkNotNull(bridge.endMs) &&
                                    span.toMs + offset > checkNotNull(bridge.startMs)
                            }
                        }
                        if (choices.size == 1) span.copy(offsetMs = choices.single(), alternatives = emptyList()) else span
                    }
                    if (resolved != take.proposedSpans) {
                        val confirmed = resolved.filter { it.alternatives.isEmpty() }.maxByOrNull { it.toMs - it.fromMs }
                        aligned[i] = take.copy(proposedSpans = resolved, startMs = confirmed?.offsetMs ?: take.startMs)
                        changed = true
                    }
                    continue
                }
                val (bridge, best) = matches.maxByOrNull { it.second.votes } ?: continue
                val conflict = matches.any { (_, match) ->
                    abs(match.offsetMs - best.offsetMs) > 100L && match.votes * 2 >= best.votes
                }
                val sections = bridges.getValue(bridge.file).partialAlignment(print, checkCancelled)
                val origin = checkNotNull(bridge.startMs) - bridge.sourceFromMs
                val resolved = if (sections != null) take.withArrangement(sections, origin) else take.withMatch(best, conflict)
                aligned[i] = resolved
                changed = true
            }
        } while (changed)
        return aligned
    }

    private fun MulticamTake.withArrangement(result: RecordingIndex.Arrangement, origin: Long = 0L): MulticamTake =
        copy(startMs = result.offsetMs + origin, needsReview = !result.whole, driftMs = result.driftMs,
            proposedSpans = result.parts.mapNotNull { span ->
                val end = minOf(span.toMs, lengthMs)
                span.copy(toMs = end, offsetMs = span.offsetMs + origin, alternatives = span.alternatives.map { it + origin })
                    .takeIf { end - it.fromMs >= 100L }
            })

    private fun MulticamTake.withMatch(match: Match, conflict: Boolean = false): MulticamTake =
        copy(startMs = match.offsetMs, needsReview = conflict || abs(match.driftMs) > 40L,
            driftMs = match.driftMs.takeIf { abs(it) > 40L })

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
        return Recording(analyser.landmarks, analyser.trace, analyser.peaks.toFloatArray(), analyser.harmonics, analyser.spectrum)
    }

    private class Fingerprint(private val features: Boolean) {
        val spectrum = mutableListOf<FloatArray>()
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
            spectrum += FloatArray(spectralEdges.lastIndex) { band ->
                var sum = 0.0
                for (bin in spectralEdges[band] until spectralEdges[band + 1]) sum += energy[bin]
                ln(0.0001 + sum).toFloat()
            }
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
                           around: Long? = null): Match? {
        val span = minOf(reference.size, take.size, if (harmonic) 240 else 180)
        if (span < 55) return null
        val windows = if (take.size <= span) listOf(0) else
            (0..6).map { (take.size - span) * it / 6 }.distinct()
        data class Hit(val at: Int, val offset: Int, val score: Double, val margin: Double)
        val hits = windows.mapNotNull { start ->
            val center = around?.let { (it / (2 * FRAME_MS)).toInt() + start }
            val first = center?.let { maxOf(0, it - 6) } ?: 0
            val last = center?.let { minOf(reference.size - span, it + 6) } ?: reference.size - span
            if (last < first) return@mapNotNull null
            val scores = traceScores(reference, take, start, span, first, last)
            val best = scores.indices.maxByOrNull { scores[it] } ?: return@mapNotNull null
            val rival = scores.indices.asSequence().filter { abs(it - best) > 7 }.maxOfOrNull { scores[it] } ?: 0.0
            val minimum = if (harmonic) 0.54 else if (around != null) 0.23 else 0.32
            if (scores[best] < minimum) null else Hit(start, first + best - start, scores[best], scores[best] - rival)
        }
        val anchored = hits.filter { it.margin >= 0.045 }
        val strongest = anchored.maxByOrNull { it.score } ?: return null
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

    private fun traceScores(reference: List<FloatArray>, take: List<FloatArray>, start: Int,
                            span: Int, first: Int, last: Int): DoubleArray {
        var energy = 0.0
        for (i in 0 until span step 2) for (v in take[start + i]) energy += v * v
        if (energy < span * 0.03) return DoubleArray(0)
        return DoubleArray(last - first + 1) { index ->
            val ref = first + index
            var dot = 0.0
            var refEnergy = 0.0
            for (i in 0 until span step 2) {
                val a = take[start + i]
                val b = reference[ref + i]
                for (band in a.indices) {
                    dot += a[band] * b[band]
                    refEnergy += b[band] * b[band]
                }
            }
            if (refEnergy > 0.0) dot / sqrt(energy * refEnergy) else 0.0
        }
    }

    private fun peaks(scores: DoubleArray, separation: Int, minimum: Double): List<Int> {
        val found = mutableListOf<Int>()
        repeat(4) {
            var best = -1
            for (i in scores.indices) {
                if (scores[i] < minimum || best >= 0 && scores[i] <= scores[best]) continue
                if (found.none { abs(it - i) <= separation }) best = i
            }
            if (best < 0) return found
            found += best
        }
        return found
    }

    class Index(landmarks: List<Landmark>) {
        private val byHash = landmarks.groupBy({ it.hash }, { it.frame })
            .mapValues { it.value.distinct() }.filterValues { it.size <= 40 }

        private fun votes(landmarks: List<Landmark>): Map<Int, Int> {
            val votes = hashMapOf<Int, Int>()
            for (mark in landmarks) {
                for (ref in byHash[mark.hash].orEmpty()) {
                    val bucket = ((ref - mark.frame) / 4.0).roundToInt()
                    votes[bucket] = (votes[bucket] ?: 0) + 1
                }
            }
            return votes
        }

        fun match(landmarks: List<Landmark>): Match? {
            val votes = votes(landmarks)
            val best = votes.maxByOrNull { it.value } ?: return null
            val competing = votes.filterKeys { abs(it - best.key) > 3 }.values.maxOrNull() ?: 0
            if (best.value < 24 || best.value < competing * 1.45) return null
            return fit(landmarks, best.key)
        }

        fun regional(landmarks: List<Landmark>): List<Pair<Match, Double>> {
            val votes = votes(landmarks)
            val strongest = votes.values.maxOrNull()?.takeIf { it >= 24 } ?: return emptyList()
            val placements = mutableListOf<Int>()
            return votes.entries.sortedByDescending { it.value }.asSequence()
                .takeWhile { it.value >= maxOf(24, (strongest * 0.70).toInt()) }
                .filter { entry -> placements.none { abs(entry.key - it) <= 3 } }
                .mapNotNull { (bucket, count) ->
                    fit(landmarks, bucket)?.let {
                        placements += bucket
                        val uniqueness = 0.50 + 0.5 * count.toDouble() / strongest
                        val support = 0.50 + 0.5 * (it.votes / 60.0).coerceAtMost(1.0)
                        it to minOf(uniqueness, support)
                    }
                }.take(4).toList()
        }

        private fun fit(landmarks: List<Landmark>, bucket: Int): Match? {
            val anchors = hashMapOf<Int, Int>()
            for (mark in landmarks) {
                val found = byHash[mark.hash].orEmpty().minByOrNull { abs(it - mark.frame - bucket * 4) }
                if (found != null && abs(found - mark.frame - bucket * 4) <= 5) {
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
