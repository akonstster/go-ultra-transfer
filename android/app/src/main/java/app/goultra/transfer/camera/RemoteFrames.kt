package app.goultra.transfer.camera

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.runBlocking

/**
 * Frames of a video that stays on the camera: MediaMetadataRetriever over HTTP Range reads, so only the index (moov)
 * and the key frames near the asked times travel — not the whole file. For the film strip of highlight pieces.
 */
object RemoteFrames {
    /** Frames at [timesMs], [width] px wide (nearest key frame: quick, close enough for a thumbnail); null = not read. */
    fun frames(link: CameraLink, uri: String, size: Long, timesMs: List<Long>, width: Int): List<Bitmap?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(RangeSource(link, uri, size))
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

    /** Reads a camera file in [BLOCK] pieces, keeping the last few (the retriever re-reads the same spots). */
    private class RangeSource(private val link: CameraLink, private val uri: String, private val size: Long) : MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > 8
        }

        override fun getSize() = size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val index = position / BLOCK
            val block = blocks[index] ?: runBlocking {
                Downloader.fetchRange(link, uri, index * BLOCK, minOf(size, (index + 1) * BLOCK) - 1)
            }?.also { blocks[index] = it } ?: return -1
            val from = (position - index * BLOCK).toInt()
            val n = minOf(length, block.size - from)
            if (n <= 0) return -1
            System.arraycopy(block, from, buffer, offset, n)
            return n
        }

        override fun close() = blocks.clear()
    }

    private const val BLOCK = 256 * 1024L
}
