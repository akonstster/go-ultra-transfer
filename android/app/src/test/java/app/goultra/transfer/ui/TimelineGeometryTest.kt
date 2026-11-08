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
    fun `a drag to the right brings the track earlier, and neither way goes past its limits`() {
        // 0.1 px per ms on this timeline: a hundred pixels are a second.
        assertMs(3000L, draggedOffset(4000L, dxPx = 100f, pxPerMs = 0.1f, maxOffsetMs = 20_000L), delta = 0L)
        assertMs(5000L, draggedOffset(4000L, dxPx = -100f, pxPerMs = 0.1f, maxOffsetMs = 20_000L), delta = 0L)
        assertMs(0L, draggedOffset(1350L, dxPx = 10_000f, pxPerMs = 0.1f, maxOffsetMs = 20_000L), delta = 0L)
        assertMs(20_000L, draggedOffset(19_000L, dxPx = -10_000f, pxPerMs = 0.1f, maxOffsetMs = 20_000L), delta = 0L)
        // No zoom yet (a lane not measured): the offset stays where it was, still inside its limits.
        assertMs(700L, draggedOffset(700L, dxPx = 400f, pxPerMs = 0f, maxOffsetMs = 20_000L), delta = 0L)
        assertMs(0L, draggedOffset(-50L, dxPx = 0f, pxPerMs = 0f, maxOffsetMs = 20_000L), delta = 0L)
    }
}