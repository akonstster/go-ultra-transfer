package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
        assertFalse(xmp.contains("Item:Padding"))
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

    @Test
    fun editedMotionPhotoKeepsOriginalBeforeVideo() {
        val original = LivePhoto.head(jpeg, 1000)
        val editedHead = LivePhoto.head(jpeg, 1000, presentationUs = 2_000_000, originalSize = original.size.toLong())
        val combined = editedHead + original + ByteArray(1000)
        val xmp = String(editedHead, Charsets.ISO_8859_1)
        assertTrue(xmp.contains("Item:Padding=\"${original.size}\""))
        assertTrue(xmp.contains("Camera:MotionPhotoPresentationTimestampUs=\"2000000\""))
        assertEquals(original.size.toLong(), LivePhoto.originalPhotoLength(xmp))
        val (start, length) = LivePhoto.videoRange(xmp, combined.size.toLong(), false)!!
        assertEquals((editedHead.size + original.size).toLong(), start)
        assertEquals(1000L, length)
        assertTrue(combined.copyOfRange((start - original.size).toInt(), start.toInt()).contentEquals(original))
        val (restoredStart, restoredLength) = LivePhoto.videoRange(String(original, Charsets.ISO_8859_1), original.size.toLong() + length, false)!!
        assertEquals(original.size.toLong(), restoredStart)
        assertEquals(length, restoredLength)
        assertTrue(LivePhoto.canRestoreOriginal(original, length))
        assertFalse(LivePhoto.canRestoreOriginal("…GCamera:MicroVideoOffset=\"1060\"…".toByteArray(), 1000))
    }

    @Test
    fun originalLengthDefaultsToZeroForOlderFiles() {
        assertEquals(0L, LivePhoto.originalPhotoLength("old XMP"))
        assertFalse(LivePhoto.hasOriginalPhotoLength("old XMP"))
        assertTrue(LivePhoto.hasOriginalPhotoLength(String(LivePhoto.head(jpeg, 1000), Charsets.ISO_8859_1)))
    }

    @Test
    fun cameraTrailerIsCountedAsPaddingAndPreserved() {
        val trailer = byteArrayOf(0x10, 0x20, 0xFF.toByte(), 0xD9.toByte(), 0x30, 0x40)
        val photo = jpeg + trailer
        val head = LivePhoto.head(photo, 1000)
        val text = String(head, Charsets.ISO_8859_1)
        val primaryEnd = LivePhoto.jpegEnd(head)
        assertTrue(text.contains("Item:Padding=\"${trailer.size}\""))
        assertEquals(head.size, primaryEnd + trailer.size)
        assertTrue(head.copyOfRange(primaryEnd, head.size).contentEquals(trailer))
    }

    @Test
    fun cameraTrailerAndPreservedOriginalAreBothCountedAsPadding() {
        val trailer = byteArrayOf(1, 2, 3)
        val original = ByteArray(9)
        val head = LivePhoto.head(jpeg + trailer, 1000, originalSize = original.size.toLong())
        val text = String(head, Charsets.ISO_8859_1)
        assertTrue(text.contains("Item:Padding=\"${trailer.size + original.size}\""))
        assertEquals(head.size + original.size, LivePhoto.jpegEnd(head) + trailer.size + original.size)
    }

    @Test
    fun keepsOriginalVideoInPaddingBeforeTheTwoTrackMotionVideo() {
        val cameraTrailer = byteArrayOf(1, 2, 3)
        val originalVideo = ByteArray(1500) { it.toByte() }
        val motionVideo = ByteArray(900) { (it * 3).toByte() }
        val head = LivePhoto.head(jpeg + cameraTrailer, motionVideo.size.toLong(),
            originalVideoSize = originalVideo.size.toLong())
        val text = String(head, Charsets.ISO_8859_1)
        val file = head + originalVideo + motionVideo
        val (start, length) = LivePhoto.videoRange(text, file.size.toLong(), false)!!
        assertEquals((head.size + originalVideo.size).toLong(), start)
        assertEquals(motionVideo.size.toLong(), length)
        assertEquals(originalVideo.size.toLong(), LivePhoto.originalVideoLength(text))
        assertTrue(text.contains("Item:Padding=\"${cameraTrailer.size + originalVideo.size}\""))
        assertTrue(file.copyOfRange((start - originalVideo.size).toInt(), start.toInt()).contentEquals(originalVideo))
        assertTrue(LivePhoto.canRestoreOriginal(head, length, originalVideo.size.toLong()))
        assertEquals(0L, LivePhoto.originalVideoLength("old XMP"))
    }

    @Test
    fun originalPhotoAndOriginalVideoStayDistinctAfterCoverChange() {
        val originalVideo = ByteArray(1500) { it.toByte() }
        val motionVideo = ByteArray(900)
        val original = LivePhoto.head(jpeg, motionVideo.size.toLong(), originalVideoSize = originalVideo.size.toLong())
        val edited = LivePhoto.head(jpeg, motionVideo.size.toLong(), originalSize = original.size.toLong(),
            originalVideoSize = originalVideo.size.toLong())
        val combined = edited + original + originalVideo + motionVideo
        val (start, length) = LivePhoto.videoRange(String(edited, Charsets.ISO_8859_1), combined.size.toLong(), false)!!
        val videoStart = start - originalVideo.size
        assertTrue(combined.copyOfRange((videoStart - original.size).toInt(), videoStart.toInt()).contentEquals(original))
        assertTrue(combined.copyOfRange(videoStart.toInt(), start.toInt()).contentEquals(originalVideo))
        assertTrue(LivePhoto.canRestoreOriginal(original, length, originalVideo.size.toLong()))
    }

    @Test
    fun replacesOwnMotionXmpWhenRestoringOriginal() {
        val trailer = byteArrayOf(1, 2, 3)
        val old = LivePhoto.head(jpeg + trailer, 1000)
        val restored = LivePhoto.head(old, 1000)
        val text = String(restored, Charsets.ISO_8859_1)
        assertEquals(1, Regex("Camera:MotionPhoto=\"1\"").findAll(text).count())
        assertEquals(restored.size, LivePhoto.jpegEnd(restored) + trailer.size)
    }

    @Test
    fun scanParsingSkipsStuffingAndRestartMarkers() {
        val stream = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0, 2,
            3, 0xff.toByte(), 0, 0xd9.toByte(), 0xff.toByte(), 0xd0.toByte(),
            4, 0xff.toByte(), 0xd9.toByte(), 8, 9,
        )
        assertEquals(15, LivePhoto.jpegEnd(stream))
    }

    @Test
    fun endMarkerInsideExifAndProgressiveScanIsNotTheImageEnd() {
        val stream = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe1.toByte(), 0, 6, 0xff.toByte(), 0xd9.toByte(), 1, 2,
            0xff.toByte(), 0xda.toByte(), 0, 2, 1, 2,
            0xff.toByte(), 0xc4.toByte(), 0, 2,
            0xff.toByte(), 0xda.toByte(), 0, 2, 3,
            0xff.toByte(), 0xd9.toByte(), 9,
        )
        assertEquals(stream.size - 1, LivePhoto.jpegEnd(stream))
    }

    @Test
    fun rejectsTruncatedAndInvalidJpeg() {
        assertThrows(IllegalArgumentException::class.java) { LivePhoto.jpegEnd(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) }
        assertThrows(IllegalArgumentException::class.java) {
            LivePhoto.jpegEnd(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 20))
        }
    }

    @Test
    fun huaweiTrailerMatchesTheGalleryFormat() {
        val trailer = LivePhoto.huaweiTrailer(19840563, coverMs = 1500)
        assertEquals(80, trailer.size)
        assertTrue(trailer.copyOf(20).all { it == 0.toByte() })
        assertEquals("v3_f44_c            ", String(trailer, 20, 20, Charsets.US_ASCII))
        assertEquals("1500:600            ", String(trailer, 40, 20, Charsets.US_ASCII))
        val last = String(trailer, 60, 20, Charsets.US_ASCII)
        assertEquals("LIVE_19840603       ", last)
        assertEquals(19840603L, LivePhoto.huaweiTrailerVideoLength(last))
    }

    @Test
    fun huaweiTrailerDefaultCoverIsTheShotMoment() {
        val trailer = LivePhoto.huaweiTrailer(1000)
        assertEquals("1500:600            ", String(trailer, 40, 20, Charsets.US_ASCII))
    }

    @Test
    fun huaweiTrailerLengthRejectsOtherTrailers() {
        assertEquals(null, LivePhoto.huaweiTrailerVideoLength("LIVE_abc"))
        assertEquals(null, LivePhoto.huaweiTrailerVideoLength(""))
        assertEquals(5L, LivePhoto.huaweiTrailerVideoLength("LIVE_5"))
    }

    @Test
    fun videoRangeFindsTheVideoBeforeTheHuaweiTrailer() {
        val head = LivePhoto.head(jpeg, 1000)
        val trailer = LivePhoto.huaweiTrailer(1000)
        val size = (head.size + 1000 + trailer.size).toLong()
        val last20 = String(trailer, 60, 20, Charsets.US_ASCII)
        val (start, length) = LivePhoto.videoRangeWithTrailer(String(head, Charsets.ISO_8859_1), size, last20, true)!!
        assertEquals(head.size.toLong(), start)
        assertEquals(1000L, length)
        // Without the v3_f marker the same tail is not trusted (earlier builds end with "LIVE_" too).
        val plain = LivePhoto.videoRangeWithTrailer(String(head, Charsets.ISO_8859_1), size, last20, false)!!
        assertEquals(size - 1000, plain.first)
    }

    @Test
    fun videoRangeKeepsEarlierBuildsWithMicroVideoOffset() {
        val headText = "…GCamera:MicroVideoOffset=\"1060\"…"
        val (start, length) = LivePhoto.videoRangeWithTrailer(headText, 5000, "LIVE_1000            ", true)!!
        assertEquals(3940L, start)
        assertEquals(1000L, length) // MicroVideoOffset covers the 60-byte trailer of the old format, not the Huawei one
    }

    @Test
    fun motionPhotoNamesFollowAndroidRecommendationWithoutChangingLogicalCameraName() {
        val camera = "LIV_20261030_123456_001.jpg"
        val gallery = "LIV_20261030_123456_001_MP.jpg"
        assertEquals(gallery, LivePhoto.galleryName(camera))
        assertEquals(gallery, LivePhoto.galleryName(gallery))
        assertEquals(camera, LivePhoto.cameraName(gallery))
        assertEquals(camera, LivePhoto.cameraName(camera))
        assertEquals("holiday.jpg", LivePhoto.galleryName("holiday.jpg"))
    }
}
