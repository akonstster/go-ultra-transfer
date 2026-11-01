package app.goultra.transfer.ui

import app.goultra.transfer.camera.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePhotoFileTest {
    private val file = CameraFile(
        uri = "/phone/LIV_20261030_111656_149.jpg",
        size = 123,
        durationSec = null,
        width = 4096,
        height = 3072,
        fps = null,
        created = null,
        localUri = "content://media/old",
    )

    @Test
    fun usesTheCurrentMediaStoreItemAndSizeAfterReplacement() {
        val current = currentLivePhoto(file, "content://media/new", 456)
        assertEquals("content://media/new", current.localUri)
        assertEquals(456L, current.size)
        assertEquals(file.uri, current.uri)
        assertEquals("content://media/old", file.localUri)
    }

    @Test
    fun keepsTheOriginalFileIfNoLiveCopyWasFound() {
        assertSame(file, currentLivePhoto(file, null, null))
    }

    @Test
    fun restorationRequiresALocalCopyEvenWhenTheOldFlagIsSet() {
        assertFalse(canRestoreLiveCover(false, 0, false, true))
        assertFalse(canRestoreLiveCover(false, 100, true, true))
        assertFalse(canRestoreLiveCover(true, 0, false, false))
        assertFalse(canRestoreLiveCover(true, 0, true, true))
        assertTrue(canRestoreLiveCover(true, 100, true, false))
        assertTrue(canRestoreLiveCover(true, 0, false, true))
    }
}
