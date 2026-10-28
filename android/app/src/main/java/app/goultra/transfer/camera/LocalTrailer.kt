package app.goultra.transfer.camera

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Insta360 trailer at the end of a downloaded mp4 — the same records the camera hands out with GetFileExtra.
 * The last 78 bytes: `[u16 id][u32 size]` of the last record, …, `[u32 trailer size]` at −40, `[u32 version]`,
 * the 32-char magic. Each record is its data followed by that 6-byte footer. The last record (id 0) is a table of
 * `[u16 id][u32 size][u32 offset from the trailer start]` entries, with empty slots in between; without it the
 * records are walked back one by one (as ExifTool does).
 */
object LocalTrailer {
    private const val MAGIC = "8db42d694ccc418790edff439fe026bf"
    private const val TAIL = 78
    private const val RECORD_TABLE = 0x0000
    private const val RECORD_METADATA = 0x0101
    private const val RECORD_HIGHLIGHT = 0x000a
    private const val MAX_RECORD = 4_000_000L

    /**
     * [highlight]: the raw 0x000a record (for [Highlights.parse]); [firstFrameUs]: metadata field 24; [colorMode]:
     * metadata 212.8 (2 = I-Log, see [CameraFile.colorMode]).
     */
    class Info(val highlight: ByteArray?, val firstFrameUs: Long?, val colorMode: Int? = null)

    fun read(context: Context, uri: Uri): Info? = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).channel.use { ch ->
            parse(ch.size()) { at, n ->
                val buf = ByteBuffer.allocate(n)
                while (buf.hasRemaining() && ch.read(buf, at + buf.position()) > 0) Unit
                buf.array()
            }
        }
    }

    /** [read] (position, length) → bytes of a file [size] bytes long; null when there is no Insta360 trailer. */
    fun parse(size: Long, read: (Long, Int) -> ByteArray): Info? {
        if (size < TAIL) return null
        val tail = read(size - TAIL, TAIL)
        if (String(tail, TAIL - 32, 32, Charsets.US_ASCII) != MAGIC) return null
        val le = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val trailerSize = le.getInt(38).toLong() and 0xFFFFFFFFL
        if (trailerSize > size) return null
        val start = size - trailerSize
        val records = mutableMapOf<Int, ByteArray>()
        fun want(id: Int) = id == RECORD_HIGHLIGHT || id == RECORD_METADATA

        val lastId = le.getShort(0).toInt() and 0xFFFF
        val lastSize = le.getInt(2).toLong() and 0xFFFFFFFFL
        val lastAt = size - TAIL - lastSize
        if (lastId == RECORD_TABLE && lastSize in 1..MAX_RECORD && lastAt >= start) {
            val table = ByteBuffer.wrap(read(lastAt, lastSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i + 10 <= table.capacity()) {
                val id = table.getShort(i).toInt() and 0xFFFF
                val len = table.getInt(i + 2).toLong() and 0xFFFFFFFFL
                val offset = table.getInt(i + 6).toLong() and 0xFFFFFFFFL
                i += 10 // empty slots (id 0, size 0) sit between real entries: never stop at one
                if (want(id) && len in 1..MAX_RECORD && offset + len <= trailerSize) records[id] = read(start + offset, len.toInt())
            }
        } else {
            // No table: data, footer, data, footer… walked back from the end.
            var id = lastId
            var len = lastSize
            var footerAt = size - TAIL
            while (len <= MAX_RECORD && footerAt - len >= start) {
                val dataAt = footerAt - len
                if (want(id) && len > 0) records[id] = read(dataAt, len.toInt())
                footerAt = dataAt - 6
                if (footerAt < start) break
                val footer = ByteBuffer.wrap(read(footerAt, 6)).order(ByteOrder.LITTLE_ENDIAN)
                id = footer.getShort(0).toInt() and 0xFFFF
                len = footer.getInt(2).toLong() and 0xFFFFFFFFL
            }
        }
        val firstFrame = records[RECORD_METADATA]?.let { meta ->
            runCatching { meta.pbFields().field(24)?.varint }.getOrNull()
        }
        val colorMode = records[RECORD_METADATA]?.let { meta -> runCatching { CameraFile.colorMode(meta.pbFields()) }.getOrNull() }
        return Info(records[RECORD_HIGHLIGHT], firstFrame, colorMode)
    }
}
