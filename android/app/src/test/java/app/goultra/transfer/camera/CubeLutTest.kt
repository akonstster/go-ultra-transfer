package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** A 2×2×2 table that inverts colours, written as a .cube file is: red changing fastest. */
class CubeLutTest {
    private val invert = buildString {
        appendLine("TITLE \"invert\"")
        appendLine("LUT_3D_SIZE 2")
        appendLine("DOMAIN_MIN 0 0 0")
        appendLine("DOMAIN_MAX 1 1 1")
        for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${1 - r} ${1 - g} ${1 - b}")
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun axesAreRedGreenBlue() {
        val lut = CubeLut.parse(invert)
        assertEquals("invert", lut.title)
        val cube = lut.cube(1f)
        assertEquals(argb(0, 255, 255), cube[1][0][0]) // pure red → cyan
        assertEquals(argb(255, 0, 255), cube[0][1][0]) // green → magenta
        assertEquals(argb(255, 255, 0), cube[0][0][1]) // blue → yellow
    }

    @Test
    fun strengthMixesWithNoChange() {
        val lut = CubeLut.parse(invert)
        assertEquals(argb(255, 0, 0), lut.cube(0f)[1][0][0]) // as shot
        assertEquals(argb(128, 128, 128), lut.cube(0.5f)[1][0][0]) // half way
    }

    @Test(expected = IllegalArgumentException::class)
    fun aShortTableIsRefused() {
        CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")
    }
}
