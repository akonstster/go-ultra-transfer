package app.goultra.transfer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAutoplayTest {
    @Test
    fun playsOnceAfterActivePageAndSourceAreReady() {
        val gate = LiveAutoplay()
        assertFalse(gate.update(active = false, foreground = true, ready = true, editing = false))
        assertFalse(gate.update(active = true, foreground = true, ready = false, editing = false))
        assertTrue(gate.update(active = true, foreground = true, ready = true, editing = false))
        assertFalse(gate.update(active = true, foreground = true, ready = true, editing = false))
    }

    @Test
    fun doesNotRestartAfterBackgroundButDoesAfterLeavingThePage() {
        val gate = LiveAutoplay()
        assertTrue(gate.update(active = true, foreground = true, ready = true, editing = false))
        assertFalse(gate.update(active = true, foreground = false, ready = true, editing = false))
        assertFalse(gate.update(active = true, foreground = true, ready = true, editing = false))
        assertFalse(gate.update(active = false, foreground = true, ready = true, editing = false))
        assertTrue(gate.update(active = true, foreground = true, ready = true, editing = false))
    }

    @Test
    fun waitsForForegroundAndDoesNotInterruptEditing() {
        val gate = LiveAutoplay()
        assertFalse(gate.update(active = true, foreground = false, ready = true, editing = false))
        assertFalse(gate.update(active = true, foreground = true, ready = true, editing = true))
        assertTrue(gate.update(active = true, foreground = true, ready = true, editing = false))
    }

    @Test
    fun manualActionSuppressesDelayedAutoplay() {
        val gate = LiveAutoplay()
        assertFalse(gate.update(active = true, foreground = true, ready = false, editing = false))
        gate.suppress()
        assertFalse(gate.update(active = true, foreground = true, ready = true, editing = false))
    }
}
