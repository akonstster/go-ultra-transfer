package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** mp4 files built box by box: an audio track first, then 29.97 fps video with a key frame every 30 frames. */
class Mp4IndexTest {
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return be32(8L + body.size) + type.toByteArray() + body
    }

    /** A full box: version and flags, then the fields. */
    private fun full(type: String, vararg fields: Long, version: Int = 0) =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0) + fields.fold(ByteArray(0)) { a, f -> a + be32(f) })

    private fun hdlr(kind: String) = box("hdlr", ByteArray(8) + kind.toByteArray() + ByteArray(12))

    private fun mdhd(timescale: Long) = full("mdhd", 0, 0, timescale, 0, 0)

    private fun trak(kind: String, timescale: Long, stbl: ByteArray, edts: ByteArray? = null) = box(
        "trak",
        *listOfNotNull(edts).toTypedArray(),
        box("mdia", mdhd(timescale), hdlr(kind), box("minf", box("stbl", stbl))),
    )

    /** 300 frames of 1001 ticks at 30000/s; key frames 1, 31, 61 …; [ctts]: every frame shown 2002 ticks later. */
    private fun file(ctts: Boolean = false, elst: Long? = null): ByteArray {
        val keys = (0 until 10).map { 1L + it * 30 }
        val stbl = full("stts", 1, 300, 1001) + full("stss", keys.size.toLong(), *keys.toLongArray()) +
            (if (ctts) full("ctts", 1, 300, 2002) else ByteArray(0))
        val audio = trak("soun", 48000, full("stts", 1, 100, 1024) + full("stss", 1, 1))
        val video = trak("vide", 30000, stbl, elst?.let { box("edts", full("elst", 1, 300_000, it, 0x10000)) })
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(), be32(0)))
        out.write(box("mdat", ByteArray(5000)))
        out.write(box("moov", full("mvhd", 0, 0, 1000, 10_000), audio, video))
        return out.toByteArray()
    }

    private fun read(f: ByteArray) = Mp4Index.keyFrames(f.size.toLong()) { at, n -> f.copyOfRange(at.toInt(), at.toInt() + n) }

    @Test
    fun keyFramesRoundedUp() {
        val keys = read(file())!!
        // Frame 31 is at 30 × 1001 / 30000 = 1.001 s; frame 61 at 2.002 s; 91 at 3.003 s.
        assertEquals(listOf(0L, 1001, 2002, 3003), keys.ms.take(4))
        assertEquals(10, keys.ms.size)
        assertFalse(keys.bFrames)
    }

    @Test
    fun displayOffsetsMeanBFramesAndTheEditListShiftsBack() {
        // ctts moves every frame 2002 ticks later; elst starts the presentation at that same 2002: times as before.
        val keys = read(file(ctts = true, elst = 2002))!!
        assertTrue(keys.bFrames)
        assertEquals(listOf(0L, 1001, 2002), keys.ms.take(3))
    }

    @Test
    fun notAnMp4() {
        assertNull(Mp4Index.keyFrames(100) { _, n -> ByteArray(n) })
    }

    @Test
    fun snapToTheNearestKeyFrame() {
        val keys = KeyFrames(listOf(0, 2000, 4000, 6000), false)
        assertEquals(4000, keys.nearest(4900))
        assertEquals(6000, keys.nearest(5100))
        // Not into the piece before, not past its own end.
        assertEquals(Highlight(4000, 6500), keys.snapStart(Highlight(4900, 6500), listOf(Highlight(1000, 3500))))
        assertEquals(Highlight(4000, 5800), keys.snapStart(Highlight(5100, 5800), emptyList()))
        assertEquals(Highlight(4500, 5800), keys.snapStart(Highlight(4500, 5800), listOf(Highlight(4200, 4500))))
    }
}
