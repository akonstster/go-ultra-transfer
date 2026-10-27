package app.goultra.transfer.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * One clip of the marked pieces of a video, without re-encoding: samples are copied as they are. A piece has to start
 * on a key frame, so it starts at the key frame at or before its start; it ends exactly where marked (the first frame at
 * or after its end) — only a video with B-frames (frames stored out of display order) ends on a key frame too.
 * A piece whose key frame lies in what the previous piece already wrote simply carries on from there (no frame twice).
 * Only the video and the first audio track are kept (no Insta360 trailer, so the clip carries no marks of its own).
 */
object ClipMaker {
    /** The current sample of a source and moving through them (as [MediaExtractor] gives them: in time order). */
    interface Samples {
        /** Track of the current sample; −1 past the end. */
        val track: Int
        val timeUs: Long
        val isSync: Boolean

        /** To [us]: video to the key frame at or before it, other tracks to about [us]. */
        fun seek(us: Long)
        fun advance()
    }

    /** After the cut end, samples still come for this long (other tracks lag behind in the file). */
    private const val TAIL_US = 1_000_000L

    /**
     * Cuts [pieces] (ms, with ends; sorted, not overlapping) out of [src]: [write] gets each kept sample — its track,
     * its time in the clip, key frame or not — while it is the current one. Returns the pieces as actually cut (ms of
     * the source; a piece carried on from the previous one is merged into it). [onProgress]: µs of the clip so far.
     */
    fun cut(
        src: Samples,
        video: Int,
        pieces: List<Highlight>,
        write: (track: Int, clipUs: Long, sync: Boolean) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): List<Highlight> {
        val out = mutableListOf<Highlight>()
        var base = 0L // clip time where this piece starts
        var prevEnd = -1L // source time where the previous piece ended (its first frame not taken)
        var reordered = false // B-frames seen: then a piece may only end on a key frame
        for (piece in pieces) {
            val startUs = piece.startMs * 1000
            val endUs = (piece.endMs ?: piece.startMs) * 1000
            if (endUs <= prevEnd) continue // already inside what was written
            // The key frame this piece has to start from.
            src.seek(startUs)
            while (src.track >= 0 && src.track != video) src.advance()
            if (src.track < 0) continue
            val key = src.timeUs
            // In what the previous piece wrote: go on from its end (the decoder has every frame up to there).
            val carryOn = prevEnd >= 0 && key < prevEnd
            val from = if (carryOn) prevEnd else key
            src.seek(from) // again, so the sound starts there too, not at the piece's own start
            var cutStart = -1L
            var cutEnd = -1L
            var lastVideo = -1L
            var prevVideo = -1L
            var frame = 33_333L
            while (src.track >= 0) {
                val t = src.timeUs
                if (src.track == video) {
                    if (prevVideo >= 0 && t < prevVideo) reordered = true
                    if (prevVideo in 0 until t && cutStart >= 0) frame = t - prevVideo
                    prevVideo = t
                    if (cutStart < 0) {
                        if (t < from || (!carryOn && !src.isSync)) { src.advance(); continue }
                        cutStart = t
                    }
                    if (cutEnd < 0 && t > cutStart && t >= endUs && (src.isSync || !reordered)) cutEnd = t
                    if (cutEnd < 0) lastVideo = maxOf(lastVideo, t)
                }
                if (cutEnd >= 0 && t > cutEnd + TAIL_US) break
                if (cutStart >= 0 && t >= cutStart && (cutEnd < 0 || t < cutEnd)) {
                    write(src.track, base + t - cutStart, src.track == video && src.isSync)
                    if (src.track == video) onProgress(base + t - cutStart)
                }
                src.advance()
            }
            if (cutStart < 0) continue // nothing left of the video here
            if (cutEnd < 0) cutEnd = lastVideo + frame // the file ended inside the piece
            if (carryOn && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(endMs = cutEnd / 1000)
            else out += Highlight(cutStart / 1000, cutEnd / 1000)
            base += cutEnd - cutStart
            prevEnd = cutEnd
        }
        return out
    }

    /**
     * Writes the clip of [pieces] from the video [open] sets up on an extractor, as an mp4 into [out].
     * Returns the pieces as cut, or throws (IOException from the source, IllegalStateException from the muxer).
     */
    fun join(
        open: (MediaExtractor) -> Unit,
        pieces: List<Highlight>,
        out: FileDescriptor,
        onProgress: (Long) -> Unit,
    ): List<Highlight> {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            open(ex)
            var video = -1
            var audio = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (video < 0 && mime.startsWith("video/")) video = i
                if (audio < 0 && mime.startsWith("audio/")) audio = i
            }
            require(video >= 0) { "в файле нет видео" }
            val vFormat = ex.getTrackFormat(video)
            val m = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
            val tracks = mutableMapOf(video to m.addTrack(vFormat))
            ex.selectTrack(video)
            if (audio >= 0) {
                tracks[audio] = m.addTrack(ex.getTrackFormat(audio))
                ex.selectTrack(audio)
            }
            m.start()
            val maxInput = (0 until ex.trackCount).filter { it in tracks }.maxOf { i ->
                ex.getTrackFormat(i).let { f -> if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
            }
            val buf = ByteBuffer.allocateDirect(maxOf(maxInput, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val samples = object : Samples {
                override val track get() = ex.sampleTrackIndex
                override val timeUs get() = ex.sampleTime
                override val isSync get() = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                override fun seek(us: Long) = ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                override fun advance() { ex.advance() }
            }
            val cut = cut(samples, video, pieces, { track, clipUs, sync ->
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size > 0) {
                    info.set(0, size, clipUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(tracks.getValue(track), buf, info)
                }
            }, onProgress)
            check(cut.isNotEmpty()) { "ни одного куска" }
            m.stop()
            return cut
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
