package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AudioRenderClip(val uri: String, val start: Long, val from: Long, val end: Long, val gain: Double)

internal fun audioRenderClips(clips: List<ProjectAudioClip>): List<AudioRenderClip> =
    clips.map { AudioRenderClip(it.source.uri, it.startMs, it.fromMs, it.sourceEndMs, it.volume) }

/** The same envelopes determine the audible mix and which track owns the beat during a transition. */
internal class AudioLane(clips: List<ProjectAudioClip>) {
    val clips = clips.filter { it.durationMs > 0L }.sortedBy { it.startMs }
    private val overlaps = this.clips.mapIndexed { index, left ->
        (index + 1 until this.clips.size).takeWhile { this.clips[it].startMs < left.endMs }.map { rightIndex ->
            val right = this.clips[rightIndex]
            Transition(index, rightIndex, right.startMs, minOf(left.endMs, right.endMs), right.endMs < left.endMs)
        }
    }
    private val regionForClip = buildList {
        var region: Region? = null
        for (clip in this@AudioLane.clips) {
            val current = region?.takeIf { clip.startMs < it.end } ?: Region(clip.startMs, clip.endMs).also { region = it }
            current.end = maxOf(current.end, clip.endMs)
            add(current)
        }
    }

    private data class Transition(val left: Int, val right: Int, val start: Long, val end: Long, val returnToLeft: Boolean)
    private data class Region(val start: Long, var end: Long)

    fun gains(atMs: Double, active: IntArray, out: FloatArray) {
        for (index in active) out[index] = 1f
        if (active.size > 1) for (index in active) {
            for (transition in overlaps[index]) {
                if (atMs < transition.start) break
                if (atMs >= transition.end) continue
                val progress = (atMs - transition.start) / (transition.end - transition.start)
                val angle = PI / 2 * if (transition.returnToLeft) {
                    1.0 - kotlin.math.abs(2 * progress - 1)
                } else progress
                out[transition.left] *= cos(angle).toFloat()
                out[transition.right] *= sin(angle).toFloat()
            }
        }
        val norm = if (active.size > 2) {
            sqrt(active.sumOf { index -> (out[index] * out[index]).toDouble() }).takeIf { it > 0.0 } ?: 1.0
        } else 1.0
        for (index in active) {
            val region = regionForClip[index]
            val fade = minOf(60.0, (region.end - region.start) / 2.0)
            val edge = minOf(atMs - region.start, region.end - atMs)
            out[index] = (out[index] / norm * (edge / fade).coerceIn(0.0, 1.0) * clips[index].volume).toFloat()
        }
    }

    fun active(atMs: Double): IntArray =
        clips.indices.filter { atMs >= clips[it].startMs && atMs < clips[it].endMs }.toIntArray()

    private fun owner(atMs: Long, out: FloatArray): Int? {
        val active = active(atMs.toDouble())
        gains(atMs.toDouble(), active, out)
        return active.asIterable().filter { clips[it].volume > 0.0 }.maxByOrNull { out[it] }
    }

    fun clipAt(atMs: Long): ProjectAudioClip? {
        val active = active(atMs.toDouble())
        if (active.isEmpty()) return clips.lastOrNull { it.endMs == atMs }
        val levels = FloatArray(clips.size)
        gains(atMs.toDouble(), active, levels)
        return clips[active.maxBy { levels[it] }]
    }

    fun marks(sourceMarks: (ProjectMusic) -> List<Long>): List<Long> {
        val gains = FloatArray(clips.size)
        return clips.flatMapIndexed { index, clip ->
            sourceMarks(clip.source).map(clip::projectAt).filter { clip.contains(it) && owner(it, gains) == index }
        }.distinct().sorted()
    }

    fun energy(peaks: (String) -> FloatArray?): List<Float> {
        val sourcePeaks = clips.map { peaks(it.source.uri) }
        val gains = FloatArray(clips.size)
        val end = clips.maxOfOrNull { it.endMs } ?: 0L
        return (0L until end step 1_000L).map { start ->
            var sum = 0.0
            for (time in start until minOf(start + 1_000L, end) step TrackAudio.BUCKET_MS.toLong()) {
                val active = active(time.toDouble())
                gains(time.toDouble(), active, gains)
                sum += active.sumOf { index ->
                    val sample = sourcePeaks[index]?.getOrNull((clips[index].sourceAt(time) / TrackAudio.BUCKET_MS).toInt()) ?: 0f
                    (sample * gains[index]).let { (it * it).toDouble() }
                }
            }
            sqrt(sum / (1_000.0 / TrackAudio.BUCKET_MS)).toFloat()
        }
    }
}
