package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewProxyCacheTest {
    @Test
    fun `large videos get a 540-line copy and small ones do not`() {
        assertTrue(PreviewProxyCache.needsProxy(3840, 2160))
        assertTrue(PreviewProxyCache.needsProxy(1920, 1080))
        assertTrue(PreviewProxyCache.needsProxy(1280, 720))
        assertTrue(PreviewProxyCache.needsProxy(720, 1280))
        assertFalse(PreviewProxyCache.needsProxy(960, 540))
        assertFalse(PreviewProxyCache.needsProxy(640, 480))
    }

    @Test
    fun `unknown dimensions are inspected instead of guessed`() {
        assertFalse(PreviewProxyCache.needsProxy(null, 1080))
        assertFalse(PreviewProxyCache.needsProxy(1920, null))
        assertFalse(PreviewProxyCache.needsProxy(0, 0))
    }

    @Test
    fun `the copy keeps the orientation and fits 960 by 540`() {
        assertEquals(960 to 540, PreviewProxyCache.size(1920, 1080))
        assertEquals(960 to 540, PreviewProxyCache.size(3840, 2160))
        assertEquals(540 to 960, PreviewProxyCache.size(1080, 1920))
        // The pair is the box Presentation fits into; a square becomes 540x540 inside it, not a stretched frame.
        assertEquals(960 to 540, PreviewProxyCache.size(1000, 1000))
    }

    @Test
    fun `a copy is keyed by its source, size and recording time`() {
        val base = PreviewProxyCache.keyFor("content://media/video/1", 100L, 1_700_000_000_000L)
        assertEquals(64, base.length)
        assertEquals(base, PreviewProxyCache.keyFor("content://media/video/1", 100L, 1_700_000_000_000L))
        assertNotEquals(base, PreviewProxyCache.keyFor("content://media/video/1", 101L, 1_700_000_000_000L))
        assertNotEquals(base, PreviewProxyCache.keyFor("content://media/video/1", 100L, 1_700_000_001_000L))
        assertNotEquals(base, PreviewProxyCache.keyFor("content://media/video/2", 100L, 1_700_000_000_000L))
    }
}