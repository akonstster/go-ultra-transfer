package app.goultra.transfer.ui

import kotlin.math.abs

/**
 * How far ahead of its beat a click is put on the queue at most, ms. The clock ticks 50 ms apart, so this is not about
 * the tick: it is the room the scheduler has to be late. A 60 ms lead gave the main thread only one tick of warning,
 * and a stalled frame made the click miss its beat and be dropped. Half a second is armed once and then slept on by the
 * scheduler itself; a seek or a pause still takes it back before it sounds.
 */
internal const val CLICK_LEAD_MS = 500L

/** How late a click may still sound, ms: past this the beat is let go and not clicked into the next one. */
internal const val CLICK_LATE_MS = 120L

/**
 * How far the clock may stand from where it was expected, ms, before it counts as a jump (a seek, another piece, a
 * pause) rather than a stutter. A tick a little late is normal; only a real move takes back the clicks promised.
 */
internal const val CLICK_JUMP_MS = 250L

/** What the phone adds between a click put on the queue and being heard, ms, when the phone does not say. */
internal const val CLICK_LATENCY_MS = 30L

/**
 * When the click sounds (P3, `memory-bank/projects-plan.md`): every beat of the grid, exactly once, on the project's own
 * clock — the very number the red line is drawn from. The clock is told on every tick together with the monotonic reading
 * it was taken at, and the time between two ticks is counted from that reading, so a beat that falls between the ticks is
 * still clicked on itself rather than up to a tick later. The click is put on the queue [latency] before its beat, because
 * that is how long the phone takes to sound it: the click is *heard* on the line, not merely queued on it.
 *
 * A jump of the clock (a seek, another piece, a pause) starts the counting over and lets the beats it skipped go: a click
 * somewhere else in the track is worse than no click at all. Pure arithmetic on ms — no Compose, no player — so the tests
 * can hold it to its word.
 */
internal class MetronomeSchedule(
    private val beats: List<Long>,
    /** The music's own offset in the project: a beat of the track stands at that moment of the project less this. */
    val offsetMs: Long,
) {
    /** A click that is due: the beat (a track moment) and how long until it must be put on the queue (0 — this instant). */
    class Due(val beat: Long, val inMs: Long)

    private var baseMs = 0L
    private var baseAt = 0L
    private var playing = false

    /** The beat last given to the sound, and the one a click is already on its way for. */
    private var last = Long.MIN_VALUE
    private var armed = Long.MIN_VALUE

    /**
     * The latest beat let go because it stood too far in the past, and by how much — for the app's log. Only the latest is
     * kept: starting in the middle of a track would otherwise report every beat before it.
     */
    var lateBeat = Long.MIN_VALUE
        private set
    var lateByMs = 0L
        private set

    /** Where the project stands at [atMs] of the monotonic clock: what was told, plus what has passed since (a clock keeps time between its ticks). */
    fun now(atMs: Long): Long = if (!playing) baseMs else baseMs + (atMs - baseAt).coerceAtLeast(0L)

    /**
     * The clock's own reading at [atMs]: [projectMs], playing or not. True when the clock jumped or stopped — a seek,
     * another piece, a pause — and everything the click promised is forgotten with it.
     */
    fun told(projectMs: Long, atMs: Long, playing: Boolean): Boolean {
        val moved = abs(projectMs - now(atMs)) > CLICK_JUMP_MS
        val stopped = !playing || !this.playing
        baseMs = projectMs
        baseAt = atMs
        this.playing = playing
        if (moved || stopped) {
            last = Long.MIN_VALUE
            armed = Long.MIN_VALUE
        }
        return moved || stopped
    }

    /**
     * The click to put on the queue now: the first beat due within [leadMs] of the clock and not yet sounded, with how far
     * off it is (0 or less — this instant). Null when the next beat is further away, or already on its way.
     *
     * [latencyMs] is what the phone adds between the queue and the ear: it moves the click *earlier*, so it is heard on
     * the beat. [shiftMs] is the ear's own correction and moves the click either way — positive later, negative earlier —
     * without ever being folded into the latency or cut off at zero. A beat already gone by more than [CLICK_LATE_MS] is
     * let go (and kept in [lateBeat]/[lateByMs] for the log) rather than clicked into the next one.
     */
    fun due(
        atMs: Long,
        leadMs: Long = CLICK_LEAD_MS,
        latencyMs: Long = CLICK_LATENCY_MS,
        shiftMs: Long = 0L,
    ): Due? {
        if (!playing) return null
        val now = now(atMs)
        val until = now + leadMs
        for (beat in beats) {
            if (beat <= last) continue
            val whenMs = beat - offsetMs - latencyMs + shiftMs
            // The beats are in order: the first one beyond the lead means nothing is due yet.
            if (whenMs > until) return null
            if (beat == armed) return null
            // Gone by — the phone stood still for a moment. The late limit is measured against the beat the ear hears,
            // not against the shifted queue moment: with a −120 ms nudge a scheduler 130 ms late had a click that would
            // still sound on the beat, and it was thrown away only because the correction had moved the target.
            if (whenMs < now - CLICK_LATE_MS) {
                val heardLate = now + latencyMs - (beat - offsetMs)
                if (heardLate > CLICK_LATE_MS) {
                    last = beat
                    lateBeat = beat
                    lateByMs = now - whenMs
                    continue
                }
                // Best effort: the exact shifted moment is gone, but the musical beat is not. The scheduler has no
                // useful wait left here, so the click is due this instant.
                armed = beat
                return Due(beat, 0L)
            }
            armed = beat
            return Due(beat, whenMs - now)
        }
        return null
    }

    /** The click is on its way: that beat is done, and it is never asked for again. */
    fun fired(beat: Long) {
        last = beat
        armed = Long.MIN_VALUE
    }

    /** The click promised for a beat was called off (a pause, a jump): the beat may be asked for again. */
    fun released() {
        armed = Long.MIN_VALUE
    }
}
