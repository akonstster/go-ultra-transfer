package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloaderDateTest {
    @Test
    fun `a stale taken date yields to the later added date`() {
        // The Huawei case: the clip was cut from a 5 November take and saved on 9 November.
        val taken = 1_793_890_609_000L
        val addedSeconds = 1_794_227_588L
        assertEquals(1_794_227_588_000L, Downloader.newestStamp(taken, addedSeconds))
    }

    @Test
    fun `a genuinely newer taken date stays`() {
        assertEquals(1_800_000_000_000L, Downloader.newestStamp(1_800_000_000_000L, 1_794_227_588L))
    }

    @Test
    fun `missing dates stay missing`() {
        assertNull(Downloader.newestStamp(null, null))
        assertEquals(1_794_227_588_000L, Downloader.newestStamp(null, 1_794_227_588L))
    }
}