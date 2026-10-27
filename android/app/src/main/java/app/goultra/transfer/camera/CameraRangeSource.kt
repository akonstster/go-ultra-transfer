package app.goultra.transfer.camera

import android.media.MediaDataSource
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * A camera file as a [MediaDataSource]: read in [block]-sized HTTP Range pieces, the last [keep] kept (extractors
 * re-read the same spots), so only the parts asked for travel. [fetched]: bytes brought over so far.
 * [strict]: a failed read (after one retry) throws instead of ending the file early — for making clips, where a
 * silent end would cut the result short.
 */
class CameraRangeSource(
    private val link: CameraLink,
    private val uri: String,
    private val size: Long,
    private val block: Long = 256 * 1024L,
    private val keep: Int = 8,
    private val strict: Boolean = false,
) : MediaDataSource() {
    @Volatile var fetched = 0L
        private set

    private val blocks = object : LinkedHashMap<Long, ByteArray>(keep, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > keep
    }

    override fun getSize() = size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val index = position / block
        val data = blocks[index] ?: fetch(index)?.also { blocks[index] = it; fetched += it.size } ?: return -1
        val from = (position - index * block).toInt()
        val n = minOf(length, data.size - from)
        if (n <= 0) return -1
        System.arraycopy(data, from, buffer, offset, n)
        return n
    }

    private fun fetch(index: Long): ByteArray? {
        val from = index * block
        val to = minOf(size, from + block) - 1
        repeat(if (strict) 2 else 1) {
            runCatching { runBlocking { Downloader.fetchRange(link, uri, from, to) } }.getOrNull()
                ?.takeIf { it.size.toLong() == to - from + 1 }?.let { return it }
        }
        if (strict) throw IOException("не прочитан кусок $from–$to файла $uri")
        return null
    }

    override fun close() = blocks.clear()
}
