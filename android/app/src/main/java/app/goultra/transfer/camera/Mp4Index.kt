package app.goultra.transfer.camera

import kotlin.math.abs

/**
 * Key frames of a video, ms from its start — rounded up, so a piece started on one never falls before it (a clip
 * starts on the key frame at or before a piece's start). [bFrames]: frames stored out of display order; then a piece
 * may only end on a key frame too.
 */
class KeyFrames(val ms: List<Long>, val bFrames: Boolean) {
    /** The key frame nearest to [at] within [lo]..[hi]; [at] itself when there is none there. */
    fun nearest(at: Long, lo: Long = 0, hi: Long = Long.MAX_VALUE): Long =
        ms.filter { it in lo..hi }.minByOrNull { abs(it - at) } ?: at

    /**
     * Where a piece's start can stand nearest to [at]: a key frame in [lo]..[hi], or [touch] — the end of the piece
     * before it. Pieces may meet: the clip then runs on through there without a seam, so no key frame is needed.
     */
    fun nearestStart(at: Long, lo: Long, hi: Long, touch: Long?): Long =
        (ms.filter { it in lo..hi } + listOfNotNull(touch?.takeIf { it <= hi })).minByOrNull { abs(it - at) } ?: at

    /** [piece] starting where [nearestStart] puts it among [others] (not before an earlier one's end, not into its own). */
    fun snapStart(piece: Highlight, others: List<Highlight>): Highlight {
        val before = others.filter { it.endMs!! <= piece.startMs }.maxOfOrNull { it.endMs!! }
        return piece.copy(startMs = nearestStart(piece.startMs, before ?: 0L, piece.endMs!! - Highlights.MIN_PIECE_MS, before))
    }
}

/**
 * The key frames of an mp4's video track, from its index (moov) alone — no frame data is read, so for a file on the
 * camera only the index travels. stss = key frame numbers, stts = frame durations, ctts = display offsets (B-frames),
 * elst = where the presentation starts (applied as the platform extractor does).
 */
object Mp4Index {
    /**
     * The video track's average frame rate: its frames over their total duration (stts), as players and ffprobe
     * give it — from the index alone, right whatever the order the frames are stored in (B-frames). Null without an
     * index or a video track.
     */
    fun frameRate(size: Long, read: (Long, Int) -> ByteArray): Double? {
        val (timescale, stbl) = videoTrack(size, read) ?: return null
        val stts = child(stbl, "stts") ?: return null
        var frames = 0L
        var duration = 0L
        for (e in 0 until be32(stts, 4).toInt()) {
            val count = be32(stts, 8 + e * 8)
            frames += count
            duration += count * be32(stts, 12 + e * 8)
        }
        return if (frames > 0 && duration > 0) frames * timescale.toDouble() / duration else null
    }

    /** The video track's timescale and sample table (with its trak), from the index; null without one. */
    private fun videoTrack(size: Long, read: (Long, Int) -> ByteArray): Triple<Long, ByteArray, ByteArray>? {
        var pos = 0L
        var moov: ByteArray? = null
        while (pos + 8 <= size) {
            val head = read(pos, minOf(16L, size - pos).toInt())
            var len = be32(head, 0)
            var header = 8
            if (len == 1L && head.size >= 16) { len = be64(head, 8); header = 16 } else if (len == 0L) len = size - pos
            if (len < header) return null
            if (type(head, 4) == "moov") {
                if (len - header > 64L * 1024 * 1024) return null
                moov = read(pos + header, (len - header).toInt())
                break
            }
            pos += len
        }
        val index = moov ?: return null
        for (trak in boxes(index).filter { it.first == "trak" }.map { it.second }) {
            val mdia = child(trak, "mdia") ?: continue
            val hdlr = child(mdia, "hdlr") ?: continue
            if (hdlr.size < 12 || type(hdlr, 8) != "vide") continue
            val mdhd = child(mdia, "mdhd") ?: return null
            val timescale = if (mdhd[0].toInt() == 1) be32(mdhd, 20) else be32(mdhd, 12)
            if (timescale <= 0) return null
            val stbl = child(mdia, "minf")?.let { child(it, "stbl") } ?: return null
            return Triple(timescale, stbl, trak)
        }
        return null
    }

