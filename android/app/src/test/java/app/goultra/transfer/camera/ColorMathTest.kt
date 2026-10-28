package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorMathTest {
    private val orange = floatArrayOf(0.8f, 0.5f, 0.2f)

    @Test
    fun noChangeIsIdentity() {
        assertArrayEquals(orange, ColorMath.apply(ColorMath.matrix(0f, 1f), orange), 1e-5f)
    }

    @Test
    fun noSaturationIsGreyOfTheSameLuma() {
        val grey = ColorMath.apply(ColorMath.matrix(0f, 0f), orange)
        val luma = 0.2126f * 0.8f + 0.7152f * 0.5f + 0.0722f * 0.2f
        assertArrayEquals(floatArrayOf(luma, luma, luma), grey, 1e-5f)
    }

    @Test
    fun oneStopUpIsTwiceTheLight() {
        // Gamma-encoded ×2^(1/2.2): back in light (^2.2) that is ×2.
        val up = ColorMath.apply(ColorMath.matrix(1f, 1f), floatArrayOf(0.4f, 0.4f, 0.4f))
        assertEquals(2.0, Math.pow(up[0].toDouble(), 2.2) / Math.pow(0.4, 2.2), 1e-4)
    }
}
