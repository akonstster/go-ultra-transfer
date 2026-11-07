package app.goultra.transfer.ui

import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic a drag runs on: where a slot is drawn, which slot is under the finger, how the strip slides at its
 * ends and where a card lands in the order. Pure, so it is checked here rather than guessed at on the phone.
 */
class ProjectDragTest {
    // 100 px cards, 10 px apart, 20 px at either end, a 1000 px strip of 20 cards.
    private fun geometry(k: Float = 1f) = StripGeometry(100f, 10f, 20f, 1000f, 20, k)

    @Test
    fun `a slot under a point is the inverse of where the slot is drawn`() {
        val g = geometry()
        assertEquals(70f, g.center(0f, 0f), 0.01f)
        assertEquals(400f, g.center(3f, 0f), 0.01f)
        assertEquals(3f, g.slotAt(400f, 0f), 0.01f)
        assertEquals(2.5f, g.slotAt(g.center(2.5f, 137f), 137f), 0.001f)
    }

    @Test
    fun `the strip gathers about the point under the finger`() {
        // The slot under the finger at full size stays under it at the carried size: the scroll makes up the change.
        val big = geometry()
        val scroll = 300f
        val finger = 450f
        val slot = big.slotAt(finger, scroll)
        val small = geometry(0.6f)
        val s = small.scrollFor(slot, finger)
        assertEquals(finger, small.center(slot, s), 0.01f)
        // Smaller cards: more of them across the same strip.
        assertTrue(small.slotAt(1000f, s) - small.slotAt(0f, s) > big.slotAt(1000f, scroll) - big.slotAt(0f, scroll))
    }

    @Test
    fun `the scroll never goes past the strip's ends`() {
        val g = geometry()
        assertEquals(0f, g.scrollFor(0f, 900f), 0f)
        // 2·20 + 19·110 + 100 − 1000 = 1230 px of content beyond the screen.
        assertEquals(1230f, g.maxScroll, 0.01f)
        assertEquals(1230f, g.scrollFor(19f, 0f), 0.01f)
        assertEquals(0f, StripGeometry(100f, 10f, 20f, 1000f, 3, 1f).maxScroll, 0f) // fits: nothing to scroll
    }

    @Test
    fun `a gathered strip keeps the carried card's slot under it even off its ends`() {
        // Three small cards fit the screen: held clamped, they would slide to the start and another slot would come
        // under a card held at 800 px. With slack, the slot stays where it was.
        val g = StripGeometry(100f, 10f, 20f, 1000f, 3, 0.6f)
        val s = g.scrollFor(2f, 800f, slack = 1000f)
        assertEquals(800f, g.center(2f, s), 0.01f)
        assertEquals(0f, g.scrollFor(2f, 800f), 0f) // without it, the strip goes to its start
    }

    @Test
    fun `the strip slides only near its ends, easing in, toward that end`() {
        assertEquals(0f, edgeSpeed(500f, 1000f), 0f)
        assertEquals(0f, edgeSpeed(200f, 1000f), 0f) // just outside the band
        assertTrue(edgeSpeed(100f, 1000f) < 0f)
        assertTrue(edgeSpeed(900f, 1000f) > 0f)
        assertTrue(edgeSpeed(980f, 1000f) > edgeSpeed(900f, 1000f)) // further in = faster
        assertEquals(1400f, edgeSpeed(5000f, 1000f), 0.01f) // and it stops there
        assertEquals(0f, edgeSpeed(10f, 0f), 0f) // not measured yet
    }

    @Test
    fun `a card carried to the right lands just where it was let go of`() {
        val a = ProjectClip("A", 0, 1000)
        val b = ProjectClip("B", 0, 1000)
        val c = ProjectClip("C", 0, 1000)
        val p = Project("p", "p", clips = listOf(a, b, c))
        // A let go of between B and C: [B, A, C], not one place further on.
        val to = sequenceIndexFor(listOf(b, c), 1)
        assertEquals(listOf(b, a, c), p.movedSequence(0, to).clips)
        // To the very end, and to the very start.
        assertEquals(listOf(b, c, a), p.movedSequence(0, sequenceIndexFor(listOf(b, c), 2)).clips)
        assertEquals(listOf(c, a, b), p.movedSequence(2, sequenceIndexFor(listOf(a, b), 0)).clips)
    }

    @Test
    fun `cutaways in the strip do not count toward a card's place in the order`() {
        val cover = ProjectClip("X", 0, 1000, atMs = 500)
        val b = ProjectClip("B", 0, 1000)
        // Before the cutaway and B: still the first place of the sequence.
        assertEquals(0, sequenceIndexFor(listOf(cover, b), 1))
        assertEquals(1, sequenceIndexFor(listOf(cover, b), 2))
    }

    @Test
    fun `the preview's clock counts every piece before the one playing`() {
        val lengths = listOf(3000L, 5000L, 2000L)
        assertEquals(1200L, clockAt(lengths, 0, 1200L))
        assertEquals(8500L, clockAt(lengths, 2, 500L))
        assertEquals(0L, clockAt(emptyList(), 0, 0L))
    }

    @Test
    fun `a seek lands in the piece that holds that time`() {
        val lengths = listOf(3000L, 5000L, 2000L)
        assertEquals(0 to 0L, seekPoint(lengths, 0L))
        assertEquals(1 to 0L, seekPoint(lengths, 3000L)) // the very start of the second piece
        assertEquals(1 to 4500L, seekPoint(lengths, 7500L))
        assertEquals(2 to 2000L, seekPoint(lengths, 99_000L)) // beyond the end: the last piece's end
        assertEquals(0 to 0L, seekPoint(emptyList(), 500L))
    }
}
