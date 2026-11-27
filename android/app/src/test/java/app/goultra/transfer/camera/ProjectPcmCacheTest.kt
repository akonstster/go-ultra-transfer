package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectPcmCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a cold seek decodes from zero and later seeks reuse the exact source frames`() {
        var opened = 0
        var next = 0L
        val cache = ProjectPcmCache(folder.root) {
            opened++
            object : ClipAudio.PcmSource {
                override val rate = 1_000
                override val channels = 2
                override val durationMs = 2_000L
                override val delayFrames = 7

                override fun read(from: Long, count: Int): ShortArray {
                    assertEquals(next, from)
                    next += count
                    return ShortArray(count * channels) { i -> ((from + i / channels) * 2 + i % channels).toShort() }
                }

                override fun release() = Unit
            }
        }
        val first = cache.open("track")!!
        try {
            assertArrayEquals(shortArrayOf(1800, 1801, 1802, 1803), first.read(900, 2))
        } finally {
            first.release()
        }
        val reopened = cache.open("track")!!
        try {
            assertArrayEquals(shortArrayOf(16, 17, 18, 19), reopened.read(8, 2))
            assertArrayEquals(shortArrayOf(1800, 1801, 1802, 1803), reopened.read(900, 2))
            assertEquals(902L, next)
            assertEquals(1, opened)
        } finally {
            reopened.release()
        }
    }
}
