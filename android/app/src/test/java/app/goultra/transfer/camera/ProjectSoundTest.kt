package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** The project's sound made anew for a save «as shot» with music: the two levels added, never wrapped round. */
class ProjectSoundTest {
    @Test
    fun `wave byte ranges start at the requested project offset and end at the advertised size`() {
        val raw = ByteArray(44 + 192_000)
        ProjectSound.Wave(emptyList(), 1_000, { error("A gap needs no decoder") }).use { wave ->
            assertEquals(raw.size.toLong(), wave.length)
            var offset = 0
            while (offset < raw.size) offset += wave.read(raw, offset, minOf(7_919, raw.size - offset))
            assertEquals(-1, wave.read(raw, 0, 1))
        }
        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(raw, 0, 4))
        assertEquals(raw.size - 8, header.getInt(4))
        assertEquals(48_000, header.getInt(24))
        assertEquals(raw.size - 44, header.getInt(40))
        for (position in listOf(19, 43, 47, raw.size - 5)) {
            val slice = ByteArray(minOf(77, raw.size - position))
            ProjectSound.Wave(emptyList(), 1_000, { error("A gap needs no decoder") }, position.toLong()).use { wave ->
                var offset = 0
                while (offset < slice.size) offset += wave.read(slice, offset, slice.size - offset)
            }
            assertArrayEquals(raw.copyOfRange(position, position + slice.size), slice)
        }
    }
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
