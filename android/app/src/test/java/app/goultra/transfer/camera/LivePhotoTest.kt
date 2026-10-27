package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePhotoTest {
    /** SOI, APP0 (JFIF, 16 B), APP1 (Exif, 10 B), then the image data (DQT…EOI stand-in). */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14) +
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 10) + "Exif\u0000\u0000ab".toByteArray() +
        byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun motionPhotoXmpAfterExifAndTheVideoAtTheEnd() {
        val video = ByteArray(1000) { it.toByte() }
        val head = LivePhoto.head(jpeg, video.size.toLong())
        val file = head + video

        // The original segments stay in front, the XMP APP1 right after them, the image data after that.
        val exifEnd = 2 + 18 + 12
        assertTrue(head.copyOfRange(0, exifEnd).contentEquals(jpeg.copyOfRange(0, exifEnd)))
        assertEquals(0xE1, head[exifEnd + 1].toInt() and 0xFF)
        assertTrue(String(head, exifEnd + 4, 28, Charsets.ISO_8859_1).startsWith("http://ns.adobe.com/xap/1.0/"))
        assertTrue(head.copyOfRange(head.size - 6, head.size).contentEquals(jpeg.copyOfRange(jpeg.size - 6, jpeg.size)))

        // Motion Photo 1.0 only: no deprecated MicroVideo fields; the video item is the last bytes of the file.
        val xmp = String(head, Charsets.ISO_8859_1)
        assertTrue(xmp.contains("Camera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("Camera:MotionPhotoPresentationTimestampUs=\"1500000\""))
        assertFalse(xmp.contains("MicroVideo"))
        val (start, length) = LivePhoto.videoRange(xmp, file.size.toLong(), endsWithLegacyTrailer = false)!!
        assertEquals(head.size.toLong(), start)
        assertEquals(video.size.toLong(), length)
    }

    @Test
    fun readsFilesOfEarlierBuilds() {
        // Earlier builds: MicroVideoOffset counted the video plus a 60-byte trailer after it.
        val headText = "…GCamera:MicroVideoOffset=\"1060\"…"
        val (start, length) = LivePhoto.videoRange(headText, 5000, endsWithLegacyTrailer = true)!!
        assertEquals(3940L, start)
        assertEquals(1000L, length)
    }
}
