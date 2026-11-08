package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the click sounds ([MetronomeSchedule]): one click a beat, on the beat rather than up to a tick after it, none for
 * the beats a jump of the clock skipped, and none at all while the picture stands still.
 */
class MetronomeScheduleTest {
    private val beats = listOf(500L, 1_000L, 1_500L, 2_000L)

    @Test
    fun `a beat is clicked once, and it is on its way before it is heard`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 10_000L, playing = true)
        // The beat at 500 is 440 ms away: nothing is due (the lead is 60 ms).
        assertNull(s.due(10_000L, leadMs = 60L, latencyMs = 40L))
        // 440 ms in — the phone's own 40 ms taken off — the click must go now to be *heard* on the beat.
        val due = s.due(10_440L, leadMs = 60L, latencyMs = 40L)!!
        assertEquals(500L, due.beat)
        assertEquals(20L, due.inMs)
        // It is on its way: the next ticks ask for nothing, and the beat is never clicked twice.
        assertNull(s.due(10_450L, leadMs = 60L, latencyMs = 40L))
        assertNull(s.due(10_490L, leadMs = 60L, latencyMs = 40L))
        s.fired(due.beat)
        assertNull(s.due(10_500L, leadMs = 60L, latencyMs = 40L))
        // The next beat, a half-second later.
        val next = s.due(10_960L, leadMs = 60L, latencyMs = 40L)!!
        assertEquals(1_000L, next.beat)
    }

    @Test
    fun `a beat close by is waited for, not clicked on the tick`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 1_000L, playing = true)
        // 200 ms before the beat: still nothing.
        assertNull(s.due(1_300L, leadMs = 60L, latencyMs = 0L))
        // 40 ms before it: the click waits exactly those 40 ms, so it sounds on the beat itself.
        val due = s.due(1_460L, leadMs = 60L, latencyMs = 0L)!!
        assertEquals(500L, due.beat)
        assertEquals(40L, due.inMs)
    }

    @Test
    fun `the phone's own delay is taken off the beat`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 0L, playing = true)
        // With 60 ms of the phone's own delay the beat at 500 must be queued at 440 — not at 500.
        assertNull(s.due(400L, leadMs = 20L, latencyMs = 60L))
        val due = s.due(440L, leadMs = 20L, latencyMs = 60L)!!
        assertEquals(500L, due.beat)
        assertEquals(0L, due.inMs)
    }

    @Test
    fun `a jump of the clock takes the beats it skipped with it`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 10_000L, playing = true)
        // A tick 100 ms on: the clock agrees with its own reading, so nothing is wrong.
        assertFalse(s.told(100L, atMs = 10_100L, playing = true))
        // A tap on the timeline: the project jumps to 1 200 while the phone's own reading says 150 ms have passed.
        assertTrue(s.told(1_200L, atMs = 10_150L, playing = true))
        // The beats the jump passed are not heard...
        assertNull(s.due(10_290L, leadMs = 0L, latencyMs = 0L))
        // ...and the next one is waited for, not skipped over.
        val due = s.due(10_440L, leadMs = 60L, latencyMs = 0L)!!
        assertEquals(1_500L, due.beat)
        assertEquals(10L, due.inMs)
    }

    @Test
    fun `nothing is clicked while the picture stands still`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 5_000L, playing = true)
        s.told(1_000L, atMs = 6_000L, playing = false)
        assertNull(s.due(6_500L))
        // Played on, the beat ahead is waited for again — the pause left nothing behind it.
        s.told(1_000L, atMs = 7_000L, playing = true)
        val due = s.due(7_460L, leadMs = 60L, latencyMs = 0L)!!
        assertEquals(1_500L, due.beat)
        assertEquals(40L, due.inMs)
    }

    @Test
    fun `the music's own offset moves the beats with the track`() {
        // The track starts 400 ms before the project: a beat of the track stands 400 ms earlier in the project.
        val s = MetronomeSchedule(listOf(400L, 900L, 1_400L), offsetMs = 400L)
        s.told(0L, atMs = 0L, playing = true)
        val first = s.due(0L, leadMs = 60L, latencyMs = 0L)!!
        assertEquals(400L, first.beat)
        assertEquals(0L, first.inMs)
        s.fired(first.beat)
        // The clock goes on: the next beat stands 500 ms into the project.
        assertFalse(s.told(300L, atMs = 300L, playing = true))
        assertNull(s.due(300L, leadMs = 60L, latencyMs = 0L))
        assertFalse(s.told(500L, atMs = 500L, playing = true))
        val second = s.due(500L, leadMs = 60L, latencyMs = 0L)!!
        assertEquals(900L, second.beat)
        assertEquals(0L, second.inMs)
    }

    @Test
    fun `the ear's shift moves the click either way, with no dead end`() {
        // What the phone adds is compensated apart from the ear's shift. Across the whole ±120 ms the moment of the click
        // must follow every five-ms step; folding the two numbers together and cutting the result at zero lost the end.
        var previous: Long? = null
        for (shift in -120L..120L step 5) {
            val expected = 1_000L - 50L + shift
            val now = expected - 50L
            val s = MetronomeSchedule(listOf(1_000L), offsetMs = 0L)
            s.told(now, atMs = now, playing = true)
            val due = s.due(now, leadMs = 60L, latencyMs = 50L, shiftMs = shift)!!
            val heardAt = now + due.inMs
            assertEquals("shift $shift", expected, heardAt)
            previous?.let { assertEquals("shift $shift must move by five ms", 5L, heardAt - it) }
            previous = heardAt
        }
    }

    @Test
    fun `a tick that comes late does not lose a beat`() {
        val s = MetronomeSchedule(listOf(500L, 1_000L), offsetMs = 0L)
        s.told(0L, atMs = 0L, playing = true)
        var clicked = 0
        // The project's clock is told only every 120 ms, twice as slowly as usual: every beat must still be clicked.
        for (at in 0L..1_080L step 120L) {
            val due = s.due(at, leadMs = 60L, latencyMs = 50L)
            if (due != null) {
                s.fired(due.beat)
                clicked++
            }
        }
        assertEquals(2, clicked)
        assertEquals(Long.MIN_VALUE, s.lateBeat)
    }

    @Test
    fun `a waiting click is moved by the ear, not clicked twice`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 0L, playing = true)
        // The beat at 500 is queued at 450: at 400 it is fifty ms away.
        val first = s.due(400L, leadMs = 60L, latencyMs = 50L, shiftMs = 0L)!!
        assertEquals(50L, first.inMs)
        // The ear asks for it ten ms later, then twenty: the same beat is put back into the plan at its new moment.
        s.released()
        assertNull(s.due(400L, leadMs = 60L, latencyMs = 50L, shiftMs = 20L))
        val moved = s.due(420L, leadMs = 60L, latencyMs = 50L, shiftMs = 20L)!!
        assertEquals(500L, moved.beat)
        assertEquals(50L, moved.inMs)
        s.fired(moved.beat)
        assertNull(s.due(420L, leadMs = 60L, latencyMs = 50L, shiftMs = 20L))
    }

    @Test
    fun `a beat missed by more than the late limit is logged, not clicked into the next one`() {
        val s = MetronomeSchedule(listOf(500L, 1_000L), offsetMs = 0L)
        s.told(0L, atMs = 0L, playing = true)
        assertNull(s.due(620L, leadMs = 0L, latencyMs = 50L))
        assertEquals(500L, s.lateBeat)
        assertEquals(170L, s.lateByMs)
    }

    @Test
    fun `a stutter of the clock is not a jump`() {
        val s = MetronomeSchedule(beats, offsetMs = 0L)
        s.told(0L, atMs = 0L, playing = true)
        // A movement of 120 ms — a late tick, not a seek — keeps the plan.
        assertFalse(s.told(120L, atMs = 0L, playing = true))
        // Past a quarter of a second the clock has really moved: the clicks it promised are taken back.
        assertTrue(s.told(500L, atMs = 0L, playing = true))
    }
}
