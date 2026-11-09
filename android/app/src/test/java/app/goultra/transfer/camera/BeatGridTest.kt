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
    fun `a strong accent every second beat does not halve the tempo`() {
        // A real track (Huawei's «Dream It Possible», 120 BPM): the strong beat repeats every two beats, so the plain
        // autocorrelation is strongest at the half-time lag and the grid became a click every other beat — 63.5 BPM on a
        // 120 BPM piece. The lean toward the usual tempo has to be strong enough to keep the beat, not the bar.
        val seconds = 20
        val out = FloatArray(rate * seconds) { i -> (0.05 * sin(2 * PI * 220 * i / rate)).toFloat() }
        val rnd = Random(23)
        var at = 0.25 * rate
        var k = 0
        while (at < out.size) {
            val loud = if (k % 2 == 0) 0.85f else 0.18f
            val start = at.toInt()
            for (n in 0 until 1500) {
                if (start + n >= out.size) break
                out[start + n] += ((rnd.nextFloat() * 2 - 1) * loud * exp(-n / 250.0)).toFloat()
            }
            at += 0.5 * rate
            k++
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertEquals("the beat, not the bar", 120.0, g!!.bpm, 2.0)
        assertTrue("the grid must begin on the first click: ${g.offsetMs}", g.offsetMs < 1_000L)
        assertTrue(
            "beats: ${g.beats.take(8)}",
            g.beats.take(8).zipWithNext { a, b -> b - a }.all { abs(it - 500) <= 15 },
        )
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
    fun `a quiet intro is found before a much louder chorus`() {
        // The real failure: the loudest part set a global floor, the quiet intro never became a candidate, and the DP path
        // began where the chorus did — 51 s into one track, 84 s into another. The clicks here start at 250 ms at a tenth
        // of the chorus's loudness.
        val seconds = 20
        val out = FloatArray(rate * seconds)
        val rnd = Random(17)
        val clicks = ArrayList<Long>()
        var at = 0.25 * rate
        while (at < out.size) {
            clicks += (at / rate * 1000).toLong()
            val loud = if (at < 10.0 * rate) 0.07f else 0.85f
            val start = at.toInt()
            for (k in 0 until 1500) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * loud * exp(-k / 250.0)).toFloat()
            }
            at += 0.5 * rate
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertEquals("the chorus must not change the tempo", 120.0, g!!.bpm, 1.0)
        assertTrue("the grid must begin at the intro, not the chorus: ${g.offsetMs}", g.offsetMs < 1_000L)
        // Every found beat is near one of the clicks — the quiet intro included.
        var j = 0
        var worst = 0L
        for (beat in g.beats) {
            while (j + 1 < clicks.size && abs(clicks[j + 1] - beat) <= abs(clicks[j] - beat)) j++
            worst = maxOf(worst, abs(clicks[j] - beat))
        }
        assertTrue("worst beat $worst ms off", worst <= 55)
    }

    @Test
    fun `the confidence is kept once the grid is built`() {
        val g = BeatGrid.detectDrifting(clicks(120.0, 230, 20), rate)!!
        assertTrue("steady clicks are sure: ${g.confidence}", g.confidence > 0.3)
        assertEquals(BeatGrid.ANALYSIS_VERSION, 8)
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
        assertTrue("worst beat $worst ms off", worst <= 12)
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
    fun `every return after a quiet stretch is a drop, and it opens on the next beat`() {
        // Quiet, loud, a real breakdown, loud again: two returns, not one. On a grid whose beats stand half a second
        // apart and start at 300 ms, each drop is the first beat of the returning loud part — the old «beat before if the
        // next one is far» put it on the last beat of the quiet passage.
        val energy = FloatArray(600) { i ->
            val t = i * 100L
            when {
                t < 10_000L -> 0.15f
                t < 20_000L -> 0.85f
                t < 30_000L -> 0.15f
                else -> 0.95f
            }
        }
        assertEquals(listOf(10_000L, 30_000L), BeatGrid.dropsOf(energy, BeatGrid.LOUD_STEP_MS))
        val beats = (300L..40_000L step 500L).toList()
        assertEquals(listOf(10_300L, 30_300L), BeatGrid.dropsOf(energy, BeatGrid.LOUD_STEP_MS, beats))
    }

    @Test
    fun `a rise inside the loud part before a pause is not a drop, and every later pause returns`() {
        // The real failure: after the first drop the track grew a little louder again just before a breakdown, and that
        // small rise became the second marker while the actual returns after the breakdown and after the next pause were
        // lost. Three quiet passages here must give three drops — at 5, 30 and 50 s — and never at 15 s (a rise inside a
        // loud part with no pause behind it) or 20/40 s (the falls into the pauses).
        val energy = FloatArray(700) { i ->
            val t = i * 100L
            when {
                t < 5_000L -> 0.15f
                t < 15_000L -> 0.80f
                t < 20_000L -> 0.82f // louder inside the same loud part: a rise, not a return
                t < 30_000L -> 0.15f
                t < 40_000L -> 0.85f
                t < 50_000L -> 0.20f
                t < 60_000L -> 0.90f
                else -> 0.30f
            }
        }
        assertEquals(listOf(5_000L, 30_000L, 50_000L), BeatGrid.dropsOf(energy, BeatGrid.LOUD_STEP_MS))
        val beats = (200L..70_000L step 500L).toList()
        assertEquals(listOf(5_200L, 30_200L, 50_200L), BeatGrid.dropsOf(energy, BeatGrid.LOUD_STEP_MS, beats))
    }

    @Test
    fun `a drop is where the energy leaps out of a quiet stretch`() {
        val quietThenLoud = FloatArray(20) { if (it < 8) 0.2f else 0.9f }
        assertEquals(8_000L, BeatGrid.dropOf(quietThenLoud))
        assertNull(BeatGrid.dropOf(FloatArray(20) { 0.5f }))
        assertNull(BeatGrid.dropOf(FloatArray(3) { 0f }))
    }

    @Test
    fun `a riser before the drop is not the drop`() {
        // Ten seconds of quiet verse, four of them a riser climbing to the drop's own level, then the loud part at 10 s.
        // The old search looked for where the bright stretch begins, walked back into the riser and put the drop two
        // beats early (8 s at 120 BPM) — the very error that was seen on the phone.
        val loud = FloatArray(300) { i ->
            val t = i * 100L
            when {
                t < 6_000L -> 0.2f
                t < 10_000L -> 0.2f + 0.5f * (t - 6_000L) / 4_000L
                else -> 0.9f
            }
        }
        assertEquals(10_000L, BeatGrid.dropOf(loud, BeatGrid.LOUD_STEP_MS))
        // On a grid it is the beat that opens the loud part — never a beat of the riser.
        val beats = (0L..12_000L step 500L).toList()
        assertEquals(10_000L, BeatGrid.dropOf(loud, BeatGrid.LOUD_STEP_MS, beats))
    }

    @Test
    fun `a drop is found in a track of drums, not half of its loudest hit`() {
        // The loudness of a track with drums: one loud bucket a beat, near-silence between them — the piece's loudest
        // moment is a single drum, and «half of it» was never reached by the quiet half, so no drop was found at all.
        fun drums(seconds: Int, quietMs: Long, loudAmp: Float, quietAmp: Float): FloatArray {
            val buckets = seconds * 10
            return FloatArray(buckets) { i ->
                val ms = i * 100L
                val inBeat = ms % 500L < 100L
                val amp = if (ms < quietMs) quietAmp else loudAmp
                if (inBeat) amp else amp * 0.05f
            }
        }
        val track = drums(40, 20_000L, loudAmp = 1f, quietAmp = 0.2f)
        val drop = BeatGrid.dropOf(track, BeatGrid.LOUD_STEP_MS)
        assertNotNull(drop)
        assertTrue("drop $drop", drop!! in 19_800L..20_500L)
        // On the grid it is the beat that opens the loud part.
        val beats = (0L..40_000L step 500L).toList()
        assertEquals(20_000L, BeatGrid.dropOf(track, BeatGrid.LOUD_STEP_MS, beats))
    }

    @Test
    fun `a drop after the detector's short grid lands on the full grid, not on its last beat`() {
        // The grid the detector builds can stop where its path earned most (on a real track it ended at 30.8 s of 1:53),
        // while the lane draws the same period on to the end. The profile must use that full grid: otherwise every later
        // drop is put on the short grid's last beat — the «second drop before the pause» seen on the phone.
        val seconds = 40
        val samples = FloatArray(rate * seconds) { i ->
            val t = i * 1000.0 / rate
            val amp = if (t < 20_000.0) 0.02 else 0.4
            (amp * sin(2 * PI * 220 * i / rate)).toFloat()
        }
        val short = BeatGrid.Grid(120.0, 0L, (0L..15_000L step 500L).toList(), 0.9)
        val full = (0L..40_000L step 500L).toList()
        val shortDrops = BeatGrid.profile(samples, rate, short, short.beats)?.drops.orEmpty()
        val fullDrops = BeatGrid.profile(samples, rate, short, full)?.drops.orEmpty()
        assertEquals(listOf(15_000L), shortDrops)
        assertEquals(1, fullDrops.size)
        assertTrue("the drop after 20 s is on the full grid: $fullDrops", fullDrops.single() in 20_000L..20_500L)
    }

    @Test
    fun `a track whose notes run at twice the beat keeps the beat, not the eighth notes`() {
        // A kick every 500 ms with an extra quieter hit between them: the strongest repeat is at 250 ms, and the walk back
        // over a long quiet introduction used to shrink the period by 15 % a step until the grid doubled (252 BPM on a
        // 126 BPM track). The beat itself must survive the extra notes.
        val seconds = 40
        val out = FloatArray(rate * seconds) { i -> (0.05 * sin(2 * PI * 220 * i / rate)).toFloat() }
        val rnd = Random(31)
        var at = 0.25 * rate
        var k = 0
        while (at < out.size) {
            val loud = if (k % 2 == 0) 0.85f else 0.20f
            val start = at.toInt()
            for (n in 0 until 900) {
                if (start + n >= out.size) break
                out[start + n] += ((rnd.nextFloat() * 2 - 1) * loud * exp(-n / 160.0)).toFloat()
            }
            at += 0.25 * rate
            k++
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertEquals("the beat, not its double", 120.0, g!!.bpm, 2.5)
        assertTrue("grid begins at the track: ${g.offsetMs}", g.offsetMs < 1_000L)
        assertTrue(
            "beats: ${g.beats.take(8)}",
            g.beats.take(8).zipWithNext { a, b -> b - a }.all { abs(it - 500) <= 25 },
        )
    }

    @Test
    fun `the first beat of a return is chosen even when the loud level arrives one beat later`() {
        // A pause to 10 s, then a hit at the first beat (10.1) that is already loud, the level settling by 10.4. The
        // detected 90 % edge is after that first beat; taking only the next beat would put the drop on the second one.
        val energy = FloatArray(200) { i -> if (i < 100) 0.05f else 0.90f }
        val beats = (100L..20_000L step 500L).toList()
        assertEquals(10_100L, BeatGrid.beatOfReturn(10_400L, 10_000L, 0.9, energy, BeatGrid.LOUD_STEP_MS, beats))
        // A beat far from the pause (a riser) must not be pulled back onto it.
        assertEquals(10_600L, BeatGrid.beatOfReturn(10_400L, 7_500L, 0.9, energy, BeatGrid.LOUD_STEP_MS, beats))
    }

    @Test
    fun `the grid phase moves onto the low drum, not between its hits`() {
        // A low-drum envelope with a peak every 15 frames; the grid currently stands five frames after every peak. The
        // whole grid must move back onto the peaks, not halfway between them.
        val rate = 22050
        val low = FloatArray(100)
        val peaks = listOf(10, 25, 40, 55, 70, 85)
        peaks.forEach { low[it] = 1f }
        val beats = listOf(20, 35, 50, 65, 80).map { BeatGrid.frameMs(it.toDouble(), rate) }
        val phased = BeatGrid.phaseToLow(beats, low, rate)
        assertEquals(peaks.drop(1).map { BeatGrid.frameMs(it.toDouble(), rate) }, phased)
    }

    @Test
    fun `a piece that only builds still has a drop, where its loud part is reached`() {
        // No step anywhere (the loudness climbs smoothly), so the fallback names where it comes most of the way to its own
        // loud stretch — taken forward, so it can never stand in the middle of the build.
        val builds = FloatArray(300) { i -> 0.4f + 0.6f * (i / 300f) }
        val drop = BeatGrid.dropOf(builds, BeatGrid.LOUD_STEP_MS)
        assertNotNull("a piece that builds has a loud part", drop)
        // Where the loudness comes most of the way to its own loud stretch: in the loud end, never at the start of the
        // build (that is the error the drop once made — it stood where the brightness began, not where it arrived).
        assertTrue("drop $drop", drop!! in 12_000L..20_000L)
        // A flat piece has none: silence and a steady hum are not a drop.
        assertNull(BeatGrid.dropOf(FloatArray(300) { 0.5f }, BeatGrid.LOUD_STEP_MS))
        assertNull(BeatGrid.dropOf(FloatArray(300), BeatGrid.LOUD_STEP_MS))
    }

    @Test
    fun `a track that speeds up is followed without falling behind`() {
        // A minute from 100 to 130 BPM. The old walk read its tempo from the four seconds around a beat and searched only
        // 30 ms off its guess, so once the sound had moved on the grid never came back: the beats stood later and later,
        // and the click with them.
        val seconds = 60
        val out = FloatArray(rate * seconds)
        val rnd = Random(11)
        val clicks = ArrayList<Long>()
        var at = 0.2 * rate
        while (at < out.size) {
            clicks += (at / rate * 1000).toLong()
            val start = at.toInt()
            for (k in 0 until 1200) {
                if (start + k >= out.size) break
                out[start + k] += ((rnd.nextFloat() * 2 - 1) * 0.8 * exp(-k / 200.0)).toFloat()
            }
            val bpm = 100.0 + 30.0 * (at / out.size)
            at += 60.0 / bpm * rate
        }
        val g = BeatGrid.detectDrifting(out, rate)
        assertNotNull(g)
        assertTrue("beats: ${g!!.beats.size}", g.beats.size > 100)
        // Every beat against the click it stands for: positive — the beat is early, negative — late.
        var j = 0
        val offs = ArrayList<Long>(g.beats.size)
        for (beat in g.beats) {
            while (j + 1 < clicks.size && abs(clicks[j + 1] - beat) <= abs(clicks[j] - beat)) j++
            offs += clicks[j] - beat
        }
        val worst = offs.maxOf { abs(it) }
        assertTrue("worst beat $worst ms off", worst <= 45)
        val mean = offs.average()
        assertTrue("mean error $mean", abs(mean) <= 12.0)
        // The second half leans no more than the first: a grid that falls behind leans more and more.
        val half = offs.size / 2
        assertEquals("the two halves lean alike", offs.take(half).average(), offs.drop(half).average(), 15.0)
    }
}
