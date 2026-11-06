package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema-less protobuf parse must survive a body the camera cut short: a large file-list page came through
 * truncated (a field claiming more bytes than were left), and a throw there used to lose the whole list (06.11.2026).
 */
class PbTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `a complete body parses every field`() {
        // {1: "a", 1: "bb"} — two length-delimited fields.
        val fields = hex("0a 01 61 0a 02 62 62").pbFields()
        assertEquals(listOf("a", "bb"), fields.mapNotNull { it.string })
    }

    @Test
    fun `a truncated tail keeps what came before`() {
        // {1: "a"} + a field claiming 9 bytes with only 2 left (the camera cuts a large page's tail).
        val fields = hex("0a 01 61 0a 09 62 62").pbFields()
        assertEquals(listOf("a"), fields.mapNotNull { it.string })
    }

    @Test
    fun `a body ending inside a varint or tag does not throw`() {
        assertTrue(hex("0a 01").pbFields().isEmpty())
        assertTrue(hex("0a 05 61").pbFields().isEmpty())
        assertTrue(hex("ff ff ff").pbFields().isEmpty())
    }
}