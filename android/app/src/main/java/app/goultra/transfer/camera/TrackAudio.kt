package app.goultra.transfer.camera

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A picked music track as the project needs it: the wave the lane draws and the beat grid the montage will snap to
 * (see `memory-bank/projects-plan.md`, P2/P3). The sound is decoded once by the phone's own decoder
 * ([ClipAudio.openPcm]) and brought to one channel at [RATE]; that one pass gives both the wave (a peak for every
 * [BUCKET_MS]) and the grid ([BeatGrid]). Everything but [analyse] is plain arithmetic, so it is tested without a
 * decoder — the same way `BeatGrid` is.
 */
object TrackAudio {
    /** The rate the analysis runs at: fine enough for onsets, a quarter of a 44.1 kHz stereo track's memory. */
    const val RATE = 22_050

    /** How many columns the lane draws. */
    const val BUCKETS = 800

    /** The width of one decoded peak, ms: the lane's columns are made of a few of them. */
    internal const val BUCKET_MS = 25

    /**
     * How much of a long track is listened to for the grid and the profile: four minutes is most tracks, and the beats
     * past it go on with the last period (a tempo that drifts is followed inside this share, not averaged over it).
     */
    internal const val ANALYSED_MS = 240_000

    /** Read from the decoder at a time, in decoded frames. */
    private const val CHUNK = 8_192

    /** A file that does not say how long it is is read no further than this. */
    private const val UNKNOWN_LIMIT_MS = 15L * 60_000L

    /**
     * What a track gives: the wave 0…1 ([peaks], one per [BUCKET_MS] along the whole track — the lane shows the share the
     * project plays, so it needs the fine one), the grid with its tempo followed where it drifts, the profile of the
     * piece (stability, drive, energy, drop), and how long the track is.
     */
    class Found(
        val peaks: FloatArray,
        val beats: List<Long>,
        val bpm: Double,
        val confidence: Double,
        val lengthMs: Long,
        val profile: BeatGrid.Profile?,
        /** The codec's own delay, ms, taken off the analysis (0 when the file has none): mp3 and aac carry some. */
        val delayMs: Long = 0L,
    )

    /**
     * The track at [uri] decoded and measured; null when it is not a sound the phone can read. Blocking — seconds of
     * decoding and arithmetic — so it is called from a worker, never from a composable.
     */
    fun analyse(context: Context, uri: Uri): Found? =
        analyse { extractor -> extractor.setDataSource(context, uri, null) }

    /** A file already on the phone (a debug probe, a downloaded copy): no SAF permission, the player's own path. */
    internal fun analyse(file: File): Found? =
        analyse { extractor -> extractor.setDataSource(file.absolutePath) }

