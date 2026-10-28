package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioJoinTest {
    private val rate = 1000 // 1 frame = 1 ms, easy to follow
    private val join = AudioJoin(rate, 2)

    /** Source: frame f has the value f % 30000 in both channels — every frame tells where it came from. */
    private fun read(at: Long, n: Int) = ShortArray(n * 2) { k -> ((at + k / 2) % 30000).toShort() }

    private fun render(pieces: List<Highlight>): ShortArray {
        val out = ArrayList<Short>()
        join.render(join.plan(pieces), ::read) { out.addAll(it.toList()) }
        return out.toShortArray()
    }

    @Test
    fun asLongAsThePictureAndExactInside() {
        val pieces = listOf(Highlight(1000, 3000), Highlight(5000, 6000), Highlight(9000, 9500))
        val out = render(pieces)
        assertEquals((2000 + 1000 + 500) * 2, out.size)
        // Inside a piece, away from its joins: the source frame for frame — in step with the picture.
        assertEquals(1500, out[500 * 2].toInt()) // clip 0.5 s = piece 1 at 1.5 s
        assertEquals(5500, out[2500 * 2].toInt()) // clip 2.5 s = piece 2 at 5.5 s
        assertEquals(9200, out[3200 * 2 + 1].toInt())
    }

    @Test
    fun theJoinMixesBothSidesAroundTheCut() {
        val out = render(listOf(Highlight(1000, 2000), Highlight(5000, 6000)))
        // The cut is at clip 1.0 s. Just before the window (0.9 s): only piece 1 (source 1.899 s).
        assertEquals(1899, out[899 * 2].toInt())
        // At the cut: piece 1's sound past its end (2.0 s) and piece 2's (5.0 s), half power each.
        val mid = out[1000 * 2].toInt()
        val expected = (2000 * Math.cos(Math.PI / 4) + 5000 * Math.sin(Math.PI / 4)).toInt()
        assertTrue("mid $mid expected ~$expected", kotlin.math.abs(mid - expected) < 20)
        // Just after the window (1.1 s): only piece 2 (source 5.1 s).
        assertEquals(5100, out[1100 * 2].toInt())
    }

    @Test
    fun aShortPieceNarrowsTheWindow() {
        val parts = join.plan(listOf(Highlight(1000, 3000), Highlight(5000, 5120), Highlight(8000, 9000)))
        assertEquals(60, parts[0].outFrames) // half of the 120 ms piece
        assertEquals(60, parts[1].inFrames)
        assertEquals(60, parts[1].outFrames)
        assertEquals(0, parts[0].inFrames)
        assertEquals(0, parts[2].outFrames)
        assertEquals((2000 + 120 + 1000) * 2, render(listOf(Highlight(1000, 3000), Highlight(5000, 5120), Highlight(8000, 9000))).size)
    }

    @Test
    fun startAndEndFadeFromSilence() {
        val out = render(listOf(Highlight(1000, 2000)))
        assertTrue(kotlin.math.abs(out[0].toInt()) < 100) // 1000 × a twentieth
        assertEquals(1500, out[500 * 2].toInt())
        assertTrue(kotlin.math.abs(out[999 * 2].toInt()) < 200)
    }
}
