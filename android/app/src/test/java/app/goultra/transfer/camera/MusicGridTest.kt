package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid edited by hand ([MusicGrid]): shifting it, changing its pace, a tap tempo, and its tempo in beats a minute. */
class MusicGridTest {
    @Test
    fun `a rigid grid estimates a single tempo despite millisecond rounding`() {
        val grid = MusicGrid.constantAt(emptyList(), 240_000L, 94.5, 318L)
        assertEquals(94.5, MusicGrid.rigidBpm(grid)!!, 0.001)
        assertNull(MusicGrid.rigidBpm(grid.toMutableList().apply { this[100] += 5L }))
    }
    @Test
    fun `forcing an exact tempo keeps the phase at the playhead and covers the whole track`() {
        val grid = MusicGrid.constantAt(listOf(100L, 600L, 1000L, 1450L, 1950L), 4000, 160.0, 1420)
        assertEquals(325L, grid.first())
        assertTrue(1450L in grid)
        assertTrue(grid.zipWithNext { a, b -> b - a }.all { it == 375L })
        assertEquals(3700L, grid.last())
        assertFalse(MusicGrid.isVariable(grid))
    }

    @Test
    fun `tempo at the cursor follows sections and ignores an omitted mark`() {
        val beats = (0..12).map { it * 500L }.filterNot { it == 2500L } +
            (1..12).map { 6000L + it * 375L }
        assertEquals(120.0, MusicGrid.bpmAt(beats, 2500), 0.001)
        assertEquals(160.0, MusicGrid.bpmAt(beats, 9000), 0.001)
    }
    @Test
    fun `irregular placements inside a steady section do not change BPM`() {
        val beats = (0..96).map { beat -> beat * 414L + listOf(0L, 35L, -28L, 12L)[beat % 4] }
        assertFalse(MusicGrid.isVariable(beats))
        assertEquals(60_000.0 / 414, MusicGrid.bpm(beats), 0.1)
    }

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

    @Test
    fun `a tempo that moves is a range, and it is called variable`() {
        // Twenty beats whose gaps shrink from 600 ms to 480: 100 BPM at the start, 125 by the end.
        val moving = ArrayList<Long>()
        var at = 0L
        var gap = 600L
        repeat(20) {
            moving += at
            at += gap
            if (gap > 480L) gap -= 10L
        }
        assertTrue("a moving tempo is variable", MusicGrid.isVariable(moving))
        val (slow, fast) = MusicGrid.tempoRange(moving)!!
        assertEquals(100.0, slow, 2.0)
        assertEquals(125.0, fast, 2.0)

        // A steady 120 with the beats a few ms off the ideal is not a moving tempo.
        val steady = (0 until 13).map { (it * 500L + ((it % 3) - 1) * 4L) }
        assertFalse("a steady tempo is not variable", MusicGrid.isVariable(steady))
        val (from, to) = MusicGrid.tempoRange(steady)!!
        assertEquals(120.0, (from + to) / 2, 2.0)
        assertTrue("a steady tempo is one number: $from–$to", to - from < 5.0)

        // Too few beats say nothing at all.
        assertNull(MusicGrid.tempoRange(listOf(0L, 500L, 1_000L)))
        assertFalse(MusicGrid.isVariable(emptyList()))
    }
}
