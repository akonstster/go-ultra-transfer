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

    @Test
    fun `a tempo that drifts is followed, not averaged away`() {
        // 100 BPM speeding up to 125 over twenty seconds, and the clicks' own moments kept for the check.
        val out = FloatArray(rate * 20)
        val rnd = Random(3)
        val clicks = ArrayList<Long>()
        var at = 0.2 * rate
        while (at < out.size) {
            clicks += (at / rate * 1000).toLong()
            val bpm = 100.0 + 25.0 * (at / out.size)
            val start = at.toInt()
            for (k in 0 until 1500) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * 0.8 * exp(-k / 250.0)).toFloat()
            }
            at += 60.0 / bpm * rate
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        // The gaps get shorter toward the end: that is the drift the one-tempo grid could not hold.
        val gaps = g!!.beats.zipWithNext { a, b -> b - a }
        assertTrue("gaps: ${gaps.first()} -> ${gaps.last()}", gaps.last() < gaps.first() - 40)
        // And every beat found is near a click of its own: none more than a sixth of a beat away, and most within ~2 frames.
        var j = 0
        val errors = ArrayList<Long>()
        for (beat in g.beats) {
            while (j + 1 < clicks.size && abs(clicks[j + 1] - beat) <= abs(clicks[j] - beat)) j++
            errors += abs(clicks[j] - beat)
        }
        assertTrue("worst beat ${errors.max()}", errors.max() <= 90)
        assertTrue("mean error ${errors.average()}", errors.average() <= 35.0)
    }

    @Test
    fun `a steady tempo is followed without losing a step by the end`() {
        // 120 BPM for forty seconds: the beats must stay on the clicks all the way, not drift half a beat by the middle
        // (a grid a beat and a half behind is what a whole-frame grid looked like).
        val out = FloatArray(rate * 40)
        val rnd = Random(5)
        val clicks = ArrayList<Long>()
        var at = 0.25 * rate
        while (at < out.size) {
            clicks += (at / rate * 1000).toLong()
            val start = at.toInt()
            for (k in 0 until 1500) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * 0.8 * exp(-k / 250.0)).toFloat()
            }
            at += 0.5 * rate
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertEquals("tempo", 120.0, g!!.bpm, 0.5)
        val gaps = g.beats.zipWithNext { a, b -> b - a }
        assertEquals("middle gap", 500.0, gaps.sorted()[gaps.size / 2].toDouble(), 2.0)
        var j = 0
        var worst = 0L
        for (beat in g.beats) {
            while (j + 1 < clicks.size && abs(clicks[j + 1] - beat) <= abs(clicks[j] - beat)) j++
            worst = maxOf(worst, abs(clicks[j] - beat))
        }
        assertTrue("worst beat $worst ms off", worst <= 25)
    }

    @Test
    fun `a whole minute that speeds up is followed to its last beat`() {
        // Sixty seconds from 100 to 130 BPM: the walk reaches the very last frame of the track, which once read past the
        // end of the envelope and lost the whole analysis.
        val seconds = 60
        val out = FloatArray(rate * seconds)
        val rnd = Random(9)
        var at = 0.2 * rate
        while (at < out.size) {
            val bpm = 100.0 + 30.0 * (at / out.size)
            val start = at.toInt()
            for (k in 0 until 1200) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * 0.8 * exp(-k / 200.0)).toFloat()
            }
            at += 60.0 / bpm * rate
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertTrue("beats: ${g!!.beats.size}", g.beats.size > 100)
        assertTrue("last beat ${g.beats.last()}", g.beats.last() > seconds * 1000L - 1000L)
        // The pace got faster: the last gaps are shorter than the first ones.
        val gaps = g.beats.zipWithNext { a, b -> b - a }
        assertTrue("gaps ${gaps.first()} -> ${gaps.last()}", gaps.last() < gaps.first())
    }

    @Test
    fun `the drop is where the energy leaps out of a quiet stretch`() {
        val quietThenLoud = FloatArray(20) { if (it < 8) 0.2f else 0.9f }
        assertEquals(8_000L, BeatGrid.dropOf(quietThenLoud))
        assertNull(BeatGrid.dropOf(FloatArray(20) { 0.5f }))
        assertNull(BeatGrid.dropOf(FloatArray(3) { 0f }))
    }
}
