package app.goultra.transfer.ui

/**
 * Where the *music* stands in the project's own time (P3): the picture may stand still — a piece waiting on the camera's
 * Wi-Fi — while the music plays on, and the click must keep time with what is heard, not with what is drawn. The player's
 * own reading is told on every tick of the project's clock, together with the monotonic moment it was taken at; between
 * the ticks the time is counted from that moment, so a beat that falls between two ticks is still clicked on itself.
 *
 * Pure arithmetic on ms — no player, no Compose — so the tests can hold it to its word.
 */
internal class MusicClock {
    /**
     * One immutable reading, swapped as a whole: the player's tick is written on the main thread while the metronome
     * reads it on its own audio thread. Two separate fields could be seen half-updated (a new position with an old
     * monotonic moment), which puts every extrapolated click off by the difference.
     */
    private data class Reading(val projectMs: Long?, val atMs: Long, val playing: Boolean, val revision: Long)

    internal data class Cursor(val positionMs: Long, val revision: Long, val atMs: Long) {
        fun after(previous: Cursor?): Cursor {
            if (previous == null || previous.revision != revision) return this
            val elapsed = (atMs - previous.atMs).coerceAtLeast(0L)
            val predicted = previous.positionMs + elapsed
            val adjustment = (positionMs - predicted).coerceIn(-elapsed / 4, elapsed / 4)
            return copy(positionMs = predicted + adjustment)
        }
    }

    @Volatile
    private var reading = Reading(null, 0L, false, 0L)

    /** The music's moment at [elapsedMs] of the monotonic clock; null until the player has played at all. */
    fun now(elapsedMs: Long): Long? {
        val seen = reading
        val base = seen.projectMs ?: return null
        return if (seen.playing) base + (elapsedMs - seen.atMs).coerceAtLeast(0L) else base
    }

    /** A beat can be scheduled only against a running audio clock. */
    fun runningAt(elapsedMs: Long): Long? {
        val seen = reading
        if (!seen.playing) return null
        return seen.projectMs?.plus((elapsedMs - seen.atMs).coerceAtLeast(0L))
    }

    fun cursorAt(elapsedMs: Long): Cursor? {
        val seen = reading
        if (!seen.playing) return null
        return seen.projectMs?.let { Cursor(it + (elapsedMs - seen.atMs).coerceAtLeast(0L), seen.revision, elapsedMs) }
    }

    /** An explicit seek lets the visual cursor move backwards immediately. */
    fun discontinuity() {
        reading = Reading(null, 0L, false, reading.revision + 1)
    }

    /**
     * The player's own reading: [projectMs] at [elapsedMs], playing or not. Null — nothing is heard (the track is still
     * loading, or the film has not reached its start yet).
     */
    fun told(projectMs: Long?, elapsedMs: Long, playing: Boolean) {
        reading = Reading(projectMs, elapsedMs, playing, reading.revision)
    }
}
