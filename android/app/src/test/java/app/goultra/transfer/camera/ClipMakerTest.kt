package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cutting rule on a made-up file: 30 fps video with a key frame every second, audio every 20 ms. */
class ClipMakerTest {
    private class Sample(val track: Int, val timeUs: Long, val sync: Boolean)

    private val video = 0
    private val audio = 1

    /**
     * [seconds] of media, interleaved in the file in half-second chunks (video, then audio), as a camera writes it.
     * [bFrames]: inside a chunk, frames 1–2, 3–4 … are stored swapped (decode order ≠ display order).
     */
    private class FakeFile(seconds: Int, bFrames: Boolean = false) : ClipMaker.Samples {
        val samples = buildList {
            for (chunk in 0 until seconds * 2) {
                val from = chunk * 500_000L
                val order = (0 until 15).toMutableList()
                if (bFrames) for (i in 1 until 14 step 2) java.util.Collections.swap(order, i, i + 1)
                for (f in order) {
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

    private fun cut(pieces: List<Highlight>, seconds: Int = 10, bFrames: Boolean = false): Pair<List<Highlight>, List<Written>> {
        val written = mutableListOf<Written>()
        val cut = ClipMaker.cut(FakeFile(seconds, bFrames), video, pieces, { track, us, sync -> written += Written(track, us, sync) })
        return cut to written
    }

    private fun assertSound(written: List<Written>, durationUs: Long) {
        for (track in listOf(video, audio)) {
            val times = written.filter { it.track == track }.map { it.clipUs }
            assertEquals("track $track in order, no frame twice", times.sorted().distinct(), times)
            assertTrue(times.all { it in 0 until durationUs })
        }
        assertTrue("starts on a key frame", written.first { it.track == video }.let { it.sync && it.clipUs == 0L })
        // Sound from the key frame on, not only from the piece's marked start.
        assertTrue("sound from the start", written.first { it.track == audio }.clipUs == 0L)
    }

    @Test
    fun onePieceStartsOnAKeyFrameAndEndsWhereMarked() {
        // Frames every 33.3 ms: the first one at or after 4.2 s is at 4.233 s.
        val (cut, written) = cut(listOf(Highlight(2500, 4200)))
        assertEquals(listOf(Highlight(2000, 4233)), cut)
        assertEquals(67, written.count { it.track == video })
        assertSound(written, 2_233_332)
    }

    @Test
    fun threeShortPiecesStayShort() {
        // The case from the phone: three short pieces must not grow to most of a 10 s video.
        val (cut, written) = cut(listOf(Highlight(1300, 1800), Highlight(4400, 4900), Highlight(7600, 8100)))
        // Each ends on the first frame at or after its mark (frames every 33.3 ms).
        assertEquals(listOf(Highlight(1000, 1833), Highlight(4000, 4933), Highlight(7000, 8133)), cut)
        val clipUs = written.filter { it.track == video }.maxOf { it.clipUs }
        assertTrue("about 3 s, not 8: $clipUs", clipUs < 3_100_000)
        assertSound(written, 3_200_000)
    }

    @Test
    fun aPieceStartingInsideThePreviousOneCarriesOn() {
        // The second piece's key frame (1 s) was already written: it goes on from where the first one ended.
        val (cut, written) = cut(listOf(Highlight(1200, 1800), Highlight(1900, 2600)))
        assertEquals(listOf(Highlight(1000, 2633)), cut)
        assertSound(written, 1_633_332)
        assertEquals(49, written.count { it.track == video })
    }

    @Test
    fun aPieceInsideWhatWasWrittenIsSkipped() {
        val (cut, _) = cut(listOf(Highlight(1200, 1900), Highlight(1500, 1800)))
        assertEquals(listOf(Highlight(1000, 1933)), cut)
    }

    @Test
    fun separatePiecesFollowEachOther() {
        val (cut, written) = cut(listOf(Highlight(1000, 2000), Highlight(6500, 7500)))
        assertEquals(listOf(Highlight(1000, 2000), Highlight(6000, 7500)), cut)
        assertSound(written, 2_500_000)
        // The second piece's key frame sits right after the first piece in the clip.
        assertTrue(written.any { it.track == video && it.sync && it.clipUs == 1_000_000L })
    }

    @Test
    fun withBFramesAPieceEndsOnAKeyFrame() {
        val (cut, _) = cut(listOf(Highlight(2500, 4200)), bFrames = true)
        assertEquals(listOf(Highlight(2000, 5000)), cut)
    }

    @Test
    fun aPieceRunningPastTheEndStopsAtTheLastFrame() {
        val (cut, written) = cut(listOf(Highlight(9500, 12000)))
        // The last frame (9.966 s) plus one frame; 33 333 µs frames make it 9.999 s, not 10.
        assertEquals(listOf(Highlight(9000, 9999)), cut)
        assertSound(written, 1_000_000)
    }
}
