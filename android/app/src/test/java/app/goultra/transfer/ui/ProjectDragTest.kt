package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic a drag runs on: the stick's speed and the slot under the eye. It used to live inside the screen's
 * lambdas and a wrong sum there threw the carried card about — so it is pure here and checked, not guessed at.
 */
class ProjectDragTest {
    @Test
    fun `the stick stands still in the middle and gets faster the further it goes`() {
        assertEquals(0f, stickSpeed(0f), 0f)
        assertEquals(0f, stickSpeed(20f), 0f) // inside the dead zone
        assertTrue(stickSpeed(60f) > 0f)
        assertTrue(stickSpeed(-60f) < 0f) // the other way
        assertTrue(stickSpeed(60f) < stickSpeed(140f)) // further = faster
        assertEquals(900f, stickSpeed(1000f), 0.01f) // and it stops there
        assertEquals(-900f, stickSpeed(-1000f), 0.01f)
    }

    @Test
    fun `the slot is the one nearest the given point`() {
        // 160 px per slot, padded by 100: the slot under a point is simply the nearest one on that grid.
        val pitch = 160f
        val pad = 100f
        assertEquals(0, slotAt(100f, pitch, pad, 5))
        assertEquals(0, slotAt(179f, pitch, pad, 5)) // rounding at the midpoint
        assertEquals(1, slotAt(181f, pitch, pad, 5))
        assertEquals(4, slotAt(900f, pitch, pad, 5)) // and the ends hold
        assertEquals(0, slotAt(-500f, pitch, pad, 5))
    }

    @Test
    fun `a slot is answered for even when the strip has not measured itself`() {
        assertEquals(0, slotAt(100f, 0f, 0f, 0)) // no pitch, no cards
        assertEquals(0, slotAt(100f, 160f, 0f, 0))
    }
}