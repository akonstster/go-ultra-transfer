package app.goultra.transfer.camera

import java.io.ByteArrayOutputStream

/**
 * One gallery file in the Android Motion Photo format 1.0: the JPEG with XMP and an appended MP4. Camera trailers,
 * the original MP4 and an optional original photo are kept in the JPEG padding.
 */
object LivePhoto {
    /** The camera keeps 1.5 s before the shot: the photo is at 1.5 s of the clip. */
    const val DEFAULT_COVER_US = 1_500_000L

    private val cameraName = Regex("""^(LIV_\d{8}_\d{6}_\d+)(?:_MP)?\.jpg$""", RegexOption.IGNORE_CASE)

    fun cameraName(name: String): String = cameraName.matchEntire(name)?.let { "${it.groupValues[1]}.jpg" } ?: name

    fun galleryName(name: String): String = cameraName.matchEntire(name)?.let { "${it.groupValues[1]}_MP.jpg" } ?: name

    /** End of the primary JPEG, skipping markers and stuffed bytes inside scan data. */
    fun jpegEnd(jpeg: ByteArray): Int {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        var i = 2
        var inScan = false
        while (i < jpeg.size) {
            if (inScan) {
                while (i < jpeg.size && jpeg[i] != 0xFF.toByte()) i++
                require(i < jpeg.size) { "JPEG scan has no end marker" }
            } else {
                require(jpeg[i] == 0xFF.toByte()) { "invalid JPEG marker" }
            }
            while (i < jpeg.size && jpeg[i] == 0xFF.toByte()) i++
            require(i < jpeg.size) { "truncated JPEG marker" }
            val marker = jpeg[i++].toInt() and 0xFF
            if (inScan && (marker == 0 || marker in 0xD0..0xD7)) continue
            require(marker != 0) { "invalid JPEG marker" }
            if (marker == 0xD9) return i
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            require(i + 2 <= jpeg.size) { "truncated JPEG segment" }
            val size = (jpeg[i].toInt() and 0xFF) shl 8 or (jpeg[i + 1].toInt() and 0xFF)
            require(size >= 2 && size <= jpeg.size - i) { "invalid JPEG segment length" }
            val wasInScan = inScan
            i += size
            inScan = marker == 0xDA || (wasInScan && marker == 0xDC)
        }
        throw IllegalArgumentException("JPEG has no end marker")
    }

