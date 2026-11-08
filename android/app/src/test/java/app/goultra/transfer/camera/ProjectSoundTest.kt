package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** The project's sound made anew for a save «as shot» with music: the two levels added, never wrapped round. */
class ProjectSoundTest {
    @Test
    fun `the clips' sound and the music are added at their own levels`() {
        val clips = floatArrayOf(0.5f, -0.5f)
        val music = floatArrayOf(0.2f, 0.2f)
        // 0.5·0.35 + 0.2·1 = 0.375; −0.5·0.35 + 0.2 = 0.025.
        val out = ProjectSound.mixed(clips, 0.35f, music, 1f, 2)
        assertEquals((0.375f * 32767).roundToInt().toShort(), out[0])
        assertEquals((0.025f * 32767).roundToInt().toShort(), out[1])
    }

    @Test
    fun `too loud a sum is clipped, not wrapped round to the other side`() {
        val out = ProjectSound.mixed(floatArrayOf(0.9f, -0.9f), 1f, floatArrayOf(0.9f, -0.9f), 1f, 2)
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals((-32767).toShort(), out[1])
    }

    @Test
    fun `at zero the clips are silent and only the music is heard`() {
        val out = ProjectSound.mixed(floatArrayOf(1f), 0f, floatArrayOf(0.5f), 1f, 1)
        assertEquals(ProjectSound.mixed(floatArrayOf(0f), 1f, floatArrayOf(0.5f), 1f, 1)[0], out[0])
    }
}
