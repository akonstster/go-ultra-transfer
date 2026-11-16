package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCacheSettingsTest {
    @Test
    fun `the default is one of the sizes Settings offers`() {
        assertEquals(2048, AppPrefs.PREVIEW_CACHE_DEFAULT_MB)
        assertTrue(AppPrefs.PREVIEW_CACHE_DEFAULT_MB in AppPrefs.PREVIEW_CACHE_MB)
    }

    @Test
    fun `the choices are half a gigabyte, then whole gigabytes`() {
        assertEquals(listOf(512, 1024, 2048, 4096, 8192), AppPrefs.PREVIEW_CACHE_MB)
    }
}