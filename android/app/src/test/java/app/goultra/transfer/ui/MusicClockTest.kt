package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the *music* stands ([MusicClock]) — the clock the click keeps time with. */
class MusicClockTest {
    @Test
    fun `the cursor holds through small clock corrections but obeys a backward seek`() {
        val clock = MusicClock()
        clock.told(1000, 0, true)
        val drawn = clock.cursorAt(100)!!
        clock.told(1080, 100, true)
        assertEquals(1080L, clock.runningAt(100))
        assertEquals(drawn, clock.cursorAt(100)!!.after(drawn))
        assertEquals(1130L, clock.cursorAt(140)!!.after(drawn).positionMs)
        clock.discontinuity()
        clock.told(900, 150, true)
        assertEquals(900L, clock.cursorAt(150)!!.after(drawn).positionMs)
    }

    @Test
    fun `the music's time is counted on between the ticks`() {
        val clock = MusicClock()
        assertNull("nothing heard yet", clock.now(1_000L))
        clock.told(5_000L, elapsedMs = 1_000L, playing = true)
        assertEquals(5_000L, clock.now(1_000L)!!)
        // Fifty ms later the project has not ticked, but the music has played on: the beat between must not wait for it.
        assertEquals(5_050L, clock.now(1_050L)!!)
        assertEquals(5_050L, clock.runningAt(1_050L)!!)
        assertEquals(5_200L, clock.now(1_200L)!!)
    }

    @Test
    fun `a paused music stands still`() {
        val clock = MusicClock()
        clock.told(5_000L, elapsedMs = 1_000L, playing = false)
        assertEquals(5_000L, clock.now(3_000L)!!)
        assertNull(clock.runningAt(3_000L))
    }

    @Test
    fun `clicks wait for a running audio track`() {
        val clock = MusicClock()
        clock.told(5_000L, elapsedMs = 1_000L, playing = true)
        clock.told(null, elapsedMs = 1_100L, playing = false)
        assertNull(clock.now(1_200L))
        assertNull(clock.runningAt(1_200L))
        // And it is picked up again when the sound comes back.
        clock.told(5_100L, elapsedMs = 1_200L, playing = true)
        assertEquals(5_150L, clock.now(1_250L)!!)
        assertEquals(5_150L, clock.runningAt(1_250L)!!)
    }
}
