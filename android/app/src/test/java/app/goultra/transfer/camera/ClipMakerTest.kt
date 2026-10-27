package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cutting rule on a made-up file: 30 fps video with a key frame every second, audio every 20 ms. */
class ClipMakerTest {
    private class Sample(val track: Int, val timeUs: Long, val sync: Boolean)

    private val video = 0
    private val audio = 1

    /** [seconds] of media, interleaved in the file in half-second chunks (video, then audio), as a camera writes it. */
    private class FakeFile(seconds: Int) : ClipMaker.Samples {
        val samples = buildList {
            for (chunk in 0 until seconds * 2) {
                val from = chunk * 500_000L
                for (f in 0 until 15) {
                    val t = from + f * 33_333L
                    add(Sample(0, t, t % 1_000_000L < 33_333L))
                }
                for (a in 0 until 25) add(Sample(1, from + a * 20_000L, true))
            }
        }
        var pos = 0
        override val track get() = if (pos < samples.size) samples[pos].track else -1
        override val timeUs get() = samples[pos].timeUs
        override val isSync get() = samples[pos].sync
        override fun seek(us: Long) {
            pos = samples.indices.last { samples[it].track == 0 && samples[it].sync && samples[it].timeUs <= us }
        }
        override fun advance() { pos++ }
    }

    private class Written(val track: Int, val clipUs: Long, val sync: Boolean)

    private fun cut(pieces: List<Highlight>, seconds: Int = 10): Pair<List<Highlight>, List<Written>> {
        val written = mutableListOf<Written>()
        val cut = ClipMaker.cut(FakeFile(seconds), video, pieces, { track, us, sync -> written += Written(track, us, sync) })
        return cut to written
    }

    private fun assertSound(written: List<Written>, durationUs: Long) {
        for (track in listOf(video, audio)) {
            val times = written.filter { it.track == track }.map { it.clipUs }
            assertEquals("track $track in order, no frame twice", times.sorted().distinct(), times)
            assertTrue(times.all { it in 0 until durationUs })
        }
        assertTrue("starts on a key frame", written.first { it.track == video }.let { it.sync && it.clipUs == 0L })
    }

    @Test
    fun onePieceGrowsToKeyFrames() {
        val (cut, written) = cut(listOf(Highlight(2500, 4200)))
        assertEquals(listOf(Highlight(2000, 5000)), cut)
        assertEquals(90, written.count { it.track == video })
        assertEquals(150, written.count { it.track == audio })
        assertSound(written, 3_000_000)
    }

    @Test
    fun piecesInOneGopJoinWithoutRepeats() {
        val (cut, written) = cut(listOf(Highlight(1200, 1800), Highlight(2100, 2600)))
        assertEquals(listOf(Highlight(1000, 2000), Highlight(2000, 3000)), cut)
        assertSound(written, 2_000_000)
        assertEquals(60, written.count { it.track == video })
    }

    @Test
    fun aPieceInsideThePreviousTailIsSkipped() {
        val (cut, _) = cut(listOf(Highlight(1200, 1500), Highlight(1600, 1900)))
        assertEquals(listOf(Highlight(1000, 2000)), cut)
    }

    @Test
    fun separatePiecesFollowEachOther() {
        val (cut, written) = cut(listOf(Highlight(1000, 2000), Highlight(6500, 7500)))
        assertEquals(listOf(Highlight(1000, 2000), Highlight(6000, 8000)), cut)
        assertSound(written, 3_000_000)
        // The second piece's key frame sits right after the first piece in the clip.
        assertTrue(written.any { it.track == video && it.sync && it.clipUs == 1_000_000L })
    }

    @Test
    fun aPieceRunningPastTheEndStopsAtTheLastFrame() {
        val (cut, written) = cut(listOf(Highlight(9500, 12000)))
        // The last frame (9.966 s) plus one frame; 33 333 µs frames make it 9.999 s, not 10.
        assertEquals(listOf(Highlight(9000, 9999)), cut)
        assertSound(written, 1_000_000)
    }
}
