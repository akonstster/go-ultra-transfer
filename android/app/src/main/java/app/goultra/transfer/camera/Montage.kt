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
    const val MIN_PIECE_MS = 250L

    /** The longest slot the beat-count rule may ask for: a slow track still gets a cut inside a long stretch. */
    const val MAX_PIECE_MS = 16_000L

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
        val drop = track.activeDropMs
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
        // and every slot lasts the same number of its steps.
        val beatCuts = found.ifEmpty {
            (fallback until targetMs step fallback).map { it }.toList()
        }
        val cuts = (listOf(0L) + beatCuts + listOfNotNull(drop) + listOf(targetMs))
            .distinct()
            .sorted()
        if (cuts.size < 2) return listOf(Slot(0L, targetMs, intensity(track, 0L)))
        // The indices of the real beat boundaries, so the step counts beats and not arbitrary forced cuts.
        val beatSet = beatCuts.toHashSet()
        val dropIndex = drop?.let { cuts.indexOf(it) }?.takeIf { it > 0 } ?: -1
        val slots = ArrayList<Slot>(cuts.size)
        var i = 0
        var guard = 0
        while (i < cuts.lastIndex && guard++ < 100_000) {
            var j = i + 1
            val beats = step(scenario, dynamics, cuts[i], targetMs, drop, fallback, seed, i)
            // Forced cuts (a drop, a stray beat found apart from the fixed grid) count as one boundary each.
            var taken = if (cuts[j] in beatSet || cuts[j] == drop) 1 else 0
            while (j < cuts.lastIndex && taken < beats) {
                j++
                if (cuts[j] in beatSet || cuts[j] == drop) taken++
            }
            if (dropIndex in (i + 1) until j) j = dropIndex
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
        val used = out.sumOf { it.durationMs }
        return Result(out, targetMs, used, exhausted || used < targetMs, filledSlots)
    }

    /** The number of beats a slot should last under the chosen scenario and dynamics. */
    private fun step(
        scenario: Scenario,
        dynamics: Dynamics,
        atMs: Long,
        targetMs: Long,
        drop: Long?,
        fallbackBeatMs: Long,
        seed: Long,
        slotIndex: Int,
    ): Int {
        val share = if (targetMs <= 0L) 0.0 else atMs.toDouble() / targetMs
        var beats = when (dynamics) {
            Dynamics.EVEN -> 4
            Dynamics.RHYTHMIC -> 2
            Dynamics.MOOD -> if (share < 0.2 || share > 0.82) 8 else 4
            Dynamics.DYNAMIC -> when {
                drop != null && abs(atMs - drop) <= 2L * fallbackBeatMs -> 1
                share < 0.18 -> 4
                share > 0.86 -> 4
                else -> 2
            }
            Dynamics.SHUFFLE -> {
                val r = Random(seed xor (slotIndex.toLong() * 0x9E3779B9L)).nextInt(100)
                when {
                    r < 25 -> 2
                    r < 80 -> 4
                    else -> 8
                }
            }
        }
        // A scenario is a bias, not a cage: it makes the cutting shorter or longer by at most one step.
        beats = when (scenario) {
            Scenario.MUSIC, Scenario.ACTION -> if (beats > 1) beats - 1 else beats
            Scenario.VLOG -> (beats + 2).coerceAtMost(8)
            Scenario.SLIDESHOW -> beats.coerceAtLeast(4)
            Scenario.TIMELAPSE -> beats.coerceAtMost(2).coerceAtLeast(1)
            Scenario.TRAVEL, Scenario.CUSTOM -> beats
        }
        // One beat is the smallest musical unit; a hand grid with very close beats still gets a visible piece.
        val minBeats = ((MIN_PIECE_MS + fallbackBeatMs - 1) / fallbackBeatMs).toInt().coerceAtLeast(1)
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