    private fun analyse(open: (MediaExtractor) -> Unit): Found? {
        val reader = runCatching { ClipAudio.openPcm(open) }.getOrNull()
            ?: return null
        try {
            val step = (reader.rate.toDouble() / RATE).roundToInt().coerceAtLeast(1)
            val rate = (reader.rate / step).coerceAtLeast(1) // the mono rate, ~[RATE]
            val perBucket = (rate.toLong() * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
            val said = reader.durationMs
            val leftToRead = (if (said > 0L) said else UNKNOWN_LIMIT_MS) * reader.rate / 1000
            // The share the grid is taken from, one channel at [rate]: a minute and a half is a few megabytes.
            val heard = FloatArray((rate.toLong() * ANALYSED_MS / 1000).toInt().coerceAtLeast(0))
            var heardAt = 0
            val peaks = ArrayList<Float>(1024) // one per [BUCKET_MS]: a few thousand for a whole track
            var bucket = 0f
            var inBucket = 0
            var quiet = 0 // chunks read with nothing in them: the end of a file that does not say how long it is
            var at = 0L
            // The priming samples a player cuts off before anything is heard: dropped here too, or every beat of the
            // grid stands later than the sound by them and a click on it lands after the beat (see PcmReader.delayFrames).
            var priming = reader.delayFrames.toLong()
            while (at < leftToRead) {
                val count = minOf(CHUNK.toLong(), leftToRead - at).toInt()
                var pcm = reader.read(at, count)
                if (pcm.isEmpty()) break
                at += count
                if (priming > 0L) {
                    val frames = pcm.size / reader.channels
                    val drop = minOf(priming, frames.toLong()).toInt()
                    if (drop > 0) pcm = pcm.copyOfRange(drop * reader.channels, pcm.size)
                    priming -= drop
                }
                var loud = false
                for (v in mono(pcm, reader.channels, step)) {
                    val a = abs(v)
                    if (a > 0.0001f) loud = true
                    if (a > bucket) bucket = a
                    if (++inBucket >= perBucket) {
                        peaks += bucket
                        bucket = 0f
                        inBucket = 0
                    }
                    if (heardAt < heard.size) heard[heardAt++] = v
                }
                // A file that says nothing of its length: the reader pads the end with silence, so four quiet chunks in
                // a row are the end (a quiet passage of music is never 0.7 s dead silent).
                quiet = if (loud) 0 else quiet + 1
                if (said <= 0L && quiet >= 4 && peaks.isNotEmpty()) break
            }
            if (bucket > 0f) peaks += bucket
            if (peaks.isEmpty()) return null
            val lengthMs = if (said > 0L) said else heardAt.toLong() * 1000 / rate
            if (heardAt > 0 && peaks.isEmpty()) return null
            val seen = heard.copyOf(heardAt)
            // The tempo is followed where it drifts, and the same envelope says what kind of piece it is.
            val grid = BeatGrid.detectDrifting(seen, rate)
            return Found(
                peaks = FloatArray(peaks.size) { peaks[it].coerceIn(0f, 1f) },
                beats = if (grid == null) emptyList() else beatsOver(grid, lengthMs),
                bpm = grid?.bpm ?: 0.0,
                confidence = grid?.confidence ?: 0.0,
                lengthMs = lengthMs,
                profile = BeatGrid.profile(seen, rate, grid),
                delayMs = reader.delayFrames * 1000L / reader.rate.coerceAtLeast(1),
            )
        } finally {
            reader.release()
        }
    }

    /**
     * The lane's columns: [buckets] of them, each the loudest of the decoded peaks that fall in its share (a quiet
     * track gets a flat line rather than a divide by zero).
     */
    internal fun columns(peaks: FloatArray, buckets: Int = BUCKETS): FloatArray {
        if (peaks.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = peaks.size.toDouble() / buckets
        for (i in 0 until buckets) {
            val from = (i * per).toInt().coerceIn(0, peaks.size - 1)
            val to = ((i + 1) * per).toInt().coerceIn(from + 1, peaks.size)
            var max = 0f
            for (j in from until to) if (peaks[j] > max) max = peaks[j]
            out[i] = max.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * The wave of the track's share from [fromMs] to [toMs] as [buckets] columns ([peaks]: one per [BUCKET_MS]); past
     * the track's end the columns stay empty (silence). Empty when there is nothing to show.
     */
    internal fun window(peaks: FloatArray, fromMs: Long, toMs: Long, buckets: Int): FloatArray {
        if (peaks.isEmpty() || buckets <= 0 || toMs <= fromMs) return FloatArray(0)
        val from = (fromMs / BUCKET_MS).toInt().coerceAtLeast(0)
        val to = (toMs / BUCKET_MS).toInt().coerceAtLeast(from + 1)
        val share = FloatArray(to - from) { i -> peaks.getOrElse(from + i) { 0f } }
        return columns(share, buckets)
    }

    /**
     * One channel at [RATE]: [step] decoded frames averaged into one, the channels mixed and 16-bit brought to −1…1.
     * Averaging rather than taking every [step]th frame keeps the highs from folding down into the lows — and the
     * onsets the grid is found from are high ones.
     */
    internal fun mono(pcm: ShortArray, channels: Int, step: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = pcm.size / ch
        val n = if (step <= 0) 0 else frames / step
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (f in i * step until (i + 1) * step) {
                var v = 0f
                for (c in 0 until ch) v += pcm[f * ch + c]
                sum += v / ch
            }
            out[i] = sum / step / 32768f
        }
        return out
    }

    /**
     * The grid over the whole track: the beats found in the share that was listened to, then the same period on to the
     * end — the lane draws them all, and a cut may snap to any of them (P3).
     */
    internal fun beatsOver(grid: BeatGrid.Grid, lengthMs: Long): List<Long> {
        val beats = grid.beats
        if (beats.size < 2) return beats
        val period = (beats.last() - beats.first()).toDouble() / (beats.size - 1)
        if (period <= 1.0) return beats
        val out = ArrayList<Long>(beats.size * 2)
        for (b in beats) if (lengthMs <= 0L || b < lengthMs) out += b
        var next = beats.last() + period
        while (lengthMs > 0L && next < lengthMs) {
            out += next.roundToInt().toLong()
            next += period
        }
        return out
    }
}