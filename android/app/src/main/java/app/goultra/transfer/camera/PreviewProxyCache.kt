package app.goultra.transfer.camera

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Low-resolution copies of the phone's own videos, used by the project preview instead of the originals. Decoding a
 * 4K frame costs the player about as much as six 540p frames: the copies trade disk space for a preview that keeps up
 * with the music. Camera files keep their own LRV; only phone videos taller/wider than [LONG_SIDE] are copied.
 *
 * A copy is keyed by its source (URI, size and date), so a replaced or re-edited file gets a new key and the old copy
 * is simply trimmed away. Writes go to `*.part` and are renamed on success: a killed process never leaves a truncated
 * file that the player could mistake for a ready copy. Sizes are trimmed least-recently-used first.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PreviewProxyCache(context: Context, private val log: (String) -> Unit = {}) {
    companion object {
        /** A source whose longer side is above this is worth a 540-line preview copy. */
        internal const val LONG_SIDE = 960
        private const val SHORT_SIDE = 540
        private const val BITRATE = 2_500_000

        /** Frequent key frames: a piece starts at an arbitrary time inside the long copy, and a 1 s GOP made the
         * player decode up to a second of frames before the first one could be shown (97–105 ms at each join). */
        private const val GOP_SECONDS = 0.25f
        private const val KEY_VERSION = 2
        private const val PART = ".part.mp4"
        private const val READY = ".mp4"

        /** The resolution of a copy: the longer side stays near 960, the shorter near 540, the aspect untouched. */
        internal fun size(width: Int, height: Int): Pair<Int, Int> =
            if (width >= height) LONG_SIDE to SHORT_SIDE else SHORT_SIDE to LONG_SIDE

        /** Whether a source of this size is worth copying; unknown dimensions are inspected before deciding. */
        internal fun needsProxy(width: Int?, height: Int?): Boolean {
            if (width == null || height == null || width <= 0 || height <= 0) return false
            return maxOf(width, height) > LONG_SIDE
        }

        /** A stable name for a source; a different size or recording time means different bytes. */
        internal fun keyFor(uri: String, size: Long?, createdMs: Long?): String {
            val input = "$KEY_VERSION|$uri|${size ?: -1L}|${createdMs ?: -1L}"
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private val app: Context = context.applicationContext
    private val dir = File(app.cacheDir, "preview-proxy")
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    /** A ready copy for [file], or null while it has to be made. */
    fun proxyFor(file: CameraFile): File? {
        if (!eligible(file)) return null
        val ready = File(dir, keyFor(file) + READY)
        return ready.takeIf { it.isFile && it.length() > 0L }
    }

    /** Marks a copy as just used: the least recently used ones go first when the cache is full. */
    fun touch(file: CameraFile) {
        proxyFor(file)?.setLastModified(System.currentTimeMillis())
    }

    /**
     * Makes the copy for [file] if it is missing. Returns null for a file that needs none, or when the phone cannot
     * encode it (the original is still playable, so a failed copy is only a missed optimisation, never an error).
     */
    suspend fun ensure(file: CameraFile): File? {
        if (!eligible(file)) return null
        migrateIfNeeded()
        proxyFor(file)?.let { touch(file); return it }
        val uri = file.localUri ?: return null
        val (width, height) = dimensions(uri) ?: (file.width to file.height)
        if (!needsProxy(width, height)) return null
        val key = keyFor(file)
        val out = File(dir, key + READY)
        val part = File(dir, key + PART)
        synchronized(lock) { part.delete() }
        val (targetWidth, targetHeight) = size(width!!, height!!)
        val edited = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.parse(uri)).build())
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            targetWidth,
                            targetHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                    ),
                ),
            )
            .build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(listOf(edited)).build(),
        ).build()
        val done = CompletableDeferred<Unit>()
        var failed = false
        val encoder = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(BITRATE)
                    .setiFrameIntervalSeconds(GOP_SECONDS)
                    .build(),
            )
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException,
                ) {
                    failed = true
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        try {
            withContext(Dispatchers.Main.immediate) { transformer.start(composition, part.absolutePath) }
            done.await()
            coroutineContext.ensureActive()
            if (!part.isFile || part.length() <= 0L) return null
            synchronized(lock) {
                out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
            }
            return out.takeIf { it.isFile && it.length() > 0L }
                ?.also { log("превью-копия ${file.name}: ${it.length() / 1024} КБ, ${targetWidth}×${targetHeight}") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("превью-копия ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            if (!done.isCompleted) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { transformer.cancel() }
                }
            }
            if (failed) part.delete()
        }
    }

    /** Frees space, least recently used first; [keep] is never deleted. */
    fun trim(maxBytes: Long, keep: File? = null) {
        synchronized(lock) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(PART) }.orEmpty().forEach { it.delete() }
            val ready = dir.listFiles { f -> f.isFile && f.name.endsWith(READY) }.orEmpty()
            var total = ready.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in ready.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                if (f == keep) continue
                val size = f.length()
                if (f.delete()) total -= size
            }
        }
    }

    /** Every byte of the cache: copies and unfinished ones. */
    fun bytes(): Long = synchronized(lock) {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clear() = synchronized(lock) {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    /** A cache made with other encoder settings is not reused: old copies are dropped once, then the marker stays. */
    private suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val marker = File(dir, "version")
            val stored = runCatching { marker.readText().trim() }.getOrNull()
            if (stored == KEY_VERSION.toString()) return@synchronized
            dir.listFiles().orEmpty().filter { it.name != "version" }.forEach { it.deleteRecursively() }
            runCatching { marker.writeText(KEY_VERSION.toString()) }
        }
    }

    private fun eligible(file: CameraFile): Boolean =
        file.isOnPhone && file.kind == MediaKind.VIDEO &&
            (file.width == null || file.height == null || needsProxy(file.width, file.height))

    private fun keyFor(file: CameraFile): String =
        keyFor(
            file.localUri ?: file.uri,
            file.size,
            file.created?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
        )

    private suspend fun dimensions(uri: String): Pair<Int?, Int?>? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, Uri.parse(uri))
            fun key(name: Int) = retriever.extractMetadata(name)?.toIntOrNull()
            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) to key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}