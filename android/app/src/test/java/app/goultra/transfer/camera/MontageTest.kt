package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editorial arithmetic of the auto montage: four long takes become a musical cut, a source is never used twice,
 * a moving tempo keeps its actual beats, and a rerun with the same seed is the same film. No decoder, no phone.
 */
class MontageTest {
    private fun src(
        file: String,
        from: Long = 0L,
        to: Long = 120_000L,
        energy: Double? = null,
        role: ProjectClip.Role = ProjectClip.Role.TAKE,
    ) = Montage.Source(ProjectClip(file, from, to, energy = energy, role = role))

    private fun grid(period: Long = 500L, length: Long = 60_000L): List<Long> =
        (0..length step period).map { it }

    private fun four() = listOf(
        src("a.mp4"), src("b.mp4"), src("c.mp4"), src("d.mp4"),
    )

    /** Where each output piece begins and ends on the project's own timeline. */
    private fun boundaries(clips: List<ProjectClip>): List<Long> =
        clips.runningFold(0L) { at, clip -> at + clip.durationMs }.drop(1)

    @Test
    fun `four long takes are cut into a whole track`() {
        val result = Montage.montage(
            Scenario.TRAVEL, Dynamics.EVEN, four(),
            Montage.Track(beats = grid(), lengthMs = 60_000L),
        )
        assertEquals(60_000L, result.usedMs)
        assertEquals(60_000L, result.targetMs)
        assertFalse(result.exhausted)
        // Not one card per file: the music asked for many pieces, and each file's material is behind several of them.
        assertTrue("only ${result.clips.size} pieces", result.clips.size >= 20)
        assertEquals(setOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"), result.clips.map { it.file }.toSet())
        assertTrue(result.clips.all { it.auto && it.atMs == null })
        // Every file is read forward, once: no range of it is used twice.
        for ((file, pieces) in result.clips.groupBy { it.file }) {
            val ranges = pieces.sortedBy { it.fromMs }
            assertTrue(file, ranges.all { it.fromMs >= 0L && it.toMs <= 120_000L && it.toMs > it.fromMs })
            assertTrue(file, ranges.zipWithNext().all { (a, b) -> a.toMs <= b.fromMs })
        }
    }

    @Test
    fun `a seed is the same cut and another seed is another one`() {
        val track = Montage.Track(beats = grid(), lengthMs = 60_000L)
        fun cut(seed: Long) = Montage.montage(Scenario.TRAVEL, Dynamics.SHUFFLE, four(), track, seed)
        assertEquals(cut(7L), cut(7L))
        assertNotEquals(cut(7L).clips, cut(8L).clips)
    }

    @Test
    fun `without a grid the fallback still cuts evenly`() {
        val result = Montage.montage(
            Scenario.VLOG, Dynamics.EVEN, four(),
            Montage.Track(beats = emptyList(), lengthMs = 60_000L, fallbackBeatMs = 500L),
        )
        assertEquals(60_000L, result.usedMs)
        assertTrue(result.clips.size > 1)
        // VLOG holds longer (6 fallback beats = 3 s), and the pieces still cover the whole track without gaps.
        assertTrue(result.clips.all { it.durationMs == 3_000L })
        assertEquals(60_000L, boundaries(result.clips).last())
    }

    @Test
    fun `the offset is the project's zero and the drop is a cut`() {
        val track = Montage.Track(
            beats = grid(length = 120_000L),
            lengthMs = 63_000L,
            offsetMs = 3_000L,
            activeDropMs = 33_000L,
            fallbackBeatMs = 500L,
        )
        val result = Montage.montage(Scenario.MUSIC, Dynamics.DYNAMIC, four(), track)
        assertEquals(60_000L, result.usedMs)
        val cuts = boundaries(result.clips)
        assertTrue("drop is not a cut: $cuts", 30_000L in cuts)
    }

    @Test
    fun `a moving tempo cuts on its actual beats`() {
        // The beats drift: the first four last 2.6 s, not the 4 × 500 ms a fixed BPM would give.
        val beats = listOf(0L, 500L, 1_300L, 1_800L, 2_600L, 3_100L, 4_100L, 4_600L, 5_400L, 6_000L)
        val result = Montage.montage(
            Scenario.TRAVEL, Dynamics.EVEN, four(),
            Montage.Track(beats = beats, lengthMs = 6_000L, fallbackBeatMs = 500L),
        )
        assertEquals(2_600L, result.clips.first().durationMs)
        assertTrue(boundaries(result.clips).dropLast(1).all { it in beats })
    }

    @Test
    fun `a photo fills its slot and is not a missing video range`() {
        val photo = src("IMG_0001.jpg", 0L, 3_000L, role = ProjectClip.Role.STILL)
        val result = Montage.montage(
            Scenario.SLIDESHOW, Dynamics.EVEN, listOf(photo),
            Montage.Track(beats = grid(length = 2_000L), lengthMs = 2_000L),
        )
        assertEquals(2_000L, result.usedMs)
        assertEquals(ProjectClip.Role.STILL, result.clips.single().role)
        assertEquals(2_000L, result.clips.single().durationMs)
    }

    @Test
    fun `too little material stops at its end and says so`() {
        val pool = listOf(src("a.mp4", 0, 1_000), src("b.mp4", 0, 1_000))
        val result = Montage.montage(
            Scenario.MUSIC, Dynamics.RHYTHMIC, pool,
            Montage.Track(beats = grid(), lengthMs = 60_000L),
        )
        assertEquals(60_000L, result.targetMs)
        assertEquals(2_000L, result.usedMs)
        assertTrue(result.exhausted)
        assertEquals(2_000L, result.clips.sumOf { it.durationMs })
        assertTrue(result.clips.all { it.durationMs > 0L })
    }

    @Test
    fun `a tail too small for a card is not a flash`() {
        val result = Montage.montage(
            Scenario.TRAVEL, Dynamics.EVEN, listOf(src("a.mp4", 0, 500)),
            Montage.Track(beats = grid(), lengthMs = 2_000L),
        )
        assertEquals(500L, result.usedMs)
        assertEquals(500L, result.clips.single().durationMs)
        assertTrue(result.exhausted)
    }

    @Test
    fun `an exact source fills the track and leaves nothing exhausted`() {
        val result = Montage.montage(
            Scenario.TRAVEL, Dynamics.EVEN, listOf(src("a.mp4", 0, 2_000)),
            Montage.Track(beats = grid(length = 2_000L), lengthMs = 2_000L),
        )
        assertFalse(result.exhausted)
        assertEquals(2_000L, result.clips.single().durationMs)
    }

    @Test
    fun `the source's own start is honoured and its energy carried`() {
        val pool = listOf(src("a.mp4", from = 5_000L, to = 9_000L, energy = 0.73))
        val result = Montage.montage(
            Scenario.MUSIC, Dynamics.EVEN, pool,
            Montage.Track(beats = grid(length = 4_000L), lengthMs = 4_000L),
        )
        val pieces = result.clips.sortedBy { it.fromMs }
        assertEquals(5_000L, pieces.first().fromMs)
        assertEquals(9_000L, pieces.last().toMs)
        assertTrue(pieces.all { it.energy == 0.73 })
    }

    @Test
    fun `an energy-led scene opens on a moving source and turns to the other after it`() {
        val pool = listOf(
            src("quiet.mp4", energy = 0.05),
            src("busy.mp4", energy = 0.95),
        )
        val result = Montage.montage(
            Scenario.MUSIC, Dynamics.EVEN, pool,
            Montage.Track(
                beats = grid(length = 4_000L),
                lengthMs = 4_000L,
                energy = listOf(0.9f, 0.9f, 0.9f, 0.9f),
            ),
        )
        assertEquals("busy.mp4", result.clips.first().file)
        // The busy source is not used over and over: the second piece is the other take's turn.
        assertEquals("quiet.mp4", result.clips[1].file)
    }

    @Test
    fun `the loudness curve follows the wave and survives silence and bad values`() {
        val quiet = FloatArray(20) { 0.1f }
        val loud = FloatArray(20) { 0.8f }
        val curve = Montage.intensities(quiet + loud, bucketMs = 1_000L, stepMs = 1_000L)
        assertEquals(40, curve.size)
        assertTrue(curve[0] < 0.2f)
        assertTrue(curve.last() > 0.95f)
        // Silence is zero and not NaN; a broken sample must not poison the whole curve.
        val broken = Montage.intensities(floatArrayOf(0f, 0f, Float.NaN), 25L, 1_000L)
        assertTrue(broken.all { !it.isNaN() && it in 0f..1f })
    }

    @Test
    fun `an empty pool makes nothing`() {
        val result = Montage.montage(
            Scenario.CUSTOM, Dynamics.EVEN, emptyList(),
            Montage.Track(beats = grid(), lengthMs = 60_000L),
        )
        assertTrue(result.clips.isEmpty())
        assertEquals(0L, result.usedMs)
    }

    @Test
    fun `slots cover the track exactly once`() {
        val slots = Montage.slots(
            Scenario.TRAVEL, Dynamics.EVEN,
            Montage.Track(beats = grid(), lengthMs = 60_000L), 60_000L,
        )
        assertEquals(0L, slots.first().startMs)
        assertEquals(60_000L, slots.last().endMs)
        assertTrue(slots.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertTrue(slots.all { it.durationMs > 0L })
    }
}