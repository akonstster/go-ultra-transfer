package app.goultra.transfer.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A highlight mark in a video, ms from its start; [endMs] only for a segment added in the camera's playback. */
data class Highlight(val startMs: Long, val endMs: Long? = null)

/**
 * Highlight marks: the mp4 trailer record 0x000a (EXTRA_TYPE_HIGHLIGHT), from GetFileExtra(uri, 10).
 * Blocks `[type u8][count u32 LE][u64 LE µs …]`: type 1 = marked while recording (one value each), type 2 = added
 * in the camera's playback (start, end). Values are on the camera clock — relative to the file's
 * first_frame_timestamp; a value below it (seen: 1320000) is taken as the very start, as the camera's film strip
 * does. Other types carry no marks (count 0 seen).
 */
object Highlights {
    const val EXTRA_TYPE = 10L

    fun parse(record: ByteArray, firstFrameUs: Long?, durationMs: Long? = null): List<Highlight> {
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val base = firstFrameUs ?: 0L
        fun ms(us: Long) = maxOf(0, us - base) / 1000
        val out = mutableListOf<Highlight>()
        while (buf.remaining() >= 5) {
            val type = buf.get().toInt() and 0xFF
            val count = buf.int.toLong() and 0xFFFFFFFFL
            val perMark = if (type == 2) 2 else 1
            if (count * perMark * 8 > buf.remaining()) break  // not a layout we know: keep what we have
            repeat(count.toInt()) {
                val values = LongArray(perMark) { buf.long }
                when (type) {
                    1 -> out += Highlight(ms(values[0]))
                    2 -> out += Highlight(ms(values[0]), ms(values[1]))
                }
            }
        }
        return out.filter { durationMs == null || it.startMs <= durationMs }.sortedBy { it.startMs }
    }

    /** Half the piece the camera makes around a mark set while recording (2 s pieces). */
    const val AROUND_MS = 1000L

    /**
     * Pieces as the camera's film strip shows them: a recording mark becomes [AROUND_MS] either side, a playback
     * segment stays as it is; overlapping pieces merge into one.
     */
    fun pieces(marks: List<Highlight>, durationMs: Long? = null): List<Highlight> {
        val out = mutableListOf<Highlight>()
        marks.map { m -> if (m.endMs != null) m else Highlight(maxOf(0, m.startMs - AROUND_MS), m.startMs + AROUND_MS) }
            .map { p -> if (durationMs != null) p.copy(endMs = minOf(p.endMs!!, durationMs)) else p }
            .sortedBy { it.startMs }
            .forEach { p ->
                val last = out.lastOrNull()
                if (last != null && p.startMs <= last.endMs!!) out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, p.endMs!!))
                else out += p
            }
        return out
    }
}
