package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectExportTest {
    private fun clip(file: String, from: Long, to: Long, still: Boolean = false) =
        ProjectClip(file, from, to, role = if (still) ProjectClip.Role.STILL else ProjectClip.Role.TAKE)

    @Test
    fun `pieces of one file in order go as one run`() {
        val runs = ProjectExport.runs(listOf(clip("A", 0, 1000), clip("A", 2000, 3000), clip("B", 0, 500)))
        assertEquals(listOf("A", "B"), runs.map { it.file })
        assertEquals(listOf(Highlight(0, 1000), Highlight(2000, 3000)), runs[0].pieces)
        // Each run knows its first card: an error names the card the user sees.
        assertEquals(listOf(0, 2), runs.map { it.first })
    }

    @Test
    fun `a piece moved before another of its file starts a new run`() {
        // The cutter only goes forward through a file: an earlier piece after a later one is read again.
        val runs = ProjectExport.runs(listOf(clip("A", 2000, 3000), clip("A", 0, 1000)))
        assertEquals(2, runs.size)
        // Overlapping counts as going back too.
        assertEquals(2, ProjectExport.runs(listOf(clip("A", 0, 3000), clip("A", 2000, 4000))).size)
        // Touching does not.
        assertEquals(1, ProjectExport.runs(listOf(clip("A", 0, 1000), clip("A", 1000, 2000))).size)
    }

    @Test
    fun `the same file further on, after another, is a run of its own`() {
        val runs = ProjectExport.runs(listOf(clip("A", 0, 1000), clip("B", 0, 1000), clip("A", 2000, 3000)))
        assertEquals(listOf("A", "B", "A"), runs.map { it.file })
    }

    @Test
    fun `a project with a photo cannot be copied as shot`() {
        assertTrue(ProjectExport.canCopy(listOf(clip("A", 0, 1000))))
        assertFalse(ProjectExport.canCopy(listOf(clip("A", 0, 1000), clip("P.jpg", 0, 3000, still = true))))
        assertFalse(ProjectExport.canCopy(emptyList()))
    }

    @Test
    fun `a project's name becomes a file name every gallery takes`() {
        assertEquals("Trip_to_the_sea", ProjectExport.fileStem("  Trip to the sea "))
        assertEquals("a_b_c", ProjectExport.fileStem("a/b:c"))
        assertEquals("Project", ProjectExport.fileStem("  ///  "))
    }

    private fun sig(mime: String = "video/hevc", w: Int = 1920, setup: String = "x", audio: String? = "audio/mp4a-latm") =
        ClipMaker.Signature(mime, w, 1080, 0, setup, audio, 48000, 2)

    @Test
    fun `files join as shot only with the same formats`() {
        assertNull(ClipMaker.mismatch(sig(), sig()))
        assertEquals(ClipMaker.Mismatch.CODEC, ClipMaker.mismatch(sig(), sig(mime = "video/avc")))
        assertEquals(ClipMaker.Mismatch.SIZE, ClipMaker.mismatch(sig(), sig(w = 3840)))
        assertEquals(ClipMaker.Mismatch.SETUP, ClipMaker.mismatch(sig(), sig(setup = "y")))
        assertEquals(ClipMaker.Mismatch.SOUND, ClipMaker.mismatch(sig(), sig(audio = null)))
    }
}
