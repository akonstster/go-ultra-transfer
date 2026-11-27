package app.goultra.transfer.ui

import app.goultra.transfer.camera.ProjectClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline's arithmetic ([TimelineGeometry]): a moment drawn and read back, the pieces' places, the default zoom,
 * zooming about the fingers, following the clock, and dragging the music along.
 */
class TimelineGeometryTest {
    private fun clip(from: Long, to: Long) = ProjectClip(file = "A.mp4", fromMs = from, toMs = to)

    /** Three pieces — 1 s, 2 s and 1 s — so the project runs 0–4000 and its pieces start at 0, 1000 and 3000. */
    private val clips = listOf(clip(0, 1000), clip(2000, 4000), clip(5000, 6000))
    private val starts = startsOf(clips)
    private val geometry = TimelineGeometry(starts, 4000L, pxPerMs = 0.1f, edgePx = 20f, widthPx = 400f)

    private fun assertMs(expected: Long, actual: Long, delta: Long = 1L) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())

    @Test
    fun `the pieces start where the ones before them end`() {
        assertEquals(listOf(0L, 1000L, 3000L), starts)
        assertEquals(emptyList<Long>(), startsOf(emptyList()))
    }

    @Test
    fun `a moment drawn and read back is the same moment`() {
        for (ms in listOf(0L, 250L, 1000L, 2750L, 3999L)) {
            assertMs(ms, geometry.msAt(geometry.x(ms, scroll = 0f), 0f))
        }
        // And the same with the lane scrolled along.
        for (ms in listOf(500L, 1500L, 3000L)) {
            assertMs(ms, geometry.msAt(geometry.x(ms, scroll = 25f), 25f))
        }
    }

    @Test
    fun `a moment never leaves the project`() {
        assertMs(0L, geometry.msAt(geometry.x(-500L, 0f), 0f))
        assertMs(4000L, geometry.msAt(geometry.x(9000L, 0f), 0f))
        assertMs(0L, geometry.msAt(-10_000f, 0f))
        assertMs(4000L, geometry.msAt(10_000f, 0f))
    }

    @Test
    fun `a piece is the one its moment falls in, the last one at the very end`() {
        assertEquals(0, geometry.pieceAt(0L))
        assertEquals(0, geometry.pieceAt(999L))
        assertEquals(1, geometry.pieceAt(1000L))
        assertEquals(1, geometry.pieceAt(2999L))
        assertEquals(2, geometry.pieceAt(3000L))
        assertEquals(2, geometry.pieceAt(4000L))
        // Nothing to point at: -1, not a crash.
        assertEquals(-1, TimelineGeometry(emptyList(), 0L, 0.1f, 0f, 400f).pieceAt(0L))
    }

    @Test
    fun `the default zoom puts the whole project into the width`() {
        val g = TimelineGeometry(starts, 4000L, fitZoom(4000L, widthPx = 400f, edgePx = 20f), 20f, 400f)
        assertEquals(400.0, g.contentPx.toDouble(), 0.01)
        assertEquals(0.0, g.maxScroll.toDouble(), 0.001)
        assertMs(0L, g.msAt(20f, 0f))
        assertMs(4000L, g.msAt(380f, 0f))
        // A project with nothing in it has no zoom at all (and deletes nothing by dividing by zero).
        assertEquals(0.0, fitZoom(0L, 400f, 20f).toDouble(), 0.0)
        assertMs(0L, TimelineGeometry(emptyList(), 0L, 0f, 20f, 400f).msAt(300f, 0f))
    }

    @Test
    fun `zooming keeps the moment under the fingers under them`() {
        val (next, scroll) = geometry.zoomedAbout(newPxPerMs = 0.5f, focusX = 120f, scroll = 100f)
        assertMs(2000L, geometry.msAt(120f, 100f))
        assertMs(2000L, next.msAt(120f, scroll))
        assertTrue("the scroll went past the content: $scroll of ${next.maxScroll}", scroll in 0f..next.maxScroll)
        // Zoomed out to nothing: the moment is still the one it was, and the lane has room to spare.
        val (flat, atZero) = geometry.zoomedAbout(newPxPerMs = 0f, focusX = 300f, scroll = 0f)
        assertEquals(0.0, atZero.toDouble(), 0.001)
        assertMs(0L, flat.msAt(300f, atZero))
    }

    @Test
    fun `the clock is followed inside what can be scrolled`() {
        assertEquals(0.0, geometry.scrollToKeep(0L, atX = 300f).toDouble(), 0.01)
        assertEquals(geometry.maxScroll.toDouble(), geometry.scrollToKeep(4000L, atX = 100f).toDouble(), 0.01)
        val mid = geometry.scrollToKeep(2000L, atX = 200f)
        assertMs(2000L, geometry.msAt(200f, mid))
        assertTrue(mid in 0f..geometry.maxScroll)
    }

    @Test
    fun `moving the audio clip preserves its source range`() {
        val clip = app.goultra.transfer.camera.ProjectAudioClip(
            "audio", app.goultra.transfer.camera.ProjectMusic("track", lengthMs = 20_000L),
            startMs = 4_000L, fromMs = 3_000L, toMs = 10_000L,
        )
        assertEquals(clip.copy(startMs = 5_000L), editAudio(clip, AudioEdit.MOVE, 1_000L))
        assertEquals(clip.copy(startMs = 0L), editAudio(clip, AudioEdit.MOVE, -9_000L))
    }

    @Test
    fun `trimming the head keeps the tail and trimming the tail keeps the head`() {
        val clip = app.goultra.transfer.camera.ProjectAudioClip(
            "audio", app.goultra.transfer.camera.ProjectMusic("track", lengthMs = 20_000L),
            startMs = 2_000L, fromMs = 3_000L, toMs = 10_000L,
        )
        val extendedHead = editAudio(clip, AudioEdit.START, -9_000L)
        assertEquals(clip.copy(startMs = 0L, fromMs = 1_000L), extendedHead)
        assertEquals(clip.endMs, extendedHead.endMs)
        assertEquals(clip.projectAt(5_000L), extendedHead.projectAt(5_000L))
        assertEquals(clip.copy(toMs = 8_000L), editAudio(clip, AudioEdit.END, -2_000L))
        assertEquals(clip.copy(toMs = null), editAudio(clip, AudioEdit.END, 12_000L))
    }

    @Test
    fun `the tail can pan through the full source even when the trimmed project fits on screen`() {
        val original = app.goultra.transfer.camera.ProjectAudioClip(
            "trimmed", app.goultra.transfer.camera.ProjectMusic("source", lengthMs = 30_000L),
            startMs = 1_000L, fromMs = 2_000L, toMs = 6_000L,
        )
        val g = TimelineGeometry(emptyList(), original.endMs, 0.08f, 10f, 420f)
        assertEquals(0.0, g.maxScroll.toDouble(), 0.01)
        val scroll = endTrimScrollLimit(g, original)
        assertEquals(1_920.0, scroll.toDouble(), 0.01)
        val x = g.rawX(original.projectAt(original.source.lengthMs), scroll)
        assertEquals(original.projectAt(original.source.lengthMs), g.rawMsAt(x, scroll))
    }

    @Test
    fun `the moving beat snaps only inside an overlap and releases beyond the short radius`() {
        fun clip(id: String, start: Long, trim: Long = 0L) = app.goultra.transfer.camera.ProjectAudioClip(
            id, app.goultra.transfer.camera.ProjectMusic("source", lengthMs = 4_000L, beats = (1..7).map { it * 500L }),
            startMs = start, fromMs = trim,
        )
        val still = clip("still", 0L)
        val moving = clip("moving", 1_022L, trim = 500L)
        val snap = snapAudioMove(moving, listOf(still), gripSourceMs = 991L, toleranceMs = 35L)!!
        assertEquals(1_000L, snap.clip.startMs)
        assertEquals(1_500L, snap.timeMs)
        assertEquals(moving.fromMs, snap.clip.fromMs)
        assertEquals(null, snapAudioMove(moving.copy(startMs = 1_037L), listOf(still), 991L, 35L))
        assertEquals(null, snapAudioMove(moving.copy(startMs = 4_002L), listOf(still), 991L, 35L))
    }
}
