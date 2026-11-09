package app.goultra.transfer.camera

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The auto montage (P3, see `memory-bank/projects-plan.md`): the project's material as a pool of source ranges, the
 * track's beat grid, and the rules that turn the two into musical slots filled by new pieces. Pure arithmetic, no
 * decoder and no Compose, so the whole editorial part is checked on the JVM; [ClipEnergy] only supplies the 0…1 value
 * of a source.
 *
 * The pool is deliberately not «one clip = one piece of the result». Four long takes are four *sources*: the montage
 * walks a cursor through each of them and cuts as many musical pieces as it needs. Nothing in a camera file changes.
 */
object Montage {
    /** A piece shorter than this is not worth a card (and a cut no eye can follow): it is left in the source. */
    const val MIN_PIECE_MS = 400L

    /** The longest slot the beat-count rule may ask for: a slow track still gets a cut inside a long stretch. */
    const val MAX_PIECE_MS = 16_000L

    /** Ordinary plans are kept at least this long; shorter pieces are reserved for accents and drops. */
    const val MIN_REGULAR_MS = 900L

    /** Three quick pieces in a row are a rhythm; a fourth starts to look like a broken screen. */
    const val MAX_SHORT_RUN = 3

    /**
     * One source range: [clip] as the project knows it, plus the part of it the montage may still take. They are the
     * same for an ordinary project; kept apart because a marked range may later be widened without changing the card.
     */
    data class Source(
        val clip: ProjectClip,
        val availableFromMs: Long = clip.fromMs,
        val availableToMs: Long = clip.toMs,
    ) {
        val fromMs: Long get() = availableFromMs.coerceAtLeast(0L)
        val toMs: Long get() = availableToMs.coerceAtLeast(fromMs)
        val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0L)

