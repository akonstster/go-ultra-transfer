package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLaneTest {
    @Test
    fun `overlap preserves power while an exposed edge fades to silence`() {
        val lane = AudioLane(listOf(clip("a", 0, 2_000), clip("b", 1_000, 2_000)))
        val gains = FloatArray(2)
        for (ms in 1_000..1_999 step 13) {
            lane.gains(ms.toDouble(), lane.active(ms.toDouble()), gains)
            assertEquals(1f, gains.sumOf { (it * it).toDouble() }.toFloat(), 0.0001f)
        }
        lane.gains(1_500.0, lane.active(1_500.0), gains)
        assertEquals(gains[0], gains[1], 0.0001f)
        lane.gains(30.0, lane.active(30.0), gains)
        assertEquals(0.5f, gains[0], 0.0001f)
        lane.gains(2_970.0, lane.active(2_970.0), gains)
        assertEquals(0.5f, gains[1], 0.0001f)
    }

    @Test
    fun `a contained clip crossfades in and back without a hard jump`() {
        val lane = AudioLane(listOf(clip("a", 0, 3_000), clip("b", 1_000, 1_000)))
        val gains = FloatArray(2)
        lane.gains(1_500.0, lane.active(1_500.0), gains)
        assertEquals(1f, gains[1], 0.0001f)
        lane.gains(1_999.0, lane.active(1_999.0), gains)
        assertEquals(1f, gains[0], 0.0001f)
        assertEquals(0f, gains[1], 0.004f)
    }

    @Test
    fun `marks follow trims and moves with one beat source in the crossfade`() {
        val first = clip("a", 0, 2_500).copy(fromMs = 500, source = ProjectMusic(
            "a", lengthMs = 2_500, beats = listOf(100, 500, 1_600, 2_100, 2_500),
        ))
        val second = clip("b", 1_000, 2_100).copy(source = ProjectMusic(
            "b", lengthMs = 2_100, beats = listOf(200, 800, 1_900),
        ))
        assertEquals(listOf(0L, 1_100L, 1_800L, 2_900L), AudioLane(listOf(first, second)).marks { it.beats })
    }

    private fun clip(id: String, start: Long, duration: Long) =
        ProjectAudioClip(id, ProjectMusic(id, lengthMs = duration), startMs = start)
}
