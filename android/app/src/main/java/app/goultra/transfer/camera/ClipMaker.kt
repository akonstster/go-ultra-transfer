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
    ): List<Highlight> = cutAt(src, video, pieces, 0L, write, onProgress).first

    /** [cut] with the clip's time starting at [baseUs] (a source after others); also returns where the clip now ends. */
    private fun cutAt(
        src: Samples,
        video: Int,
        pieces: List<Highlight>,
        baseUs: Long,
        write: (track: Int, clipUs: Long, sync: Boolean) -> Unit,
        onProgress: (Long) -> Unit,
    ): Pair<List<Highlight>, Long> {
        val out = mutableListOf<Highlight>()
        var base = baseUs // clip time where this piece starts
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
                    // A frame within the last ms counts as reaching the end: ends are whole ms, key frame times are
                    // rounded up to whole ms (an end set on a key frame must not skip to the next one).
                    if (cutEnd < 0 && t > cutStart && t + 1000 > endUs && (src.isSync || !reordered)) cutEnd = t
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
        return out to base
    }

    /** What two files must share to be joined into one clip without re-encoding: one track format for all of them. */
    data class Signature(
        val videoMime: String,
        val width: Int,
        val height: Int,
        val rotation: Int,
        /** The codec's own set-up (SPS/PPS…): a different one means the decoder would read the joined frames wrong. */
        val videoSetup: String,
        val audioMime: String?,
        val sampleRate: Int,
        val channels: Int,
    )

    /** Why one file cannot follow another in a clip made without re-encoding. */
    enum class Mismatch { CODEC, SIZE, ROTATION, SETUP, SOUND }

    /** Why [b] cannot follow [a] in a clip made without re-encoding (null: it can). */
    fun mismatch(a: Signature, b: Signature): Mismatch? = when {
        a.videoMime != b.videoMime -> Mismatch.CODEC
        a.width != b.width || a.height != b.height -> Mismatch.SIZE
        a.rotation != b.rotation -> Mismatch.ROTATION
        a.videoSetup != b.videoSetup -> Mismatch.SETUP
        a.audioMime != b.audioMime || a.sampleRate != b.sampleRate || a.channels != b.channels -> Mismatch.SOUND
        else -> null
    }

    /** Thrown by [joinAll] when a file's formats differ from the first one's: [piece] (from 1), why, and both formats. */
    class MismatchException(val piece: Int, val why: Mismatch, val first: Signature, val other: Signature) :
        IllegalStateException("кусок $piece: $why ($first / $other)")

    private fun MediaFormat.int(key: String) = if (containsKey(key)) getInteger(key) else 0

    private fun signature(v: MediaFormat, a: MediaFormat?): Signature {
        val setup = listOf("csd-0", "csd-1").mapNotNull { key ->
            v.getByteBuffer(key)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) }.contentHashCode() }
        }.joinToString(":")
        return Signature(
            v.getString(MediaFormat.KEY_MIME).orEmpty(), v.int(MediaFormat.KEY_WIDTH), v.int(MediaFormat.KEY_HEIGHT),
            v.int(MediaFormat.KEY_ROTATION), setup,
            a?.getString(MediaFormat.KEY_MIME), a?.int(MediaFormat.KEY_SAMPLE_RATE) ?: 0, a?.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
        )
    }

    /** One file of a clip made of several: how its extractor is set up and the pieces taken from it (sorted, apart). */
    class Source(val open: (MediaExtractor) -> Unit, val pieces: List<Highlight>)

    /**
     * Several files' pieces, one file after another, into one mp4 without re-encoding — a project «as shot». Every file
     * has to share the first one's formats ([mismatch]: thrown as IllegalStateException with the reason). Returns the
     * clip's length, µs; [onProgress] gets µs of the clip so far.
     */
    fun joinAll(sources: List<Source>, out: FileDescriptor, withSound: Boolean = true, onProgress: (Long) -> Unit): Long {
        var muxer: MediaMuxer? = null
        var first: Signature? = null
        var videoOut = -1
        var audioOut = -1
        var base = 0L
        try {
            for ((n, s) in sources.withIndex()) {
                val ex = MediaExtractor()
                try {
                    s.open(ex)
                    var video = -1
                    var audio = -1
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (video < 0 && mime.startsWith("video/")) video = i
                        if (audio < 0 && mime.startsWith("audio/")) audio = i
                    }
                    require(video >= 0) { "в файле нет видео" }
                    val vFormat = ex.getTrackFormat(video)
                    val aFormat = if (withSound && audio >= 0) ex.getTrackFormat(audio) else null
                    val sig = signature(vFormat, aFormat)
                    val m = muxer ?: MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
                        muxer = m
                        first = sig
                        if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
                        videoOut = m.addTrack(vFormat)
                        if (aFormat != null) audioOut = m.addTrack(aFormat)
                        m.start()
                    }
                    first?.let { f -> mismatch(f, sig)?.let { throw MismatchException(n + 1, it, f, sig) } }
                    ex.selectTrack(video)
                    if (audioOut >= 0) ex.selectTrack(audio)
                    val tracks = buildMap { put(video, videoOut); if (audioOut >= 0) put(audio, audioOut) }
                    val maxInput = tracks.keys.maxOf { ex.getTrackFormat(it).int(MediaFormat.KEY_MAX_INPUT_SIZE) }
                    val buf = ByteBuffer.allocateDirect(maxOf(maxInput, 8 * 1024 * 1024))
                    val info = MediaCodec.BufferInfo()
                    val samples = object : Samples {
                        override val track get() = ex.sampleTrackIndex
                        override val timeUs get() = ex.sampleTime
                        override val isSync get() = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        override fun seek(us: Long) = ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        override fun advance() { ex.advance() }
                    }
                    base = cutAt(samples, video, s.pieces, base, { track, clipUs, sync ->
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size > 0) {
                            info.set(0, size, clipUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            m.writeSampleData(tracks.getValue(track), buf, info)
                        }
                    }, onProgress).second
                } finally {
                    ex.release()
                }
            }
            check(base > 0) { "ни одного куска" }
            muxer?.stop()
            return base
        } finally {
            runCatching { muxer?.release() }
        }
    }

    /**
     * Writes the clip of [pieces] from the video [open] sets up on an extractor, as an mp4 into [out]; without
     * [withSound] the sound track is left out. Returns the pieces as cut, or throws (IOException from the source,
     * IllegalStateException from the muxer).
     */
    fun join(
        open: (MediaExtractor) -> Unit,
        pieces: List<Highlight>,
        out: FileDescriptor,
        withSound: Boolean = true,
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
                if (withSound && audio < 0 && mime.startsWith("audio/")) audio = i
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
