package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The beat detector on sound made here: clicks at a known tempo and phase, over a hum, and noise without a beat. */
class BeatGridTest {
    private val rate = 22050

    /** A drum-like hit every beat from [firstMs], over a quiet steady tone, for [seconds]. */
    private fun clicks(bpm: Double, firstMs: Long, seconds: Int, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(rate * seconds) { i -> (0.05 * sin(2 * PI * 220 * i / rate)).toFloat() }
        val period = 60.0 / bpm * rate
        var at = firstMs / 1000.0 * rate
        while (at < out.size) {
            val start = at.toInt()
            for (k in 0 until 1500) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * 0.8 * exp(-k / 250.0)).toFloat()
            }
            at += period
        }
        return out
    }

    @Test
    fun `a steady beat is found with its tempo and its first beat`() {
        for ((bpm, first) in listOf(120.0 to 230L, 96.0 to 100L, 140.0 to 400L)) {
            val g = BeatGrid.detect(clicks(bpm, first, 20), rate)
            assertNotNull(g)
            assertEquals("tempo for $bpm", bpm, g!!.bpm, 1.5)
            // On the grid: the found first beat is one of the real ones, within a frame or so.
            val period = 60_000.0 / bpm
            val off = ((g.offsetMs - first) % period + period) % period
            assertTrue("phase for $bpm: ${g.offsetMs} vs $first", off < 35 || period - off < 35)
        }
    }

    @Test
    fun `the beats cover the piece one period apart`() {
        val g = BeatGrid.detect(clicks(120.0, 230, 20), rate)!!
        assertTrue(g.beats.size in 38..40)
        assertTrue(g.beats.zipWithNext { a, b -> b - a }.all { abs(it - 500) <= 3 })
    }

    @Test
    fun `noise has no beat to be sure of`() {
        val rnd = Random(7)
        val noise = FloatArray(rate * 20) { (rnd.nextFloat() * 2 - 1) * 0.5f }
        val beat = BeatGrid.detect(clicks(120.0, 230, 20), rate)!!.confidence
        val none = BeatGrid.detect(noise, rate)?.confidence ?: 0.0
        assertTrue("beat $beat vs noise $none", none < beat)
    }

    @Test
    fun `silence and a scrap of sound give no grid`() {
        assertNull(BeatGrid.detect(FloatArray(rate * 10), rate))
        assertNull(BeatGrid.detect(clicks(120.0, 0, 1), rate))
    }

    @Test
    fun `a cut snaps to the nearest beat`() {
        val g = BeatGrid.Grid(120.0, 0, listOf(0, 500, 1000, 1500), 1.0)
        assertEquals(500L, BeatGrid.nearest(g, 620))
        assertEquals(1000L, BeatGrid.nearest(g, 760))
        assertEquals(42L, BeatGrid.nearest(g.copy(beats = emptyList()), 42))
    }
}
