package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LooksTest {
    private val colours = listOf(
        floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.8f, 0.5f, 0.2f), floatArrayOf(0.3f, 0.6f, 0.25f),
        floatArrayOf(0.35f, 0.55f, 0.85f), floatArrayOf(0.78f, 0.58f, 0.48f), floatArrayOf(0.05f, 0.05f, 0.06f),
    )
    private val grass = floatArrayOf(0.3f, 0.6f, 0.25f)
    private val sky = floatArrayOf(0.35f, 0.55f, 0.85f)
    private val face = floatArrayOf(0.78f, 0.58f, 0.48f)

    private fun graded(look: Look, c: FloatArray, strength: Float = 1f) = FilmMath.apply(c, Look.grade(look, strength, 0f, 0f, 0f))
    private fun chroma(c: FloatArray) = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))
    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun noneAndZeroStrengthChangeNothing() {
        for (c in colours) {
            assertArrayEquals(c, graded(Look.NONE, c), 2e-3f)
            for (l in Look.entries) assertArrayEquals(c, graded(l, c, 0f), 2e-3f)
        }
    }

    @Test
    fun privateRyanIsMutedWithLiftedBlacks() {
        assertTrue(chroma(graded(Look.RYAN, grass)) < chroma(grass) * 0.7f)
        val black = luma(graded(Look.RYAN, floatArrayOf(0f, 0f, 0f)))
        assertTrue("black $black", black > 0.03f && black < 0.1f) // the flash: blacks lifted a little, not grey
    }

    @Test
    fun oBrotherTurnsGrassDryYellow() {
        val g = graded(Look.O_BROTHER, grass)
        // From green (about 225°) towards yellow (167°); the vectorscope angle taken 0…360.
        fun deg(c: FloatArray) = (FilmMath.hue(c) + 360f) % 360f
        assertTrue("hue ${deg(g)} from ${deg(grass)}", deg(g) < deg(grass) - 30f && deg(g) > 167f)
        assertTrue(chroma(g) < chroma(grass))
    }

    @Test
    fun amelieIsWarmWithoutBlue() {
        val s = graded(Look.AMELIE, sky)
        assertTrue(s[2] - s[0] < (sky[2] - sky[0]) * 0.5f)
        val grey = graded(Look.AMELIE, floatArrayOf(0.6f, 0.6f, 0.6f))
        assertTrue(grey[0] > grey[2] && grey[1] > grey[2]) // yellow cast
        // No skin guard: a face goes golden with the rest (its hue moves towards yellow, 167°).
        val f = graded(Look.AMELIE, face)
        assertTrue("face ${FilmMath.hue(f)} from ${FilmMath.hue(face)}", FilmMath.hue(f) > FilmMath.hue(face) + 5f)
    }

    @Test
    fun revenantIsMutedFacesLighterSkyDarker() {
        assertTrue(chroma(graded(Look.REVENANT, grass)) < chroma(grass) * 0.75f)
        val faceUp = luma(graded(Look.REVENANT, face)) / luma(face)
        val skyUp = luma(graded(Look.REVENANT, sky)) / luma(sky)
        assertTrue("face $faceUp sky $skyUp", faceUp > skyUp + 0.15f)
        assertTrue(abs(FilmMath.hue(graded(Look.REVENANT, face)) - FilmMath.hue(face)) < 2f) // skin keeps its hue
    }
}
