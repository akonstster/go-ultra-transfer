package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The movement score of a take: still is zero, a moving picture is high, and a broken frame is not fatal. */
class ClipEnergyTest {
    private fun frame(value: Int, width: Int = 8, height: Int = 6) =
        ClipEnergy.Frame(width, height, IntArray(width * height) { value })

    private fun checker(a: Int, b: Int, width: Int = 8, height: Int = 6) =
        ClipEnergy.Frame(width, height, IntArray(width * height) { if (it % 2 == 0) a else b })

    @Test
    fun `still frames have no energy`() {
        assertEquals(0.0, ClipEnergy.score(listOf(frame(90), frame(90), frame(90))), 0.0001)
    }

    @Test
    fun `a picture that changes every frame scores high`() {
        assertTrue(ClipEnergy.score(listOf(frame(0), frame(255), frame(0))) > 0.8)
    }

    @Test
    fun `a small change stays below a big one`() {
        val small = ClipEnergy.score(listOf(frame(100), frame(110)))
        val big = ClipEnergy.score(listOf(frame(20), frame(200)))
        assertTrue("small=$small big=$big", small < big)
        assertTrue(small in 0.0..1.0)
    }

    @Test
    fun `contrast alone is felt but does not shout`() {
        val score = ClipEnergy.score(listOf(frame(128), checker(112, 144)))
        assertTrue(score > 0.0)
        assertTrue(score < 0.5)
    }

    @Test
    fun `a broken or single frame is not fatal`() {
        assertEquals(0.0, ClipEnergy.score(emptyList()), 0.0)
        assertEquals(0.0, ClipEnergy.score(listOf(frame(10))), 0.0)
        val broken = ClipEnergy.Frame(0, 0, IntArray(0))
        assertEquals(0.0, ClipEnergy.score(listOf(broken, frame(10))), 0.0)
        val odd = ClipEnergy.Frame(4, 3, IntArray(4 * 3) { 255 })
        val mixed = ClipEnergy.score(listOf(frame(0), odd))
        assertTrue(mixed in 0.0..1.0)
    }
}