    private fun withoutOwnXmp(jpeg: ByteArray): ByteArray {
        var i = 2
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte() &&
            (jpeg[i + 1] == 0xE0.toByte() || jpeg[i + 1] == 0xE1.toByte())) {
            val size = (jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF)
            require(size >= 2 && size + 2 <= jpeg.size - i) { "invalid JPEG APP segment" }
            val end = i + size + 2
            val isOwnXmp = jpeg[i + 1] == 0xE1.toByte() &&
                String(jpeg, i + 4, size - 2, Charsets.ISO_8859_1).let {
                    it.startsWith("http://ns.adobe.com/xap/1.0/\u0000") && it.contains("x:xmptk=\"GO Ultra Transfer\"")
                }
            if (!isOwnXmp) out.write(jpeg, i, end - i)
            i = end
        }
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }

    /** Preserves camera trailers as JPEG padding and replaces our previous Motion Photo XMP, if present. */
    fun head(
        jpeg: ByteArray,
        videoSize: Long,
        presentationUs: Long = DEFAULT_COVER_US,
        originalSize: Long = 0,
        originalVideoSize: Long = 0,
    ): ByteArray {
        require(videoSize > 0) { "invalid video size" }
        require(originalSize >= 0 && originalVideoSize >= 0 && presentationUs >= -1)
        val imageEnd = jpegEnd(jpeg)
        val trailing = jpeg.size.toLong() - imageEnd
        require(originalSize <= Long.MAX_VALUE - trailing) { "padding is too large" }
        require(originalVideoSize <= Long.MAX_VALUE - trailing - originalSize) { "padding is too large" }
        val padding = trailing + originalSize + originalVideoSize
        val image = withoutOwnXmp(jpeg)
        val paddingAttribute = if (padding > 0) """ Item:Padding="$padding"""" else ""
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="GO Ultra Transfer">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    xmlns:GUT="https://github.com/akonstster/go-ultra-transfer/ns/motion-photo/1.0/"
    GUT:OriginalPhotoLength="$originalSize"
    GUT:OriginalVideoLength="$originalVideoSize"
    Camera:MotionPhoto="1"
    Camera:MotionPhotoVersion="1"
    Camera:MotionPhotoPresentationTimestampUs="$presentationUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"$paddingAttribute/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>""".toByteArray()
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp
        val segLen = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (segLen shr 8).toByte(), segLen.toByte()) + payload
        var i = 2
        while (i + 4 <= image.size && image[i] == 0xFF.toByte() && (image[i + 1] == 0xE0.toByte() || image[i + 1] == 0xE1.toByte())) {
            i += 2 + ((image[i + 2].toInt() and 0xFF) shl 8 or (image[i + 3].toInt() and 0xFF))
        }

        return image.copyOfRange(0, i) + segment + image.copyOfRange(i, image.size)
    }

    fun originalPhotoLength(head: String): Long =
        Regex("""GUT:OriginalPhotoLength="(\d+)"""").find(head)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    fun hasOriginalPhotoLength(head: String): Boolean =
        Regex("""GUT:OriginalPhotoLength="\d+"""").containsMatchIn(head)

    fun originalVideoLength(head: String): Long =
        Regex("""GUT:OriginalVideoLength="(\d+)"""").find(head)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Huawei Gallery's live photo trailer: four 20-byte fields after the video — 20 zero bytes, "v3_f44_c",
     * "<cover ms>:600" and "LIVE_<n>" where n covers the video plus the first two fields. MediaStore then sets
     * special_file_type/offset so the gallery treats the file as a live photo (verified on Mate 80 Pro).
     */
    fun huaweiTrailer(videoSize: Long, coverMs: Long = DEFAULT_COVER_US / 1000): ByteArray {
        require(videoSize > 0 && coverMs >= 0)
        fun field(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= 20)
            return bytes + ByteArray(20 - bytes.size) { 0x20 }
        }
        return ByteArray(20) + field("v3_f44_c") + field("$coverMs:600") + field("LIVE_${videoSize + 40}")
    }

    /** n from a trailing "LIVE_<n>" 20-byte field, or null. */
    fun huaweiTrailerVideoLength(last20: String): Long? =
        Regex("""^LIVE_(\d+) *$""").find(last20)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Video range when the file may carry the Huawei trailer: the trailer's n covers the video and the first two
     * trailer fields, so the video is the [n]-40 bytes starting 40 bytes after them. [hasV3Marker]: the field 40
     * bytes before the end starts with "v3_f". Files of earlier builds (MicroVideoOffset) keep the old logic.
     */
    fun videoRangeWithTrailer(head: String, size: Long, last20: String, hasV3Marker: Boolean): Pair<Long, Long>? {
        val n = if (!head.contains("MicroVideoOffset") && hasV3Marker) huaweiTrailerVideoLength(last20) else null
        if (n != null && n > 40 && size >= n + 40) return (size - n - 40) to (n - 40)
        return videoRange(head, size, last20.startsWith("LIVE_"))
    }

    fun canRestoreOriginal(photo: ByteArray, videoSize: Long, originalVideoSize: Long = 0): Boolean {
        val text = String(photo, 0, minOf(photo.size, 256 * 1024), Charsets.ISO_8859_1)
        return videoRange(text, photo.size.toLong() + originalVideoSize + videoSize, endsWithLegacyTrailer = false) ==
            ((photo.size.toLong() + originalVideoSize) to videoSize)
    }

    /**
     * Where the video is in a live photo of [size] bytes whose first bytes are [head] (as ISO-8859-1 text):
     * (start, length), or null. Also reads the files of earlier versions of this app (MicroVideoOffset + a 60-byte
     * Huawei-style trailer after the video).
     */
    fun videoRange(head: String, size: Long, endsWithLegacyTrailer: Boolean): Pair<Long, Long>? {
        Regex("""MicroVideoOffset="(\d+)"""").find(head)?.groupValues?.get(1)?.toLongOrNull()?.let { offset ->
            val length = offset - if (endsWithLegacyTrailer) 60 else 0
            return if (offset in 1 until size && length > 0) (size - offset) to length else null
        }
        val length = Regex("""Item:Semantic="MotionPhoto"[^>]*?Item:Length="(\d+)"""").find(head)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (length in 1 until size) (size - length) to length else null
    }
}
