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
import java.io.FileInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Downloads http://192.168.42.1/<uri> straight into the shared gallery: DCIM/Camera, next to the phone's own shots,
 * with DATE_TAKEN set from the camera so files land at the right place in the timeline.
 * An unfinished file stays a pending MediaStore item and is resumed with an HTTP Range request
 * (after a network hiccup, a cancel or on the next connection). Replacements keep the old item until the new one is ready.
 */
object Downloader {
    private val TARGET = Environment.DIRECTORY_DCIM + "/Camera"
    private const val MAX_RETRIES = 5

    /** Live photos get the Huawei Gallery trailer on Huawei phones so the gallery recognizes them (verified on Mate 80 Pro). */
    internal fun isHuawei(): Boolean = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

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
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    names += name
                    names += LivePhoto.cameraName(name)
                }
            }
        }
        return names
    }

    /** [CameraFile.uri] of a file listed from the phone (no camera path). */
    const val PHONE_PREFIX = "/phone/"

    /**
     * Files this app saved to the gallery, as list items for the Files tab without the camera (newest first).
     * Date from the name like on the camera, else DATE_TAKEN; duration from MediaStore (videos only).
     */
    fun phoneFiles(context: Context): List<CameraFile> {
        val out = mutableListOf<Pair<CameraFile, Boolean>>()
        val motionNames = mutableSetOf<String>()
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
                    val storedName = c.getString(1) ?: continue
                    val name = LivePhoto.cameraName(storedName)
                    if (name != storedName) motionNames += name
                    val taken = if (c.isNull(3)) null else c.getLong(3)
                    out += CameraFile(
                        uri = "$PHONE_PREFIX$name",
                        size = if (c.isNull(2)) null else c.getLong(2),
                        durationSec = if (c.isNull(6)) null else ((c.getLong(6) + 500) / 1000).toInt(),
                        width = if (c.isNull(4)) null else c.getInt(4),
                        height = if (c.isNull(5)) null else c.getInt(5),
                        fps = null,
                        created = CameraFile.createdFromName(name) ?: taken?.let {
                            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
                        },
                        localUri = ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    ) to (name != storedName)
                }
            }
        }
        return out.filter { (file, isMotionName) -> file.name !in motionNames || isMotionName }
            .map { it.first }
            .sortedWith(compareByDescending<CameraFile> { it.created }.thenByDescending { it.name })
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
     * [overwrite]: re-download a file that is already complete in the gallery (no duplicate).
     * Otherwise a complete file is left as is and a partial one is resumed. [takenAtMillis] goes to DATE_TAKEN.
     * [liveVideo]: the original clip; [livePreview]: its optional LRV. Both are resumed in a pending item before
     * the two-track Motion Photo is assembled and published without replacing an existing file prematurely.
     */
    suspend fun download(
        context: Context,
        link: CameraLink,
        uri: String,
        overwrite: Boolean = false,
        takenAtMillis: Long? = null,
        liveVideo: String? = null,
        livePreview: String? = null,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val name = uri.substringAfterLast('/')
        val mime = mimeType(name)
        val video = mime.startsWith("video/")
        val resolver = context.contentResolver
        // Live photo: the photo part (with the XMP) is the same on every attempt, so a resume can trust it.
        val live = liveVideo?.let { clip ->
            val size = contentLength(link, clip) ?: throw IOException("нет размера видео $clip")
            val previewSize = livePreview?.let {
                contentLength(link, it) ?: throw IOException("нет размера превью $it")
            } ?: 0L
            LiveDownload(clip, size, LivePhoto.head(fetch(link, uri), size), livePreview, previewSize)
        }
        val targetName = if (live != null) LivePhoto.galleryName(name) else name
        val targetExisting = findExisting(resolver, collection(video), targetName)
        val legacyExisting = if (targetName != name) findExisting(resolver, collection(video), name) else null
        val complete = targetExisting?.takeIf { !it.pending } ?: legacyExisting?.takeIf { !it.pending }
        if (complete != null && !overwrite) return@withContext complete.uri
        val old = complete?.takeIf { overwrite }?.uri
        val oldName = if (complete === legacyExisting) name else targetName
        val stagingName = "GOUltra-replace-$targetName"
        val staging = if (old != null) findExisting(resolver, collection(video), stagingName) else null
        if (staging != null && !staging.pending) throw IOException("имя временного файла уже занято: $stagingName")
        val partial = if (old != null) staging else targetExisting ?: legacyExisting
        val item = partial?.uri ?: resolver.insert(
            collection(video),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, if (old != null) stagingName else targetName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TARGET)
                takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("MediaStore insert failed")
        var attempt = 0
        while (true) {
            // A fresh item has no file on disk until the first write: "r" throws FileNotFoundException.
            var have = runCatching {
                resolver.openFileDescriptor(item, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
            try {
                if (live == null) {
                    transfer(resolver, link, uri, item, have, onProgress)
                } else {
                    val (clip, size, head, preview, previewSize) = live
                    val base = head.size.toLong()
                    if (have > 0) {
                        val prefixLength = minOf(have, base).toInt()
                        val matches = resolver.openFileDescriptor(item, "r")?.use { pfd ->
                            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                                readFullyAt(input, 0, prefixLength).contentEquals(head.copyOf(prefixLength))
                            }
                        } ?: throw IOException("не открыть недокачанное живое фото")
                        if (!matches) {
                            writeAt(resolver, item, 0, head)
                            have = base
                        }
                    }
                    val trailer = if (preview == null && isHuawei()) LivePhoto.huaweiTrailer(size) else null
                    if (have > base + size + previewSize + (trailer?.size ?: 0))
                        throw IOException("недокачанное живое фото больше оригинала")
                    if (have < base) writeAt(resolver, item, 0, head)
                    val total = base + size + previewSize
                    if (have < base + size) {
                        transfer(resolver, link, clip, item, (have - base).coerceIn(0, size),
                            { done, _ -> onProgress(base + done, total) }, base, expectedSize = size)
                    }
                    if (preview != null && have < base + size + previewSize) {
                        transfer(resolver, link, preview, item, (have - base - size).coerceIn(0, previewSize),
                            { done, _ -> onProgress(base + size + done, total) }, base + size, expectedSize = previewSize)
                    }
                    if (trailer != null) writeAt(resolver, item, base + size, trailer)
                    onProgress(total, total)
                }
                break
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw e
                delay(1_500L * attempt)
            }
        }
        if (live != null && live.preview != null) {
            return@withContext completeLiveDownload(
                context, item, targetName, mime, takenAtMillis, old, oldName, live.head, live.size, live.previewSize,
            )
        }
        if (old != null) {
            publishReplacement(resolver, item, targetName, old, oldName)
        } else {
            val changed = resolver.update(
                item,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                    takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                },
                null,
                null,
            )
            if (changed != 1) throw IOException("не опубликовать скачанный файл $name")
            val stored = resolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (stored != targetName) throw IOException("MediaStore сохранил файл как $stored вместо $targetName")
        }
        item
    }

    internal fun completeLiveDownload(
        context: Context,
        item: Uri,
        targetName: String,
        mime: String,
        takenAtMillis: Long?,
        old: Uri?,
        oldName: String,
        head: ByteArray,
        originalSize: Long,
        previewSize: Long,
    ): Uri {
        val resolver = context.contentResolver
        val muxed = File.createTempFile("live-mux-", ".mp4", context.cacheDir)
        try {
            LiveMuxer.mux(resolver, item, head.size.toLong(), originalSize, previewSize, muxed)
            val finalHead = LivePhoto.head(head, muxed.length(), originalVideoSize = originalSize)
            val replacement = saveReplacement(
                resolver, collection(video = false), targetName, mime, takenAtMillis,
                old ?: item, oldName = if (old == null) targetName else oldName,
            ) { fd ->
                val inputDescriptor = resolver.openFileDescriptor(item, "r") ?: throw IOException("не открыть скачанное видео")
                inputDescriptor.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        input.channel.position(head.size.toLong())
                        FileOutputStream(fd).use { out ->
                            out.write(finalHead)
                            copyExactly(input, out, originalSize)
                            FileInputStream(muxed).use { preview -> copyExactly(preview, out, muxed.length()) }
                            if (isHuawei()) out.write(LivePhoto.huaweiTrailer(muxed.length()))
                        }
                    }
                }
            }
            if (old != null && resolver.delete(item, null, null) != 1)
                throw IOException("новое живое фото сохранено, но временный элемент $item не удалён")
            return replacement
        } finally {
            if (!muxed.delete() && muxed.exists()) android.util.Log.w("GUT", "не удалить временный файл ${muxed.name}")
        }
    }

    private data class LiveDownload(
        val clip: String,
        val size: Long,
        val head: ByteArray,
        val preview: String?,
        val previewSize: Long,
    )

    /** A clip made on the phone ([ClipMaker]) saved as [name] in DCIM/Camera. */
    fun saveClip(context: Context, name: String, takenAtMillis: Long?, write: (java.io.FileDescriptor) -> Unit): Uri =
        saveFile(context, name, "video/mp4", takenAtMillis, write)

    /** A file made on the phone (a clip, a photo with its colour) saved as [name] in DCIM/Camera, in the collection of its [mime]. */
    fun saveFile(context: Context, name: String, mime: String, takenAtMillis: Long?, write: (java.io.FileDescriptor) -> Unit): Uri {
        val resolver = context.contentResolver
        val collection = collection(video = mime.startsWith("video/"))
        val existing = findExisting(resolver, collection, name)
        if (existing?.pending == true) throw IOException("незавершённый файл с именем $name; сначала завершите его скачивание")
        val old = existing?.uri
        return saveReplacement(resolver, collection, name, mime, takenAtMillis, old, write = write)
    }

    /** Writes a replacement first; an existing item is kept under a backup name until publication succeeds. */
    internal fun saveReplacement(
        resolver: ContentResolver,
        collection: Uri,
        name: String,
        mime: String,
        takenAtMillis: Long?,
        old: Uri?,
        oldName: String = name,
        write: (java.io.FileDescriptor) -> Unit,
    ): Uri {
        val token = UUID.randomUUID().toString()
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val temporary = "GOUltra-$token.part$suffix"
        val item = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, temporary)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TARGET)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            },
        ) ?: error("MediaStore insert failed")
        try {
            val descriptor = resolver.openFileDescriptor(item, "rwt") ?: throw IOException("не открыть новый элемент $item")
            descriptor.use { write(it.fileDescriptor) }
        } catch (e: Exception) {
            try {
                if (resolver.delete(item, null, null) != 1) throw IOException("не удалить незавершённый элемент $item")
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
        publishReplacement(resolver, item, name, old, oldName)
        return item
    }

    private fun publishReplacement(resolver: ContentResolver, item: Uri, name: String, old: Uri?, oldName: String = name) {
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val backup = name.removeSuffix(suffix) + ".backup-${UUID.randomUUID()}" + suffix
        fun rename(uri: Uri, target: String, publish: Boolean = false) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target)
                if (publish) put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, values, null, null) != 1) throw IOException("не переименовать $uri в $target")
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (actual != target) throw IOException("MediaStore сохранил $uri как $actual вместо $target")
        }
        try {
            ReplacementTransaction.commit(
                hasOld = old != null,
                backupOld = { rename(requireNotNull(old), backup) },
                publishNew = { rename(item, name, publish = true) },
                discardNew = {
                    if (resolver.delete(item, null, null) != 1) throw IOException("не удалить новый элемент $item")
                },
                restoreOld = { rename(requireNotNull(old), oldName) },
                deleteOld = {
                    try {
                        if (resolver.delete(requireNotNull(old), null, null) != 1) throw IOException("MediaStore не удалил элемент")
                    } catch (e: Exception) {
                        throw IOException("новый файл сохранён, но резервная копия $backup не удалена: ${e.message}", e)
                    }
                },
            )
        } catch (e: Exception) {
            if (e.suppressed.isNotEmpty()) {
                throw IOException("${e.message}; ошибка восстановления: ${e.suppressed.joinToString { it.message ?: it.javaClass.simpleName }}; проверьте резервную копию $backup", e)
            }
            throw e
        }
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
        expectedSize: Long? = null,
    ) {
        val conn = open(link, uri)
        try {
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            var start = from
            val total = when (val code = conn.responseCode) {
                206 -> {
                    val range = conn.getHeaderField("Content-Range")
                    if (expectedSize != null && !HttpRanges.matchesRange(range, from, expectedSize))
                        throw IOException("неверный Content-Range для $uri: $range")
                    range?.substringAfterLast('/')?.toLongOrNull() ?: (from + conn.contentLengthLong)
                }
                200 -> { start = 0; conn.contentLengthLong }
                416 -> {
                    val size = HttpRanges.unsatisfiedSize(conn.getHeaderField("Content-Range")) ?: contentLength(link, uri)
                    if (!HttpRanges.isComplete(from, size)) throw IOException("HTTP 416: на телефоне $from байт, на камере ${size ?: "неизвестно"}")
                    if (expectedSize != null && size != expectedSize) throw IOException("размер файла $uri изменился")
                    onProgress(from, from)
                    return
                }
                else -> throw IOException("HTTP $code")
            }
            if (expectedSize != null && total >= 0 && total != expectedSize)
                throw IOException("размер файла $uri изменился: $total вместо $expectedSize")
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
            if (expectedSize != null && done != expectedSize) throw IOException("получено $done из $expectedSize байт")
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
        val normalized = LivePhoto.cameraName(name)
        val candidates = listOf(LivePhoto.galleryName(normalized), normalized).distinct()
        return candidates.firstNotNullOfOrNull { candidate ->
            findExisting(context.contentResolver, collection(video), candidate)?.takeIf { !it.pending }?.uri
        }
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
