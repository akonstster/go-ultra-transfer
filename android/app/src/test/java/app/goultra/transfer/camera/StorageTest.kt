package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The storage notifications and the camera's refusals, byte for byte as captured on a GO Ultra (fw v1.9.29,
 * 05.11.2026 — docs/PROTOCOL.md, «Card states and errors»): the app warns from the state, never from a «200».
 */
class StorageTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `8198 carries the card state`() {
        assertEquals(CardState.OK, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 00 10 00"))?.state)
        assertEquals(CardState.NO_CARD, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 01 10 00"))?.state)
        assertEquals(CardState.FULL, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 02 10 00"))?.state)
        assertEquals(CardState.UNUSABLE, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 03 10 00"))?.state)
        assertEquals(CardState.WRITE_ERROR, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 05 10 00"))?.state)
        // 4 was never seen on hardware: it must not turn into a wrong warning.
        assertEquals(CardState.UNKNOWN, StorageEvent.parse(Proto.NOTIFY_STORAGE_UPDATE, hex("08 04 10 00"))?.state)
    }

    @Test
    fun `8201 carries the refusal reason`() {
        assertEquals(2, StorageEvent.parse(Proto.NOTIFY_CAPTURE_STOPPED, hex("08 02"))?.stopReason) // no card
        assertEquals(11, StorageEvent.parse(Proto.NOTIFY_CAPTURE_STOPPED, hex("08 0b"))?.stopReason) // can't write
    }

    @Test
    fun `8290 carries the sizes with the 10 percent reserve`() {
        // A 128 GB card: total 127 831 900 160, free 127 768 330 240, reserve exactly a tenth of the card.
        val e = StorageEvent.parse(
            Proto.NOTIFY_CARD_INFO,
            hex("08 80 80 80 9b dc 03 10 80 80 d8 fc db 03 18 80 80 c0 cf 2f 20 00"),
        )!!
        assertEquals(127_831_900_160L, e.totalBytes)
        assertEquals(127_768_330_240L, e.freeBytes)
        assertEquals(12_783_190_016L, e.reserveBytes)
        assertEquals(e.totalBytes!! / 10, e.reserveBytes!!)
    }

    @Test
    fun `a photo refusal is the camera's words, a uri is not`() {
        // The captured reply to a photo with no card: 1a 0c 0a 0a «No SD card».
        assertEquals("No SD card", replyRefusal(hex("1a 0c 0a 0a 4e 6f 20 53 44 20 63 61 72 64")))
        // A file uri — an unusable card answers exactly like a good one, which is why the state is the truth.
        val uri = "/DCIM/Camera01/LIV_20261105_230425_001.jpg"
        val body = byteArrayOf(0x1a, (uri.length + 2).toByte(), 0x0a, uri.length.toByte()) + uri.toByteArray()
        assertNull(replyRefusal(body))
    }
}