package app.goultra.transfer.camera

import android.content.Context
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Changing the cover of a live photo already in the gallery (a [LivePhoto] file written by this app): a frame of
 * its clip replaces the photo part; the clip and the trailer stay. The original EXIF (date, camera) is kept.
 */
object LiveCover {
    /**
     * Finds the video of a live photo at [uri] whatever it is called: our Motion Photo XMP, or a native live photo
     * (e.g. Huawei's own, added from the phone) whose file ends with the 80-byte gallery trailer. Returns the same
     * [video] result so a photo added by hand can play and get a new cover; such files carry no saved original photo.
     */
    fun video(context: Context, item: Uri, name: String): Video? {
        val resolver = context.contentResolver
        return resolver.openFileDescriptor(item, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size < 0) throw IOException("неизвестен размер живого фото $name")
            FileInputStream(pfd.fileDescriptor).use { input ->
                val head = readFullyAt(input, 0, minOf(size, 256 * 1024L).toInt())
                val text = String(head, Charsets.ISO_8859_1)
                val last20 = if (size >= 20) String(readFullyAt(input, size - 20, 20), Charsets.ISO_8859_1) else ""
                val hasV3Marker = size >= 60 && readFullyAt(input, size - 60, 4).contentEquals("v3_f".toByteArray())
                val range = LivePhoto.videoRangeWithTrailer(text, size, last20, hasV3Marker) ?: return@use null
                val hasTrailer = hasV3Marker && LivePhoto.huaweiTrailerVideoLength(last20) != null &&
                    !text.contains("MicroVideoOffset")
                val originalSize = LivePhoto.originalPhotoLength(text)
                val originalVideoSize = LivePhoto.originalVideoLength(text)
                if (originalVideoSize > range.first || originalSize > range.first - originalVideoSize)
                    throw IOException("неверный размер исходного фото или видео")
                Video(item, range.first, range.second, size, originalSize, originalVideoSize,
                    LivePhoto.hasOriginalPhotoLength(text), hasHuaweiTrailer = hasTrailer,
                    ownXmp = text.contains("x:xmptk=\"GO Ultra Transfer\""))
            }
        }
    }

    /** The Motion Photo MP4 and, when available, the unmodified original MP4 stored immediately before it. */
    class Video(
        val item: Uri, val start: Long, val length: Long, val size: Long, val originalSize: Long,
        val originalVideoSize: Long = 0,
        val originalPhotoTracked: Boolean = false,
        val hasHuaweiTrailer: Boolean = false,
        /** Written by this app (its XMP): the cover can be replaced in place. Native/added live photos can't. */
        val ownXmp: Boolean = true,
    ) {
        val fullVideoStart: Long get() = if (originalVideoSize > 0) start - originalVideoSize else start
        val fullVideoLength: Long get() = if (originalVideoSize > 0) originalVideoSize else length
    }

    /** Null if [name] is not in the gallery or is not a live photo written by this app. */
    fun video(context: Context, name: String): Video? {
        val item = Downloader.galleryUri(context, name) ?: return null
        return video(context, item, name)
    }

    /** The clip's frame at [us] (exact frame, full resolution), or null. */
    fun frame(context: Context, v: Video, us: Long): Bitmap? =
        context.contentResolver.openFileDescriptor(v.item, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor, v.fullVideoStart, v.fullVideoLength)
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            }
        }

    /** Width of the original clip in the gallery file. */
    fun width(context: Context, v: Video): Int =
        context.contentResolver.openFileDescriptor(v.item, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor, v.fullVideoStart, v.fullVideoLength)
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
            val length = v.fullVideoStart - v.originalSize
            if (length > Int.MAX_VALUE) throw IOException("слишком большое фото")
            val buf = ByteArray(length.toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n != buf.size) throw IOException("неполное исходное фото: $n из ${buf.size}")
            buf
        }

    /** The clip's cover frame, decoded small (any codec, Media3-less): for thumbnails. */
    fun coverFrame(context: Context, v: Video, us: Long = LivePhoto.DEFAULT_COVER_US): Bitmap? =
        context.contentResolver.openFileDescriptor(v.item, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor, v.start, v.length)
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }

    fun originalPhoto(context: Context, v: Video): ByteArray? {
        if (v.originalSize == 0L) return null
        if (v.originalSize > Int.MAX_VALUE) throw IOException("слишком большое исходное фото")
        val pfd = context.contentResolver.openFileDescriptor(v.item, "r") ?: throw IOException("не открыть живое фото")
        return pfd.use { FileInputStream(it.fileDescriptor).use { input ->
            readFullyAt(input, v.fullVideoStart - v.originalSize, v.originalSize.toInt())
        } }
    }

    /**
     * Writes [photo] and the same clip to a new gallery item before replacing the old one.
     * A file of an earlier build loses its old trailer: the result is a plain Motion Photo.
     */
    fun replacePhoto(
        context: Context,
        v: Video,
        photo: ByteArray,
        original: ByteArray? = null,
        restore: Boolean = false,
        coverUs: Long = LivePhoto.DEFAULT_COVER_US,
    ) {
        val resolver = context.contentResolver
        val details = resolver.query(
            v.item,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("живое фото не найдено: ${v.item}")
            (cursor.getString(0) ?: throw IOException("нет имени живого фото")) to
                if (cursor.isNull(1)) null else cursor.getLong(1)
        } ?: throw IOException("не прочитать сведения о живом фото: ${v.item}")
        if (v.start < 0 || v.length < 0 || v.start > v.size || v.length > v.size - v.start)
            throw IOException("неверные границы видео в живом фото")
        val originalToCheck = if (restore) photo else original
        if (originalToCheck != null && !LivePhoto.canRestoreOriginal(originalToCheck, v.length, v.originalVideoSize)) {
            throw IOException("исходное живое фото не удастся восстановить; оригинал не изменён")
        }
        val head = LivePhoto.head(photo, v.length, presentationUs = if (restore) LivePhoto.DEFAULT_COVER_US else coverUs,
            originalSize = if (restore) 0 else original?.size?.toLong() ?: 0,
            originalVideoSize = v.originalVideoSize)
        Downloader.saveReplacement(
            resolver,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            LivePhoto.galleryName(details.first),
            "image/jpeg",
            details.second,
            v.item,
            oldName = details.first,
        ) { fd ->
            resolver.openFileDescriptor(v.item, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    input.channel.position(v.fullVideoStart)
                    FileOutputStream(fd).use { out ->
                        out.write(head)
                        if (!restore && original != null) out.write(original)
                        copyExactly(input, out, v.originalVideoSize + v.length)
                        if (v.hasHuaweiTrailer) {
                            out.write(LivePhoto.huaweiTrailer(v.length, if (restore) LivePhoto.DEFAULT_COVER_US / 1000 else coverUs / 1000))
                        }
                    }
                }
            }
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
