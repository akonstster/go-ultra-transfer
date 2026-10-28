package app.goultra.transfer.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Frames of a video that stays on the camera: MediaMetadataRetriever over HTTP Range reads, so only the index (moov)
 * and the key frames near the asked times travel — not the whole file. For the film strip of highlight pieces;
 * [local] does the same for a copy already on the phone.
 */
object RemoteFrames {
    /** Frames at [timesMs], [width] px wide (nearest key frame: quick, close enough for a thumbnail); null = not read. */
    fun frames(link: CameraLink, uri: String, size: Long, timesMs: List<Long>, width: Int): List<Bitmap?> =
        frames(timesMs, width) { it.setDataSource(CameraRangeSource(link, uri, size)) }

    /** The same for a downloaded copy on the phone (a gallery content:// item). */
    fun local(context: Context, uri: Uri, timesMs: List<Long>, width: Int): List<Bitmap?> =
        frames(timesMs, width) { it.setDataSource(context, uri) }

    private fun frames(timesMs: List<Long>, width: Int, open: (MediaMetadataRetriever) -> Unit): List<Bitmap?> {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
            timesMs.map { t ->
                runCatching {
                    retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, width * h / w)
                }.getOrNull()
            }
        } catch (e: RuntimeException) {
            timesMs.map { null }
        } finally {
            retriever.release()
        }
    }

    /** [FrameReader] of a camera file over Range reads (only the index and the key frames asked for travel). */
    fun reader(link: CameraLink, uri: String, size: Long): FrameReader? =
        FrameReader.open { it.setDataSource(CameraRangeSource(link, uri, size)) }

    /** [FrameReader] of a copy on the phone. */
    fun localReader(context: Context, uri: Uri): FrameReader? = FrameReader.open { it.setDataSource(context, uri) }
}

/**
 * One video kept open for many single frames — the editor's timeline asks for them one by one as it scrolls.
 * Nearest key frame (quick); not thread-safe: one caller at a time, [close] when done.
 */
class FrameReader private constructor(private val retriever: MediaMetadataRetriever) {
    private val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
    private val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9

    fun frame(ms: Long, width: Int): Bitmap? = runCatching {
        retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, width * h / w)
    }.getOrNull()

    /**
     * The frame at exactly [ms] (decoded from the key frame before it: slower), its long side about [size] — the still
     * the viewer's colour is shown on while paused. Upright already: the retriever turns frames by the video's rotation
     * (turning it again laid a vertical video on its side).
     */
    fun exact(ms: Long, size: Int): Bitmap? = runCatching {
        retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, size, size)
    }.getOrNull()

    fun close() = retriever.release()

    companion object {
        fun open(setup: (MediaMetadataRetriever) -> Unit): FrameReader? {
            val retriever = MediaMetadataRetriever()
            return try {
                setup(retriever)
                FrameReader(retriever)
            } catch (e: RuntimeException) {
                retriever.release()
                null
            }
        }
    }
}
