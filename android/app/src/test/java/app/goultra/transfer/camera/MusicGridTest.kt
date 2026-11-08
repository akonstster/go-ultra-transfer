package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid edited by hand ([MusicGrid]): shifting it, changing its pace, a tap tempo, and its tempo in beats a minute. */
class MusicGridTest {
    @Test
    fun `a shifted grid never goes before the start`() {
        assertEquals(listOf(400L), MusicGrid.shifted(listOf(500L, 1000L), byMs = -600L))
        assertEquals(listOf(510L, 1010L), MusicGrid.shifted(listOf(500L, 1000L), byMs = 10L))
        assertEquals(emptyList<Long>(), MusicGrid.shifted(emptyList(), byMs = 10L))
    }

    @Test
    fun `a doubled tempo halves the gaps and a halved one doubles them`() {
        // 120 BPM (500 ms a beat) becomes 240: a beat every 250 ms, on to the track's end.
        val faster = MusicGrid.stretched(listOf(0L, 500L, 1000L, 1500L), factor = 0.5, lengthMs = 2000L)
        assertEquals(listOf(0L, 250L, 500L, 750L, 1000L, 1250L, 1500L, 1750L), faster)
        val slower = MusicGrid.stretched(listOf(0L, 500L, 1000L), factor = 2.0, lengthMs = 4000L)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), slower)
    }

    @Test
    fun `a slower grid still covers the whole track`() {
        // Halved: three beats become two, and the rest of the track is filled with the same gap of two seconds.
        val slow = MusicGrid.stretched(listOf(0L, 1000L, 2000L), factor = 1.5, lengthMs = 5000L)
        assertEquals(listOf(0L, 1500L, 3000L, 4500L), slow)
    }

    @Test
    fun `taps give the beat, and the grid starts at the track's own first beat`() {
        val taps = listOf(1000L, 1500L, 2002L, 2499L)
        assertEquals(listOf(250L, 750L, 1250L, 1750L), MusicGrid.fromTap(250L, taps, lengthMs = 2000L))
        // One tap is nothing; taps faster than a tenth of a second are slips.
        assertTrue(MusicGrid.fromTap(0L, listOf(100L), 2000L).isEmpty())
        assertTrue(MusicGrid.fromTap(0L, listOf(100L, 150L, 200L), 2000L).isEmpty())
    }

    @Test
    fun `the tempo of a grid is the middle gap turned into beats a minute`() {
        assertEquals(120.0, MusicGrid.bpm(listOf(0L, 500L, 1000L, 1500L)), 0.01)
        assertEquals(0.0, MusicGrid.bpm(listOf(0L)), 0.0)
        assertEquals(0.0, MusicGrid.bpm(emptyList()), 0.0)
    }
}