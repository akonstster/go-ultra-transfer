package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The full-screen turn: a landscape file is turned 90°, a portrait one is left alone. */
class FullScreenRotationTest {

    @Test
    fun landscapeIsWiderThanTall() {
        assertEquals(true, isLandscape(1920, 1080))
    }

    @Test
    fun portraitIsTallerThanWide() {
        assertEquals(false, isLandscape(1080, 1920))
    }

    @Test
    fun squareIsNotLandscape() {
        assertEquals(false, isLandscape(1000, 1000))
    }

    @Test
    fun unknownSizeIsNull() {
        assertEquals(null, isLandscape(null, 1080))
        assertEquals(null, isLandscape(1920, null))
        assertEquals(null, isLandscape(0, 0))
    }
}
