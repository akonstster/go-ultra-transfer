package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class FilmMathTest {
    private val orange = floatArrayOf(0.8f, 0.5f, 0.2f)
    private val skin = floatArrayOf(0.78f, 0.58f, 0.48f) // a light face, about on the skin line

    private fun gamma(lin: Float) = lin.pow(1f / FilmMath.GAMMA)

    @Test
    fun offIsIdentity() {
        assertArrayEquals(orange, FilmMath.apply(orange, 0f, 0f, 0f), 2e-3f)
        assertArrayEquals(skin, FilmMath.apply(skin, 0f, 0f, 0f), 2e-3f)
    }

    @Test
    fun curveKeepsGreyAndOrder() {
        // Grey stays grey (only the print's black lifts it a hair); the curve never turns back.
        assertEquals(FilmMath.GREY, FilmMath.tone(FilmMath.GREY, 1f), 1e-3f)
        var last = -1f
        for (i in 0..1000) {
            val y = FilmMath.tone(i / 1000f, 1f)
            assertTrue("at $i", y >= last)
            last = y
        }
    }

    @Test
    fun highlightsRollOffShadowsGoDown() {
        val white = FilmMath.tone(1f, 1f)
        assertTrue("white $white", white < 1f && white > 0.97f) // into white, not clipped
        assertTrue(FilmMath.tone(0.6f, 1f) > 0.6f) // more contrast above grey…
        assertTrue(FilmMath.tone(0.03f, 1f) < 0.03f) // …and below it
        assertTrue(FilmMath.tone(0f, 1f) > 0f) // the print's black
        assertTrue(gamma(FilmMath.tone(0f, 1f)) < 14f / 255)
    }

    @Test
    fun saturatedColoursGetDenser() {
        // Same light for a grey and a red; with film the red ends darker relative to the grey.
        val red = floatArrayOf(0.9f, 0.15f, 0.12f)
        val luma = 0.2126f * red[0].pow(2.4f) + 0.7152f * red[1].pow(2.4f) + 0.0722f * red[2].pow(2.4f)
        val grey = FloatArray(3) { gamma(luma) }
        fun light(c: FloatArray) = 0.2126f * c[0].pow(2.4f) + 0.7152f * c[1].pow(2.4f) + 0.0722f * c[2].pow(2.4f)
        val redOut = light(FilmMath.apply(red, 1f, 0f, 0f)) / light(red)
        val greyOut = light(FilmMath.apply(grey, 1f, 0f, 0f)) / light(grey)
        assertTrue("red $redOut grey $greyOut", redOut < greyOut * 0.9f)
    }

    @Test
    fun skinKeepsItsHue() {
        // Cold and toned, a face keeps the hue it was shot with (always, no slider needed).
        val before = FilmMath.hue(skin)
        val graded = FilmMath.hue(FilmMath.apply(skin, 1f, -1f, 0f))
        assertTrue("before $before graded $graded", abs(graded - before) <= 1.5f)
        // A dark skin too: same line, less light and less colour.
        val dark = floatArrayOf(0.3f, 0.19f, 0.13f)
        assertTrue(FilmMath.skinMask(0.21f, (0.13f - 0.21f) / 1.8556f, (0.3f - 0.21f) / 1.5748f) > 0.9f)
        assertEquals(FilmMath.hue(dark), FilmMath.hue(FilmMath.apply(dark, 1f, -1f, 0f)), 1.5f)
        // «Ровная кожа» turns a reddish face towards the skin line.
        val red = floatArrayOf(0.80f, 0.55f, 0.52f)
        assertTrue(abs(FilmMath.hue(FilmMath.apply(red, 0f, 0f, 1f)) - 123f) < abs(FilmMath.hue(red) - 123f) - 5f)
        // A blue sky is no skin: the skin setting leaves it alone.
        val sky = floatArrayOf(0.35f, 0.55f, 0.85f)
        assertArrayEquals(FilmMath.apply(sky, 1f, -1f, 0f), FilmMath.apply(sky, 1f, -1f, 1f), 1e-4f)
    }

    @Test
    fun skinMaskHasNoSteps() {
        // Across a face hue, colour and brightness wander from pixel to pixel: the mask must follow smoothly, or a
        // preset that moves colours far shows its edge as stripes. Full on a face's own hues (±15° of 123°).
        fun mask(deg: Double, rel: Float, luma: Float): Float {
            val c = rel * luma
            val a = Math.toRadians(deg)
            return FilmMath.skinMask(luma, (c * kotlin.math.cos(a)).toFloat(), (c * kotlin.math.sin(a)).toFloat())
        }
        for (deg in 108..138) assertEquals(1f, mask(deg.toDouble(), 0.25f, 0.4f), 1e-3f)
        for (deg in 60..190) {
            val step = abs(mask(deg + 1.0, 0.25f, 0.4f) - mask(deg.toDouble(), 0.25f, 0.4f))
            assertTrue("at $deg°: $step", step < 0.06f)
        }
        // From a skin's colour (0.2…0.3, more in shadows) up to a saturated orange.
        for (i in 15..100) {
            val rel = i / 100f
            val step = abs(mask(123.0, rel + 0.01f, 0.4f) - mask(123.0, rel, 0.4f))
            assertTrue("at $rel: $step", step < 0.06f)
        }
    }

    @Test
    fun warmthIsWarmer() {
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        val warm = FilmMath.apply(grey, 0f, 1f, 0f)
        assertTrue(warm[0] > warm[1] && warm[1] > warm[2])
        val cold = FilmMath.apply(grey, 0f, -1f, 0f)
        assertTrue(cold[2] > cold[1] && cold[1] > cold[0])
    }
}
