package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFadeTest {
    @Test
    fun joinsFadeOutAndIn() {
        val first = AudioFade.of(0, 2, 2_000_000)
        val second = AudioFade.of(1, 2, 3_000_000)
        assertEquals(0f, first.gain(0), 1e-6f) // the clip's start: from silence…
        assertEquals(1f, first.gain(20_000), 1e-6f) // …in 20 ms
        assertEquals(1f, first.gain(1_000_000), 1e-6f)
        assertEquals(1f, first.gain(1_900_000), 1e-6f) // the join's fade starts 0.1 s before the end
        assertTrue(first.gain(1_950_000) in 0.6f..0.8f)
        assertEquals(0f, first.gain(2_000_000), 1e-6f)
        assertEquals(0f, second.gain(0), 1e-6f) // the next piece comes in over 0.1 s
        assertEquals(1f, second.gain(100_000), 1e-6f)
        assertEquals(0f, second.gain(3_000_000), 1e-6f) // the clip's end
        assertEquals(1f, second.gain(2_980_000), 1e-6f)
    }

    @Test
    fun aShortPieceStillFades() {
        val f = AudioFade.of(1, 3, 150_000) // shorter than both fades together
        for (t in 0..150_000L step 1_000) assertTrue(f.gain(t) in 0f..1f)
        assertTrue(f.gain(75_000) > 0.5f)
    }
}
