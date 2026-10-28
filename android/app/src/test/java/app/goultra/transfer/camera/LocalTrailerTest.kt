package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Trailers built the way the camera lays them out, around the real highlight record of VID_…_119. */
class LocalTrailerTest {
    private fun bytes(hex: String) = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val highlight = bytes(
        "01 04 00 00 00 40 24 14 00 00 00 00 00 e7 e6 a5 11 00 00 00 00 67 fa c1 11 00 00 00 00 " +
            "e7 2b d9 11 00 00 00 00 02 00 00 00 00 03 00 00 00 00 04 00 00 00 00 10 00 00 00 00 " +
            "11 00 00 00 00 12 00 00 00 00 15 00 00 00 00",
    )
    private val metadata = PbWriter().string(1, "serial").varint(10, 9).varint(24, 293565223).toByteArray()
    private val gyro = ByteArray(300) { it.toByte() }

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** mp4 body, then each record as data + `[u16 id][u32 size]`, then (optionally) the table, then the tail. */
    private fun file(records: List<Pair<Int, ByteArray>>, table: Boolean): ByteArray {
        val body = ByteArray(1000) { 0x55 }
        val trailer = ByteArrayOutputStream()
        val entries = ByteArrayOutputStream()
        for ((id, data) in records) {
            val offset = trailer.size()
            trailer.write(data); trailer.write(u16(id)); trailer.write(le32(data.size))
            entries.write(ByteArray(10)) // an empty slot before each entry, as on the GO Ultra
            entries.write(u16(id)); entries.write(le32(data.size)); entries.write(le32(offset))
        }
        if (table) {
            val t = entries.toByteArray()
            trailer.write(t); trailer.write(u16(0)); trailer.write(le32(t.size))
        }
        // The 78-byte tail starts with the last record's footer (6 bytes, already written): 32 more bytes, then the
        // trailer size at −40, the version, the magic.
        trailer.write(ByteArray(32))
        val size = trailer.size() + 4 + 4 + 32
        trailer.write(le32(size)); trailer.write(le32(3)); trailer.write("8db42d694ccc418790edff439fe026bf".toByteArray())
        return body + trailer.toByteArray()
    }

    private fun parse(f: ByteArray) = LocalTrailer.parse(f.size.toLong()) { at, n -> f.copyOfRange(at.toInt(), at.toInt() + n) }

    private fun check(info: LocalTrailer.Info?) {
        assertArrayEquals(highlight, info!!.highlight)
        assertEquals(293565223L, info.firstFrameUs)
        assertEquals(
            listOf(Highlight(0), Highlight(2520), Highlight(4360), Highlight(5880)),
            Highlights.parse(info.highlight!!, info.firstFrameUs, 9_000),
        )
    }

    @Test
    fun withTable() = check(parse(file(listOf(0x0101 to metadata, 0x0003 to gyro, 0x000a to highlight), table = true)))

    @Test
    fun withoutTable() = check(parse(file(listOf(0x0101 to metadata, 0x000a to highlight, 0x0003 to gyro), table = false)))

    @Test
    fun noMarksRecord() {
        val info = parse(file(listOf(0x0101 to metadata, 0x0003 to gyro), table = true))
        assertNull(info!!.highlight)
        assertEquals(293565223L, info.firstFrameUs)
    }

    @Test
    fun notAnInsta360File() {
        assertNull(parse(ByteArray(5000) { 1 }))
        assertNull(parse(ByteArray(10)))
    }

    @Test
    fun colorProfileAsTheGoUltraWritesIt() {
        // Field 22 as text: «I_Log» / «standard» (two real files, 28.10).
        val ilog = PbWriter().varint(24, 293565223).string(22, "I_Log").toByteArray()
        val standard = PbWriter().varint(24, 293565223).string(22, "standard").toByteArray()
        assertEquals(2, parse(file(listOf(0x0101 to ilog), table = true))!!.colorMode)
        assertEquals(1, parse(file(listOf(0x0101 to standard), table = true))!!.colorMode)
    }

    @Test
    fun colorModeFromTheMetadata() {
        // ExtraMetadata 212 shooting_param_info {8 color_mode: 2 = I-Log}.
        val ilog = PbWriter().varint(24, 293565223).bytes(212, PbWriter().varint(1, 50).varint(8, 2).toByteArray()).toByteArray()
        assertEquals(2, parse(file(listOf(0x0101 to ilog), table = true))!!.colorMode)
        assertNull(parse(file(listOf(0x0101 to metadata), table = true))!!.colorMode)
    }
}
