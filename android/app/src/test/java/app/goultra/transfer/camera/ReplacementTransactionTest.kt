package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ReplacementTransactionTest {
    @Test
    fun publishesBeforeDeletingBackup() {
        val calls = mutableListOf<String>()
        ReplacementTransaction.commit(
            true,
            { calls += "backup" },
            { calls += "publish" },
            { calls += "discard" },
            { calls += "restore" },
            { calls += "delete backup" },
        )
        assertEquals(listOf("backup", "publish", "delete backup"), calls)
    }

    @Test
    fun publicationFailureDiscardsNewAndRestoresOld() {
        val calls = mutableListOf<String>()
        val failure = IOException("publish failed")
        val thrown = assertThrows(IOException::class.java) {
            ReplacementTransaction.commit(
                true,
                { calls += "backup" },
                { calls += "publish"; throw failure },
                { calls += "discard" },
                { calls += "restore" },
                { calls += "delete backup" },
            )
        }
        assertSame(failure, thrown)
        assertEquals(listOf("backup", "publish", "discard", "restore"), calls)
    }

    @Test
    fun backupFailureAlsoRestoresAndReportsCleanupErrors() {
        val calls = mutableListOf<String>()
        val failure = IOException("backup failed")
        val thrown = assertThrows(IOException::class.java) {
            ReplacementTransaction.commit(
                true,
                { calls += "backup"; throw failure },
                { calls += "publish" },
                { calls += "discard"; throw IOException("discard failed") },
                { calls += "restore"; throw IOException("restore failed") },
                { calls += "delete backup" },
            )
        }
        assertEquals(listOf("backup", "discard", "restore"), calls)
        assertEquals(listOf("discard failed", "restore failed"), thrown.suppressed.map { it.message })
    }

    @Test
    fun backupDeleteFailureKeepsPublishedItem() {
        val calls = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            ReplacementTransaction.commit(
                true,
                { calls += "backup" },
                { calls += "publish" },
                { calls += "discard" },
                { calls += "restore" },
                { calls += "delete backup"; throw IOException("delete failed") },
            )
        }
        assertEquals(listOf("backup", "publish", "delete backup"), calls)
    }

    @Test
    fun publishesWithoutAnOldItem() {
        val calls = mutableListOf<String>()
        ReplacementTransaction.commit(
            false,
            { calls += "backup" },
            { calls += "publish" },
            { calls += "discard" },
            { calls += "restore" },
            { calls += "delete backup" },
        )
        assertEquals(listOf("publish"), calls)
    }

    @Test
    fun failedPublicationLeavesOldContentsAvailable() {
        val files = mutableMapOf("original" to "old bytes", "pending" to "new bytes")
        assertThrows(IOException::class.java) {
            ReplacementTransaction.commit(
                true,
                { files["backup"] = files.remove("original")!! },
                { throw IOException("publication failed") },
                { files.remove("pending") },
                { files["original"] = files.remove("backup")!! },
                { files.remove("backup") },
            )
        }
        assertEquals(mapOf("original" to "old bytes"), files)
    }

    @Test
    fun failedRollbackStillLeavesOldBytesInBackup() {
        val files = mutableMapOf("original" to "old bytes", "pending" to "new bytes")
        assertThrows(IOException::class.java) {
            ReplacementTransaction.commit(
                true,
                { files["backup"] = files.remove("original")!! },
                { throw IOException("publication failed") },
                { files.remove("pending") },
                { throw IOException("restore failed") },
                { files.remove("backup") },
            )
        }
        assertEquals("old bytes", files["backup"])
    }
}
