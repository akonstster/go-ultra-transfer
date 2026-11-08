package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of a track's analysis — the decode itself is the phone's own decoder ([TrackAudio.analyse], which a
 * JVM test cannot run): the wave's columns, the one channel at [TrackAudio.RATE], and the grid carried on past the share
 * that was listened to.
 */
class TrackAudioTest {
    @Test
    fun `a column is the loudest of its share of the decoded peaks`() {
        val columns = TrackAudio.columns(floatArrayOf(0.1f, 0.9f, 0.2f, 0.4f), buckets = 2)
        assertEquals(2, columns.size)
        assertEquals(0.9f, columns[0], 1e-6f)
        assertEquals(0.4f, columns[1], 1e-6f)
    }

    @Test
    fun `a track with fewer peaks than the lane has columns repeats its last one, never divides by zero`() {
        val columns = TrackAudio.columns(floatArrayOf(0.5f, 0.25f, 0.75f))
        assertEquals(TrackAudio.BUCKETS, columns.size)
        assertEquals(0.5f, columns.first(), 1e-6f)
        assertEquals(0.75f, columns.last(), 1e-6f)
    }

    @Test
    fun `no peaks is an empty wave, and silence is a flat line`() {
        assertTrue(TrackAudio.columns(FloatArray(0)).isEmpty())
        assertTrue(TrackAudio.columns(floatArrayOf(0f, 0f)).all { it == 0f })
    }

    @Test
    fun `the lane shows the share of the track the project plays`() {
        // 25 ms per peak: 0…100 ms is the first four, loud in the second; 50…150 ms the next four.
        val peaks = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        assertEquals(listOf(0.9f, 0.3f), TrackAudio.window(peaks, 0, 100, 2).toList())
        assertEquals(listOf(0.3f, 0.5f), TrackAudio.window(peaks, 50, 150, 2).toList())
        // Past the end the track is silent, not cut short.
        assertEquals(listOf(0.7f, 0f), TrackAudio.window(peaks, 150, 250, 2).toList())
        assertTrue(TrackAudio.window(peaks, 100, 100, 2).isEmpty())
    }

    @Test
    fun `frames are averaged into one at the analysis rate, the channels mixed`() {
        // Two stereo frames — (30000, 30000) and (1000, 1000) — become one sample: their average over 32768.
        val mono = TrackAudio.mono(shortArrayOf(30000, 30000, 1000, 1000), channels = 2, step = 2)
        assertEquals(1, mono.size)
        assertEquals(15500f / 32768f, mono[0], 1e-6f)
    }

    @Test
    fun `the two channels are mixed, not added up`() {
        // (32767, −32767) is silence once mixed: a stereo track must not come out louder than it went in.
        val mono = TrackAudio.mono(shortArrayOf(32767, -32767), channels = 2, step = 1)
        assertEquals(0f, mono[0], 1e-6f)
    }

    @Test
    fun `the grid runs on to the end of the track at the period it was found with`() {
        val grid = BeatGrid.Grid(bpm = 120.0, offsetMs = 0L, beats = listOf(0L, 500L, 1000L), confidence = 0.9)
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L), TrackAudio.beatsOver(grid, 2500L))
        // One beat says nothing about a period: the grid is left as it is.
        assertEquals(listOf(0L), TrackAudio.beatsOver(BeatGrid.Grid(120.0, 0L, listOf(0L), 0.9), 10_000L))
    }
}