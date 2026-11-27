package app.goultra.transfer.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import app.goultra.transfer.camera.ProjectSound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatAudioTest {
    @Test
    fun `seeking into a click yields the same PCM samples as continuous playback`() {
        val mix = BeatMix(listOf(500L, 875L), musicGain = 1f)
        val click = beatClick(ProjectSound.RATE)
        val process = { pcm: ByteBuffer, at: Long ->
            mixBeatBuffer(pcm, at, ProjectSound.RATE, ProjectSound.CHANNELS, false, click, mix)
        }
        fun read(position: Int, bytes: Int): ByteArray =
            ProjectSound.Wave(emptyList(), 1_000, { error("No source") }, position.toLong(), process).use { wave ->
                ByteArray(bytes).also { output ->
                    var at = 0
                    while (at < output.size) {
                        val count = wave.read(output, at, output.size - at)
                        check(count > 0)
                        at += count
                    }
                }
            }
        val continuous = read(0, 192_044)
        val start = 44 + 192 * 508 + 1
        val end = 44 + 192 * 900
        assertArrayEquals(continuous.copyOfRange(start, end), read(start, end - start))
    }
    @Test
    fun `the click shares the music samples and crosses audio buffer boundaries exactly once`() {
        val mix = BeatMix(listOf(5L), musicGain = 0.5f)
        val click = floatArrayOf(0.25f, -0.25f, 0.125f)
        fun chunk(start: Long): List<Int> {
            val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until 6) buffer.putShort(index * 2, 2000)
            mixBeatBuffer(buffer, start, 1000, 2, false, click, mix)
            return (0 until 6).map { buffer.getShort(it * 2).toInt() }
        }
        assertEquals(listOf(1000, 1000, 1000, 1000, 9192, 9192), chunk(3))
        assertEquals(listOf(-7192, -7192, 5096, 5096, 1000, 1000), chunk(6))
    }

    @Test
    fun `muting the music leaves the click on its exact sample`() {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 4) buffer.putFloat(index * 4, 0.9f)
        mixBeatBuffer(buffer, 10, 1000, 1, true, floatArrayOf(0.2f), BeatMix(listOf(12L), musicGain = 0f, clickGain = 0.5f))
        assertEquals(listOf(0f, 0f, 0.1f, 0f), (0 until 4).map { buffer.getFloat(it * 4) })
    }
}
