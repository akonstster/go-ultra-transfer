package app.goultra.transfer.camera

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads http://192.168.42.1/<uri> straight into the shared gallery: DCIM/Camera, next to the phone's own shots,
 * with DATE_TAKEN set from the camera so files land at the right place in the timeline.
 * An unfinished file stays a pending MediaStore item and is resumed with an HTTP Range request
 * (after a network hiccup, a cancel or on the next connection). An existing file is overwritten in place, never duplicated.
 */
object Downloader {
    private val TARGET = Environment.DIRECTORY_DCIM + "/Camera"
    private const val MAX_RETRIES = 5

    /** Where this app saves (and saved in earlier versions) — MediaStore only shows us our own items there. */
    private const val OUR_FOLDERS = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Camera%' OR " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%GO Ultra%'"

    /** Names of files this app already saved to the gallery (pending items are excluded by MediaStore). */
    fun downloadedNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        for (collection in listOf(collection(video = false), collection(video = true))) {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                OUR_FOLDERS,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
        }
        return names
    }

    /**
     * Files this app saved to the gallery, as list items for the Files tab without the camera (newest first).
     * Date from the name like on the camera, else DATE_TAKEN; duration from MediaStore (videos only).
     */
    fun phoneFiles(context: Context): List<CameraFile> {
        val out = mutableListOf<CameraFile>()
        for (video in listOf(false, true)) {
            val collection = collection(video)
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.DURATION,
                ),
                OUR_FOLDERS,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val taken = if (c.isNull(3)) null else c.getLong(3)
                    out += CameraFile(
                        uri = "/phone/$name",
                        size = if (c.isNull(2)) null else c.getLong(2),
                        durationSec = if (c.isNull(6)) null else ((c.getLong(6) + 500) / 1000).toInt(),
                        width = if (c.isNull(4)) null else c.getInt(4),
                        height = if (c.isNull(5)) null else c.getInt(5),
                        fps = null,
                        created = CameraFile.createdFromName(name) ?: taken?.let {
                            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
                        },
                        localUri = ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    )
                }
            }
        }
        return out.sortedWith(compareByDescending<CameraFile> { it.created }.thenByDescending { it.name })
    }

    /**
     * One-time cleanup after versions that saved to Pictures|Movies/GO Ultra and created "name (1).jpg" duplicates:
     * duplicates are deleted, originals move to DCIM/Camera. Only our own items are visible here.
     */
    fun migrateLegacy(context: Context): Int {
        val resolver = context.contentResolver
        val duplicate = Regex(""" \(\d+\)\.\w+$""")
        var changed = 0
        for (video in listOf(false, true)) {
            val collection = collection(video)
            val legacy = mutableListOf<Pair<Long, String>>()
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%GO Ultra%'",
                null,
                null,
            )?.use { c -> while (c.moveToNext()) legacy += c.getLong(0) to c.getString(1) }
            for ((id, name) in legacy) {
                val uri = ContentUris.withAppendedId(collection, id)
                val inTarget = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Camera%'",
                    arrayOf(name),
                    null,
                )?.use { it.count > 0 } ?: false
                changed += if (duplicate.containsMatchIn(name) || inTarget) {
                    resolver.delete(uri, null, null)
                } else {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, TARGET) },
                        null,
                        null,
                    )
                }
            }
        }
        return changed
    }

    /** HTTP Range request: bytes [from, to] of a camera file. */
    suspend fun fetchRange(link: CameraLink, uri: String, from: Long, to: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val conn = open(link, uri)
            try {
                conn.setRequestProperty("Range", "bytes=$from-$to")
                if (conn.responseCode !in 200..206) null else conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

    /** Whole small file (e.g. /CONF) without Range: a range past the end may be rejected by the camera. */
    suspend fun fetch(link: CameraLink, uri: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(link, uri)
        try {
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code for $uri")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * [overwrite]: re-download a file that is already complete in the gallery (same item, no duplicate).
     * Otherwise a complete file is left as is and a partial one is resumed. [takenAtMillis] goes to DATE_TAKEN.
     * [liveVideo]: the photo [uri] is a live photo and this is its clip — one gallery file is written
     * ([LivePhoto]: the photo with the Motion Photo XMP, the clip right after it, resumable).
     */
    suspend fun download(
        context: Context,
        link: CameraLink,
        uri: String,
        overwrite: Boolean = false,
        takenAtMillis: Long? = null,
        liveVideo: String? = null,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val name = uri.substringAfterLast('/')
        val mime = mimeType(name)
        val video = mime.startsWith("video/")
        val resolver = context.contentResolver
        // Live photo: the photo part (with the XMP) is the same on every attempt, so a resume can trust it.
        val live = liveVideo?.let { clip ->
            val size = contentLength(link, clip) ?: throw IOException("нет размера видео $clip")
            Triple(clip, size, LivePhoto.head(fetch(link, uri), size))
        }
        val existing = findExisting(resolver, collection(video), name)
        val item = existing?.uri ?: resolver.insert(
            collection(video),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TARGET)
                takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("MediaStore insert failed")
        if (existing != null && !existing.pending && !overwrite) return@withContext item

        var restart = existing != null && !existing.pending // overwrite: start from byte 0
        if (restart) {
            // Back to pending while rewriting: an interrupted overwrite must not look like a complete file.
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null)
        }
        var attempt = 0
        while (true) {
            // A fresh item has no file on disk until the first write: "r" throws FileNotFoundException.
            val have = if (restart) 0L else runCatching {
                resolver.openFileDescriptor(item, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
            restart = false
            try {
                if (live == null) {
                    transfer(resolver, link, uri, item, have, onProgress)
                } else {
                    val (clip, size, head) = live
                    val base = head.size.toLong()
                    if (have < base) writeAt(resolver, item, 0, head)
                    val total = base + size
                    transfer(resolver, link, clip, item, (have - base).coerceIn(0, size), { done, _ -> onProgress(base + done, total) }, base)
                    onProgress(total, total)
                }
                break
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw e
                delay(1_500L * attempt)
            }
        }
        resolver.update(
            item,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            },
            null,
            null,
        )
        item
    }

    /** Truncates the item to [at] and writes [bytes] there (live photo: its photo part). */
    private fun writeAt(resolver: ContentResolver, item: Uri, at: Long, bytes: ByteArray) {
        resolver.openFileDescriptor(item, "rw")!!.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                out.channel.truncate(at)
                out.channel.position(at)
                out.write(bytes)
            }
        }
    }

    /**
     * Writes the camera file from its byte [from] (Range) to the end, into the item at [base] + [from] (the live
     * photo's clip follows its photo part); throws IOException on a short transfer.
     */
    private suspend fun transfer(
        resolver: ContentResolver,
        link: CameraLink,
        uri: String,
        item: Uri,
        from: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        base: Long = 0,
    ) {
        val conn = open(link, uri)
        try {
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            var start = from
            val total = when (val code = conn.responseCode) {
                206 -> conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: (from + conn.contentLengthLong)
                200 -> { start = 0; conn.contentLengthLong }
                416 -> return // nothing left: the file is already complete
                else -> throw IOException("HTTP $code")
            }
            var done = start
            resolver.openFileDescriptor(item, "rw")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.channel.truncate(base + start)
                    out.channel.position(base + start)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var reportedAt = 0L
                        onProgress(done, total)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - reportedAt >= 250) {
                                reportedAt = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total >= 0 && done != total) throw IOException("получено $done из $total байт")
        } finally {
            conn.disconnect()
        }
    }

    /** File size from a HEAD request (the file list has no size for some files, e.g. RAW .dng); null on failure. */
    suspend fun contentLength(link: CameraLink, uri: String): Long? = withContext(Dispatchers.IO) {
        val conn = runCatching { open(link, uri) }.getOrNull() ?: return@withContext null
        try {
            conn.requestMethod = "HEAD"
            // The camera's server sends the body even for HEAD: a kept-alive connection would hand that video to
            // the next request as its «status line». Close it after this one.
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode == 200) conn.contentLengthLong.takeIf { it > 0 } else null
        } catch (e: java.io.IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(link: CameraLink, uri: String): HttpURLConnection =
        (link.openConnection(URL("http://${CameraTcp.HOST}$uri")) as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 20_000
        }

    /** MIME by extension: a wrong one (e.g. image/jpeg for .dng) makes MediaProvider rename the file to "x.dng.jpg". */
    fun mimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "dng" -> "image/x-adobe-dng"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase())
            ?: "application/octet-stream"
    }

    private fun collection(video: Boolean): Uri = if (video) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /** The finished gallery item for a camera file name (for opening it in the phone's player), or null. */
    fun galleryUri(context: Context, name: String): Uri? {
        val video = mimeType(name).startsWith("video/")
        return findExisting(context.contentResolver, collection(video), name)?.takeIf { !it.pending }?.uri
    }

    private class Existing(val uri: Uri, val pending: Boolean)

    /** Our item with this name, pending (partial) ones included. */
    private fun findExisting(resolver: ContentResolver, collection: Uri, name: String): Existing? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ($OUR_FOLDERS)"
        val args = arrayOf(name)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(
                collection,
                projection,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                },
                null,
            )
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        cursor?.use {
            if (it.moveToFirst()) return Existing(ContentUris.withAppendedId(collection, it.getLong(0)), it.getInt(1) != 0)
        }
        return null
    }
}
