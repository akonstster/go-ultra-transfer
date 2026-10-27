package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Records are GetFileExtra(uri, 10) replies from the real camera, 2026-10-27. */
class HighlightsTest {
    private fun bytes(hex: String) = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val emptyTail = "02 00 00 00 00 03 00 00 00 00 04 00 00 00 00 10 00 00 00 00 11 00 00 00 00 12 00 00 00 00 15 00 00 00 00"

    @Test
    fun noMarks() {
        assertEquals(emptyList<Highlight>(), Highlights.parse(bytes("01 00 00 00 00 $emptyTail"), 791230576))
    }

    @Test
    fun recordedMarksWithABrokenOne() {
        // VID_…_119: the first value (1320000) is below first_frame_timestamp; the camera's film strip puts it at 0.
        val record = bytes(
            "01 04 00 00 00 40 24 14 00 00 00 00 00 e7 e6 a5 11 00 00 00 00 67 fa c1 11 00 00 00 00 " +
                "e7 2b d9 11 00 00 00 00 $emptyTail",
        )
        val marks = Highlights.parse(record, 293565223, 9_000)
        assertEquals(listOf(Highlight(0), Highlight(2520), Highlight(4360), Highlight(5880)), marks)
        // On the camera: two pieces, 1 s and 5.3 s long.
        assertEquals(listOf(Highlight(0, 1000), Highlight(1520, 6880)), Highlights.pieces(marks, 10_000))
    }

    @Test
    fun playbackSegments() {
        // VID_…_122: two marks added in the camera's playback at ~3 s and ~7 s; the record holds only types 1 and 2.
        val record = bytes(
            "01 00 00 00 00 02 02 00 00 00 64 82 9e 77 00 00 00 00 e4 06 bd 77 00 00 00 00 " +
                "ac 67 df 77 00 00 00 00 2c ec fd 77 00 00 00 00",
        )
        assertEquals(
            listOf(Highlight(2680, 4680), Highlight(6933, 8933)),
            Highlights.parse(record, 2004196772, 10_000),
        )
    }

    @Test
    fun piecesLikeTheCamera() {
        // Recording marks → 1 s either side, clipped to the video; overlapping pieces merge; playback segments stay.
        assertEquals(
            listOf(Highlight(0, 1500), Highlight(3360, 6880), Highlight(8000, 9000)),
            Highlights.pieces(
                listOf(Highlight(500), Highlight(4360), Highlight(5880), Highlight(8000, 10_000)),
                durationMs = 9_000,
            ),
        )
        assertEquals(listOf(Highlight(3360, 5360), Highlight(9120, 11_120)), Highlights.pieces(listOf(Highlight(4360), Highlight(10_120))))
    }

    @Test
    fun truncatedRecordKeepsWhatParsed() {
        assertEquals(listOf(Highlight(1320)), Highlights.parse(bytes("01 01 00 00 00 40 24 14 00 00 00 00 00 02 05 00 00 00 01"), null))
    }

    @Test
    fun editedPiecesAreTidied() {
        val edited = listOf(Highlight(5000, 7000), Highlight(-200, 900), Highlight(6500, 8000), Highlight(9000, 9100))
        assertEquals(listOf(Highlight(0, 900), Highlight(5000, 8000)), Highlights.normalize(edited, 9_500))
    }

    @Test
    fun editsSurviveStorage() {
        val pieces = listOf(Highlight(0, 900), Highlight(5000, 8000))
        assertEquals(pieces, Highlights.decode(Highlights.encode(pieces)))
        assertEquals(emptyList<Highlight>(), Highlights.decode(Highlights.encode(emptyList())))
    }
}
