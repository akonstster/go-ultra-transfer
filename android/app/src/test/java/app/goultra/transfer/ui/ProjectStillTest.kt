package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A still in the preview: how big its picture is taken in. The photo is decoded by the app itself, and the rule that
 * keeps a 50 MP photo out of memory is the one the viewer decodes by — checked here rather than guessed at.
 */
class ProjectStillTest {
    // 1280 is what the preview asks for: the long side of the black box a 16:9 phone fills.
    private val side = 1280

    @Test
    fun `a photo that fits is taken as it is`() {
        assertEquals(1, sampleFor(1280, 720, side))
        assertEquals(1, sampleFor(640, 480, side))
    }

    @Test
    fun `a bigger photo is halved until it fits`() {
        assertEquals(2, sampleFor(2560, 1440, side))
        // 12 MP (4000 × 3000) comes in at 2000 × 1500 — under twice the asked side, so no more halving.
        assertEquals(2, sampleFor(4000, 3000, side))
        assertEquals(4, sampleFor(8000, 6000, side)) // 48 MP
        assertEquals(8, sampleFor(16_000, 12_000, side))
    }

    @Test
    fun `the picture kept is the smallest that still covers the screen`() {
        for ((w, h) in listOf(3000 to 2000, 4000 to 3000, 8000 to 6000, 12_000 to 9_000)) {
            val long = maxOf(w, h) / sampleFor(w, h, side)
            assertTrue("$w×$h kept at $long", long >= side && long < side * 2)
        }
    }

    @Test
    fun `the longer side decides, whichever way the photo stands`() {
        assertEquals(sampleFor(4000, 3000, side), sampleFor(3000, 4000, side))
    }

    @Test
    fun `a header that was not read leaves the photo alone`() {
        // BitmapFactory gives no bounds for a file it cannot read: nothing is halved, and the decode itself fails.
        assertEquals(1, sampleFor(0, 0, side))
        assertEquals(1, sampleFor(-1, 100, side))
        assertEquals(1, sampleFor(4000, 3000, 0))
    }
}
