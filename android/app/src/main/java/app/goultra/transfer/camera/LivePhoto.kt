package app.goultra.transfer.camera

/**
 * One gallery file for a camera live photo (LIV_….jpg + LIV_….mp4), strictly in the Android Motion Photo format 1.0
 * (developer.android.com/media/platform/motion-photo-format): the full-size JPEG with Camera + Container XMP, the
 * MP4 appended right after it. Read by Google Photos and other galleries that follow the spec; galleries with
 * their own undocumented formats (Huawei) show it as a plain photo.
 */
object LivePhoto {
    /** The camera keeps 1.5 s before the shot: the photo is at 1.5 s of the clip. */
    const val DEFAULT_COVER_US = 1_500_000L

    /** [jpeg] with the Motion Photo XMP segment inserted after its leading APP0/APP1 (Exif) segments. */
    fun head(jpeg: ByteArray, videoSize: Long, presentationUs: Long = DEFAULT_COVER_US): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="GO Ultra Transfer">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    Camera:MotionPhoto="1"
    Camera:MotionPhotoVersion="1"
    Camera:MotionPhotoPresentationTimestampUs="$presentationUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize" Item:Padding="0"/>
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
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte() && (jpeg[i + 1] == 0xE0.toByte() || jpeg[i + 1] == 0xE1.toByte())) {
            i += 2 + ((jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF))
        }
        return jpeg.copyOfRange(0, i) + segment + jpeg.copyOfRange(i, jpeg.size)
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