    /** [read] (position, length) → bytes of a file [size] bytes long; null when it has no index or no video track. */
    fun keyFrames(size: Long, read: (Long, Int) -> ByteArray): KeyFrames? {
        // Top-level boxes: the media data is skipped by its size, only the index is read.
        val (timescale, stbl, trak) = videoTrack(size, read) ?: return null
        val stts = child(stbl, "stts") ?: return null
        val stss = child(stbl, "stss") ?: return KeyFrames(emptyList(), false) // every frame is a key frame
        val ctts = child(stbl, "ctts")
        val shift = child(trak, "edts")?.let { child(it, "elst") }?.let(::mediaStart) ?: 0L
        return KeyFrames(times(stts, stss, ctts, shift).map { t -> ceilMs(t, timescale) }, hasOffsets(ctts))
    }

    /** Display time (track timescale) of each key frame. */
    private fun times(stts: ByteArray, stss: ByteArray, ctts: ByteArray?, shift: Long): List<Long> {
        val keys = (0 until be32(stss, 4).toInt()).map { be32(stss, 8 + it * 4) } // 1-based frame numbers
        val out = ArrayList<Long>(keys.size)
        // Walk the duration runs (stts) and the offset runs (ctts) alongside the key frame numbers.
        var entry = 0
        var runStart = 1L // first frame number of the current stts run
        var runTime = 0L // decode time of that frame
        val entries = be32(stts, 4).toInt()
        var cEntry = 0
        var cRunStart = 1L
        val cEntries = ctts?.let { be32(it, 4).toInt() } ?: 0
        for (n in keys) {
            while (entry < entries) {
                val count = be32(stts, 8 + entry * 8)
                if (n < runStart + count) break
                runTime += count * be32(stts, 12 + entry * 8)
                runStart += count
                entry++
            }
            if (entry >= entries) break
            val dts = runTime + (n - runStart) * be32(stts, 12 + entry * 8)
            var offset = 0L
            if (ctts != null) {
                while (cEntry < cEntries && n >= cRunStart + be32(ctts, 8 + cEntry * 8)) {
                    cRunStart += be32(ctts, 8 + cEntry * 8)
                    cEntry++
                }
                if (cEntry < cEntries) offset = be32(ctts, 12 + cEntry * 8).toInt().toLong() // signed in version 1
            }
            out += maxOf(0L, dts + offset - shift)
        }
        return out
    }

    /** ctts with any non-zero offset: frames are stored out of display order. */
    private fun hasOffsets(ctts: ByteArray?): Boolean =
        ctts != null && (0 until be32(ctts, 4).toInt()).any { be32(ctts, 12 + it * 8) != 0L }

    /** media_time of the first non-empty edit (track timescale). */
    private fun mediaStart(elst: ByteArray): Long? {
        val v1 = elst[0].toInt() == 1
        val size = if (v1) 20 else 12
        for (i in 0 until be32(elst, 4).toInt()) {
            val at = 8 + i * size
            val time = if (v1) be64(elst, at + 8) else be32(elst, at + 4).toInt().toLong()
            if (time >= 0) return time
        }
        return null
    }

    private fun ceilMs(t: Long, timescale: Long): Long = (t * 1000 + timescale - 1) / timescale

    /** Child boxes of a container's payload: (type, payload). */
    private fun boxes(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var pos = 0
        while (pos + 8 <= data.size) {
            var len = be32(data, pos)
            var header = 8
            if (len == 1L && pos + 16 <= data.size) { len = be64(data, pos + 8); header = 16 } else if (len == 0L) len = (data.size - pos).toLong()
            if (len < header || pos + len > data.size) break
            out += type(data, pos + 4) to data.copyOfRange(pos + header, (pos + len).toInt())
            pos += len.toInt()
        }
        return out
    }

    /** A child box's payload; full boxes (hdlr, mdhd, stts …) keep their version/flags word at the start. */
    private fun child(data: ByteArray, type: String): ByteArray? = boxes(data).firstOrNull { it.first == type }?.second

    private fun type(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)
}
