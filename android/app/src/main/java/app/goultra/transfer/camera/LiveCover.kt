package app.goultra.transfer.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Changing the cover of a live photo already in the gallery (a [LivePhoto] file written by this app): a frame of
 * its clip replaces the photo part; the clip and the trailer stay. The original EXIF (date, camera) is kept.
 */
object LiveCover {
    /** Where the clip is inside a gallery live photo: [start] and [length] in bytes. */
    class Video(val item: Uri, val start: Long, val length: Long, val size: Long)

    /** Null if [name] is not in the gallery or is not a live photo written by this app. */
    fun video(context: Context, name: String): Video? {
        val item = Downloader.galleryUri(context, name) ?: return null
        val resolver = context.contentResolver
        return resolver.openFileDescriptor(item, "r")?.use { pfd ->
            val size = pfd.statSize
            val head = readAt(pfd, 0, minOf(size, 256 * 1024L).toInt())
            // Files of earlier builds end with a 60-byte «…LIVE_<n>» trailer after the video.
            val legacy = size > 20 && String(readAt(pfd, size - 20, 20), Charsets.ISO_8859_1).startsWith("LIVE_")
            val (start, length) = LivePhoto.videoRange(String(head, Charsets.ISO_8859_1), size, legacy) ?: return@use null
            Video(item, start, length, size)
        }
    }

    private fun readAt(pfd: android.os.ParcelFileDescriptor, at: Long, count: Int): ByteArray {
        val buf = ByteArray(count)
        FileInputStream(pfd.fileDescriptor).use { input ->
            input.channel.position(at)
            var n = 0
            while (n < count) {
                val r = input.read(buf, n, count - n)
                if (r < 0) break
                n += r
            }
        }
        return buf
    }

    /** The clip's frame at [us] (exact frame, full resolution), or null. */
    fun frame(context: Context, v: Video, us: Long): Bitmap? =
        context.contentResolver.openFileDescriptor(v.item, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor, v.start, v.length)
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            }
        }

    /** Width of the clip in the gallery file (1280 for the light copy, 3840 for the original). */
    fun width(context: Context, v: Video): Int =
        context.contentResolver.openFileDescriptor(v.item, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor, v.start, v.length)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        } ?: 0

    /** JPEG of [bitmap] carrying [exifFrom]'s EXIF segment (the original shot's date and camera). */
    fun jpeg(bitmap: Bitmap, exifFrom: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        val plain = out.toByteArray()
        val exif = exifFrom?.let(::exifSegment) ?: return plain
        return plain.copyOfRange(0, 2) + exif + plain.copyOfRange(2, plain.size)
    }

    /** The photo part of a gallery live photo (its JPEG with the XMP), for the EXIF. */
    fun photoPart(context: Context, v: Video): ByteArray? =
        context.contentResolver.openInputStream(v.item)?.use { input ->
            val buf = ByteArray(v.start.toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }

    /**
     * Writes [photo] (a plain JPEG) as the new photo part: [photo] + XMP, then the same clip (a file of an earlier
     * build loses its old trailer: the result is a plain Motion Photo). The clip goes through a temp file in
     * [cacheDir] (the photo part changes size, everything after it moves).
     */
    fun replacePhoto(context: Context, v: Video, photo: ByteArray, cacheDir: File) {
        val resolver = context.contentResolver
        val tmp = File(cacheDir, "live-clip.tmp")
        try {
            resolver.openFileDescriptor(v.item, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    input.channel.position(v.start)
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var left = v.length
                        while (left > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r < 0) break
                            out.write(buf, 0, r)
                            left -= r
                        }
                    }
                }
            }
            val head = LivePhoto.head(photo, v.length)
            // Pending while rewritten (no half-written file in the gallery); clearing it makes the system rescan it.
            resolver.update(v.item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null)
            resolver.openFileDescriptor(v.item, "rw")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.channel.truncate(0)
                    out.channel.position(0)
                    out.write(head)
                    FileInputStream(tmp).use { it.copyTo(out, 256 * 1024) }
                }
            }
            resolver.update(v.item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } finally {
            tmp.delete()
        }
    }

    /** The APP1 «Exif» segment of a JPEG (marker included), or null. */
    private fun exifSegment(jpeg: ByteArray): ByteArray? {
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val len = (jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 <= jpeg.size && String(jpeg, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                return jpeg.copyOfRange(i, i + 2 + len)
            }
            if (marker == 0xDA || marker == 0xD9) break // image data: no more headers
            i += 2 + len
        }
        return null
    }
}