        /** A photo is not consumed by length: it fills the slot it is given, once. */
        val isStill: Boolean get() = clip.role == ProjectClip.Role.STILL
    }

    /**
     * The musical side: [beats] in the track's own time, [lengthMs] of the track, where the project starts in it
     * ([offsetMs]), the one drop the montage accents, and the track's loudness curve ([energy], one value per
     * [energyStepMs], made by [intensities]). Without a grid [fallbackBeatMs] keeps the montage working evenly.
     */
    data class Track(
        val beats: List<Long> = emptyList(),
        val lengthMs: Long = 0L,
        val offsetMs: Long = 0L,
        val activeDropMs: Long? = null,
        /** Every drop the analysis found: each one is a boundary, while [activeDropMs] is the main accent. */
        val drops: List<Long> = emptyList(),
        val energy: List<Float> = emptyList(),
        val energyStepMs: Long = 1_000L,
        val fallbackBeatMs: Long = 500L,
    )

    /** What the montage made: the new [clips], how much music they fill, and whether the sources ran out first. */
    data class Result(
        val clips: List<ProjectClip>,
        val targetMs: Long,
        val usedMs: Long,
        val exhausted: Boolean,
        val slots: Int,
        /** The result is editorially the same as the pool: saving it would only add joins, not change the film. */
        val unchanged: Boolean = false,
    )

    /** A musical slot between two cuts: its place in the project and the loudness there. */
    data class Slot(val startMs: Long, val endMs: Long, val intensity: Float) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    /**
     * The cuts of the track: from 0 (the project's start) to [targetMs], with every beat (shifted by the track's
     * offset), the active drop and the end of the track. A slot lasts a number of *actual* beats, so a tempo that
     * moves does not walk away from the picture. A tiny slot is merged into its neighbour: a 60 ms cut is a mistake,
     * not an accent.
     */
    fun slots(scenario: Scenario, dynamics: Dynamics, track: Track, targetMs: Long, seed: Long = 0L): List<Slot> {
        if (targetMs < MIN_PIECE_MS) return emptyList()
        val offset = track.offsetMs.coerceAtLeast(0L)
        val drops = (track.drops + listOfNotNull(track.activeDropMs))
            .asSequence()
            .map { it - offset }
            .filter { it > 0L && it < targetMs }
            .distinct()
            .sorted()
            .toList()
        val activeDrop = track.activeDropMs
            ?.let { it - offset }
            ?.takeIf { it > 0L && it < targetMs }
        val fallback = track.fallbackBeatMs.coerceIn(120L, 2_000L)
        val found = track.beats.asSequence()
            .map { it - offset }
            .filter { it > 0L && it < targetMs }
            .distinct()
            .sorted()
            .toList()
        // A track the analysis could not beat out is still cut evenly: the fallback grid plays the part of the beats,
        // and every slot lasts a whole number of its steps.
        val beatCuts = found.ifEmpty {
            (fallback until targetMs step fallback).map { it }.toList()
        }
        val cuts = (listOf(0L) + beatCuts + drops + listOf(targetMs))
            .distinct()
            .sorted()
        if (cuts.size < 2) return listOf(Slot(0L, targetMs, intensity(track, 0L)))
        // The indices of the real beat boundaries, so the step counts beats and not arbitrary forced cuts.
        val beatSet = beatCuts.toHashSet()
        val forcedSet = drops.toHashSet()
        val dropIndices = drops.mapNotNull { at -> cuts.indexOf(at).takeIf { it > 0 } }
        val slots = ArrayList<Slot>(cuts.size)
        var i = 0
        var guard = 0
        while (i < cuts.lastIndex && guard++ < 100_000) {
            var j = i + 1
            // A run of very short pieces is allowed as an accent, but never as the normal way of cutting: the next
            // step is made long before a fourth short piece can follow.
            val shortRun = slots.takeLastWhile { it.durationMs < MIN_REGULAR_MS }.size
            val beats = step(
                scenario, dynamics, cuts[i], targetMs, activeDrop, drops, fallback, seed, i,
                intensity(track, cuts[i]), shortRun,
            )
            // Forced cuts (a drop, a stray beat found apart from the fixed grid) count as one boundary each.
            var taken = if (cuts[j] in beatSet || cuts[j] in forcedSet) 1 else 0
            while (j < cuts.lastIndex && taken < beats) {
                j++
                if (cuts[j] in beatSet || cuts[j] in forcedSet) taken++
            }
            // Every drop is a boundary, not only the active one the pattern accents.
            dropIndices.firstOrNull { it in (i + 1) until j }?.let { j = it }
            slots += Slot(cuts[i], cuts[j], intensity(track, cuts[i]))
            i = j
        }
        return mergedTiny(slots)
    }

    /**
     * The track's loudness at [projectMs], 0…1: the curve made by [intensities], shifted by the track's offset; 0.5
     * when nothing was measured, so a silent or unread curve does not make the montage refuse to work.
     */
    fun intensity(track: Track, projectMs: Long): Float {
        if (track.energy.isEmpty()) return 0.5f
        val trackMs = (projectMs + track.offsetMs).coerceAtLeast(0L)
        val step = track.energyStepMs.coerceAtLeast(1L)
        val at = (trackMs / step).toInt().coerceIn(0, track.energy.lastIndex)
        return track.energy[at].takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0.5f
    }

    /**
     * The track's loudness curve from the wave's fine peaks (one per [bucketMs]): one value per [stepMs], normalised
     * to the loudest tenth so a quiet recording still uses the whole 0…1 range. Empty when there is no wave.
     */
    fun intensities(peaks: FloatArray, bucketMs: Long = 25L, stepMs: Long = 1_000L): List<Float> {
        if (peaks.isEmpty() || bucketMs <= 0L || stepMs <= 0L) return emptyList()
        val per = (stepMs / bucketMs).coerceAtLeast(1L).toInt()
        val bins = (peaks.size + per - 1) / per
        if (bins <= 0) return emptyList()
        val raw = FloatArray(bins)
        for (bin in 0 until bins) {
            var sum = 0.0
            var n = 0
            for (i in bin * per until minOf((bin + 1) * per, peaks.size)) {
                val p = peaks[i].takeIf { !it.isNaN() }?.coerceAtLeast(0f) ?: 0f
                sum += p.toDouble() * p
                n++
            }
            raw[bin] = if (n == 0) 0f else sqrt(sum / n).toFloat()
        }
        val sorted = raw.sorted()
        val ref = sorted[(sorted.size * 9 / 10).coerceIn(0, sorted.lastIndex)].coerceAtLeast(1e-6f)
        return raw.map { (it / ref).coerceIn(0f, 1f) }
    }

    /**
     * The whole montage: [pool] is cut into slots made by [track], each slot is filled from a source that has material
     * left, and the result keeps the source's role and measured energy. [seed] makes the shuffle, and every tie, the
     * same on every run. The track is filled from its [Track.offsetMs] to its end, or until the sources run out.
     */
    fun montage(
        scenario: Scenario,
        dynamics: Dynamics,
        pool: List<Source>,
        track: Track,
        seed: Long = 0L,
    ): Result {
        val sources = pool.filter { it.durationMs > 0L || it.isStill }
        val materialMs = sources.sumOf {
            if (it.isStill) it.clip.durationMs.coerceAtLeast(MIN_PIECE_MS) else it.durationMs
        }
        val musicMs = (track.lengthMs - track.offsetMs).coerceAtLeast(0L)
        // The music sets the length even when the sources are shorter: [Result.exhausted] then says so, instead of a
        // neat-looking result hiding that only the first seconds of the track were filled.
        val targetMs = if (musicMs > 0L) musicMs else materialMs
        if (targetMs < MIN_PIECE_MS || sources.isEmpty()) {
            return Result(emptyList(), targetMs, 0L, sources.isNotEmpty(), 0)
        }

        val slots = slots(scenario, dynamics, track, targetMs, seed)
        if (slots.isEmpty()) return Result(emptyList(), targetMs, 0L, true, 0)

        val cursors = sources.mapIndexed { index, source ->
            Cursor(
                source = source,
                atMs = source.clip.fromMs.coerceIn(source.fromMs, source.toMs),
                order = randomOrder(seed, index, sources.size),
            )
        }
        val out = ArrayList<ProjectClip>()
        var exhausted = false
        var filledSlots = 0
        for ((slotIndex, slot) in slots.withIndex()) {
            if (slot.durationMs < MIN_PIECE_MS) continue
            var left = slot.durationMs
            while (left >= MIN_PIECE_MS) {
                val candidates = cursors.filter {
                    (it.source.isStill && it.used == 0) || (!it.source.isStill && it.remainingMs >= MIN_PIECE_MS)
                }
                if (candidates.isEmpty()) {
                    exhausted = true
                    break
                }
                val cursor = choose(candidates, scenario, slot, left, slotIndex, seed)
                val take = if (cursor.source.isStill) left else minOf(left, cursor.remainingMs)
                if (take < MIN_PIECE_MS) {
                    exhausted = true
                    break
                }
                val from = cursor.atMs
                out += ProjectClip(
                    file = cursor.source.clip.file,
                    fromMs = from,
                    toMs = from + take,
                    auto = true,
                    atMs = null,
                    role = cursor.source.clip.role,
                    energy = cursor.source.clip.energy,
                )
                cursor.advance(take, slotIndex)
                left -= take
                if (left in 1 until MIN_PIECE_MS) {
                    // A tail too short for its own card: leave it unused rather than put a flash on the screen.
                    left = 0L
                }
            }
            if (exhausted) break
            filledSlots++
        }
        // Slots are editorial, joins are technical: two neighbouring pieces of one uninterrupted range are one piece.
        // This also makes «did the montage change anything?» an honest question.
        val clips = coalesced(out)
        val used = clips.sumOf { it.durationMs }
        val unchanged = editSignature(clips) == editSignature(sources.map { it.clip })
        return Result(clips, targetMs, used, exhausted || used < targetMs, filledSlots, unchanged)
    }

    /** Merge adjacent pieces of one source when their ranges touch: the cut adds nothing the eye can see. */
    private fun coalesced(clips: List<ProjectClip>): List<ProjectClip> {
        if (clips.size < 2) return clips
        val out = ArrayList<ProjectClip>(clips.size)
        for (clip in clips) {
            val prev = out.lastOrNull()
            val join = prev != null &&
                prev.role != ProjectClip.Role.STILL &&
                clip.role != ProjectClip.Role.STILL &&
                prev.file == clip.file &&
                prev.role == clip.role &&
                prev.atMs == clip.atMs &&
                prev.toMs == clip.fromMs
            if (join) out[out.lastIndex] = prev!!.copy(toMs = clip.toMs) else out += clip
        }
        return out
    }

    /** What the editor sees: a run of pieces, without the bookkeeping of auto/energy. */
    private data class EditKey(
        val file: String,
        val fromMs: Long,
        val toMs: Long,
        val role: ProjectClip.Role,
        val atMs: Long?,
    )

    private fun editSignature(clips: List<ProjectClip>): List<EditKey> =
        clips.map { EditKey(it.file, it.fromMs, it.toMs, it.role, it.atMs) }

    private val EVEN_PATTERNS = arrayOf(
        intArrayOf(8, 8, 12, 8),
        intArrayOf(8, 12, 8, 8),
        intArrayOf(12, 8, 8, 12),
    )
    private val RHYTHMIC_PATTERNS = arrayOf(
        intArrayOf(8, 4, 12, 4, 4, 8),
        intArrayOf(4, 8, 4, 4, 8, 12),
        intArrayOf(8, 4, 4, 2, 4, 8),
    )
    private val CALM_PATTERN = intArrayOf(8, 8, 12, 8)
    private val BUILD_PATTERN = intArrayOf(8, 4, 4, 2, 2)
    private val PEAK_PATTERN = intArrayOf(4, 2, 2, 1, 1, 4, 8)
    private val MOOD_PATTERNS = arrayOf(
        intArrayOf(12, 8, 16, 12),
        intArrayOf(8, 12, 12, 16),
    )
    private val SHUFFLE_PATTERNS = arrayOf(
        intArrayOf(8, 4, 8, 4, 4, 8),
        intArrayOf(12, 8, 16, 12),
        intArrayOf(8, 12, 8, 8),
        intArrayOf(4, 8, 4, 12, 4),
    )

    /**
     * The number of beats a slot should last. The old rule asked for one fixed count per dynamic (RHYTHMIC was always
     * two), which turned a three-minute track into the same mechanical flicker. A slot now takes its place in a short
     * pattern, the pattern follows the loudness and the nearest drop, and a run of quick pieces forces a long one.
     */
    private fun step(
        scenario: Scenario,
        dynamics: Dynamics,
        atMs: Long,
        targetMs: Long,
        activeDrop: Long?,
        drops: List<Long>,
        fallbackBeatMs: Long,
        seed: Long,
        slotIndex: Int,
        intensity: Float,
        shortRun: Int,
    ): Int {
        val share = if (targetMs <= 0L) 0.0 else atMs.toDouble() / targetMs
        val nearDrop = drops.any { drop ->
            abs(atMs - drop) <= 2L * fallbackBeatMs ||
                (drop > atMs && drop - atMs <= 4L * fallbackBeatMs)
        }
        val afterDrop = drops.any { it in ((atMs - 2L * fallbackBeatMs)..atMs) }
        val phrase = (slotIndex / 4).coerceAtLeast(0)
        val pattern = when (dynamics) {
            Dynamics.EVEN -> EVEN_PATTERNS[phrase % EVEN_PATTERNS.size]
            Dynamics.RHYTHMIC -> RHYTHMIC_PATTERNS[phrase % RHYTHMIC_PATTERNS.size]
            Dynamics.MOOD -> MOOD_PATTERNS[phrase % MOOD_PATTERNS.size]
            Dynamics.DYNAMIC -> when {
                nearDrop && atMs < (activeDrop ?: Long.MAX_VALUE) -> PEAK_PATTERN
                afterDrop -> CALM_PATTERN
                intensity < 0.30f -> CALM_PATTERN
                intensity > 0.82f -> BUILD_PATTERN
                share < 0.18 || share > 0.86 -> CALM_PATTERN
                else -> RHYTHMIC_PATTERNS[phrase % RHYTHMIC_PATTERNS.size]
            }
            Dynamics.SHUFFLE -> {
                val pick = Random(seed xor (phrase.toLong() * 0x9E3779B9L)).nextInt(SHUFFLE_PATTERNS.size)
                SHUFFLE_PATTERNS[pick]
            }
        }
        var beats = pattern[(slotIndex + phrase) % pattern.size]
        if (shortRun >= MAX_SHORT_RUN) beats = maxOf(beats, 8)
        // A scenario is a bias, not a cage: it shifts the pattern, while the limits below still apply.
        beats = when (scenario) {
            Scenario.MUSIC -> beats
            Scenario.ACTION -> (beats * 3 / 4).coerceAtLeast(2)
            Scenario.VLOG -> (beats * 3 / 2).coerceAtMost(16)
            Scenario.SLIDESHOW -> (beats * 2).coerceAtMost(16)
            Scenario.TIMELAPSE -> (beats / 2).coerceAtLeast(2)
            Scenario.TRAVEL, Scenario.CUSTOM -> beats
        }
        // The drop itself may be an accent of one beat; everywhere else a plan lasts at least the regular minimum.
        val floorMs = if (nearDrop) MIN_PIECE_MS else MIN_REGULAR_MS
        val minBeats = ((floorMs + fallbackBeatMs - 1) / fallbackBeatMs).toInt().coerceAtLeast(1)
        val maxBeats = (MAX_PIECE_MS / fallbackBeatMs).toInt().coerceAtLeast(minBeats)
        return beats.coerceIn(minBeats, maxBeats)
    }

    /** One source as it is consumed: where its cursor stands, how many pieces it gave, and its shuffle order. */
    private class Cursor(val source: Source, var atMs: Long, val order: Long) {
        var used: Int = 0
        var lastSlot: Int = -1
        val remainingMs: Long get() = if (source.isStill) Long.MAX_VALUE else (source.toMs - atMs).coerceAtLeast(0L)

        fun advance(ms: Long, slot: Int) {
            if (!source.isStill) atMs += ms
            used++
            lastSlot = slot
        }
    }

    /**
     * Which source fills the next piece: the one closest to the loudness the slot wants, with a growing penalty for a
     * source used again and again (so four long takes really take turns); outside the energy-led scenarios, the least
     * used source comes first. The seed only breaks ties (and orders a shuffle), so a rerun is reproducible.
     */
    private fun choose(
        candidates: List<Cursor>,
        scenario: Scenario,
        slot: Slot,
        leftMs: Long,
        slotIndex: Int,
        seed: Long,
    ): Cursor {
        val energyLed = scenario == Scenario.MUSIC || scenario == Scenario.ACTION
        val want = if (!energyLed) 0.5f else 0.25f + 0.75f * slot.intensity
        return candidates.minWithOrNull(
            compareBy<Cursor> { it.lastSlot == slotIndex }
                .thenBy {
                    if (it.remainingMs < leftMs && !it.source.isStill && it.remainingMs > MIN_PIECE_MS) 0.5 else 0.0
                }
                .thenBy {
                    if (energyLed) {
                        val e = it.source.clip.energy?.takeIf { v -> !v.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.5
                        abs(e - want) + it.used * 1.0
                    } else {
                        it.used * 1.0
                    }
                }
                .thenBy { tie(seed, slotIndex, it.order) }
                .thenBy { it.order },
        )!!
    }

    /** A stable pseudo-random tie-break: the same project and slot choose the same source on every run. */
    private fun tie(seed: Long, slot: Int, order: Long): Long {
        var h = seed xor (slot.toLong() * -0x61c8864680b583ebL) xor order
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        return h
    }

    /** A seeded starting order, so [Dynamics.SHUFFLE] is a different cut each seed but always the same for one seed. */
    private fun randomOrder(seed: Long, index: Int, size: Int): Long {
        val r = Random(seed xor (index.toLong() * 0x9E3779B9L))
        return (r.nextLong() ushr 1) % size.coerceAtLeast(1) + index.toLong() * 1_000L
    }

    /** Slots shorter than [MIN_PIECE_MS] join their neighbour: a stray beat must not make a flash of a piece. */
    private fun mergedTiny(slots: List<Slot>): List<Slot> {
        if (slots.size < 2) return slots
        val out = ArrayList<Slot>(slots.size)
        // A tiny piece at the very start has no previous to join, so it waits for the first real slot and becomes its
        // beginning. Any other tiny piece simply extends the previous slot.
        var pending: Slot? = null
        for (slot in slots) {
            if (slot.durationMs < MIN_PIECE_MS) {
                if (out.isNotEmpty()) {
                    val prev = out.removeAt(out.lastIndex)
                    out += prev.copy(endMs = slot.endMs, intensity = maxOf(prev.intensity, slot.intensity))
                } else {
                    pending = pending?.let { it.copy(endMs = slot.endMs) } ?: slot
                }
            } else {
                val start = pending
                out += if (start == null) slot else slot.copy(
                    startMs = start.startMs,
                    intensity = maxOf(start.intensity, slot.intensity),
                )
                pending = null
            }
        }
        return if (out.isEmpty()) listOfNotNull(pending) else out
    }
}