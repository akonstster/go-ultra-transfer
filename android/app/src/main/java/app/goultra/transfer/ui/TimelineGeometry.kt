package app.goultra.transfer.ui

import app.goultra.transfer.camera.ProjectClip
import app.goultra.transfer.camera.ProjectAudioClip
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The timeline's arithmetic: where a moment of the project is drawn, and which moment is under a point (see
 * `memory-bank/task-timeline.md`, §4). Pure — no Compose, no state — so the test can hold it to its word.
 *
 * The pieces run from [starts] one after another in project ms; the whole project is [totalMs] long. Every place is
 * one sum: the edge, the moment times the zoom, less what the lane is scrolled by.
 */
internal class TimelineGeometry(
    /** The start of each piece in project ms: its own length adds up to the next one's start. */
    val starts: List<Long>,
    /** The project's whole length, ms. */
    val totalMs: Long,
    /** The zoom: how many pixels one ms of the project takes. */
    val pxPerMs: Float,
    /** Room at either end, px: the first and the last moment are not glued to the screen's edges. */
    val edgePx: Float,
    /** The lane's width on screen, px. */
    val widthPx: Float,
) {
    /** How wide the whole content is, edges included. */
    val contentPx: Float get() = 2f * edgePx + totalMs.coerceAtLeast(0L) * pxPerMs

    /** How far the content can be scrolled: nothing at all when it fits the width. */
    val maxScroll: Float get() = (contentPx - widthPx).coerceAtLeast(0f)

    /** Where [ms] of the project stands on screen at [scroll], px from the left edge. */
    fun x(ms: Long, scroll: Float): Float = rawX(ms.coerceIn(0L, totalMs.coerceAtLeast(0L)), scroll)

    fun rawX(ms: Long, scroll: Float): Float = edgePx + ms * pxPerMs - scroll

    /** The project's moment under [x] at [scroll], kept inside the project. */
    fun msAt(x: Float, scroll: Float): Long {
        return rawMsAt(x, scroll).coerceIn(0L, totalMs.coerceAtLeast(0L))
    }

    fun rawMsAt(x: Float, scroll: Float): Long =
        if (pxPerMs <= 0f) 0L else ((x + scroll - edgePx) / pxPerMs).roundToLong()

    /** The piece [ms] falls in (its place in the project's clips): the last one at the very end, -1 when there are none. */
    fun pieceAt(ms: Long): Int {
        if (starts.isEmpty()) return -1
        val at = ms.coerceAtLeast(0L)
        val found = starts.indexOfLast { it <= at }
        return if (found < 0) 0 else found
    }

    /** The scroll that puts [ms] at [atX] on screen, kept inside what can be scrolled. */
    fun scrollToKeep(ms: Long, atX: Float): Float =
        (edgePx + ms.coerceIn(0L, totalMs.coerceAtLeast(0L)) * pxPerMs - atX).coerceIn(0f, maxScroll)

    /**
     * The same timeline at another zoom, with the moment under [focusX] still under it: the geometry to draw with, and
     * the scroll that goes with it.
     */
    fun zoomedAbout(newPxPerMs: Float, focusX: Float, scroll: Float): Pair<TimelineGeometry, Float> {
        val next = TimelineGeometry(starts, totalMs, newPxPerMs.coerceAtLeast(0f), edgePx, widthPx)
        val under = msAt(focusX, scroll)
        return next to next.scrollToKeep(under, focusX)
    }
}

/** The zoom at which the whole project stands on the width: the timeline's default. */
internal fun fitZoom(totalMs: Long, widthPx: Float, edgePx: Float): Float {
    val room = (widthPx - 2f * edgePx).coerceAtLeast(1f)
    return if (totalMs <= 0L) 0f else room / totalMs
}

/** Each piece's start in project ms, in the project's own order. */
internal fun startsOf(clips: List<ProjectClip>): List<Long> {
    var at = 0L
    return clips.map { c -> at.also { at += c.durationMs } }
}

internal enum class AudioEdit { MOVE, START, END }

internal fun endTrimScrollLimit(geometry: TimelineGeometry, clip: ProjectAudioClip): Float =
    (2 * geometry.edgePx + maxOf(geometry.totalMs, clip.projectAt(clip.source.lengthMs)) * geometry.pxPerMs -
        geometry.widthPx).coerceAtLeast(0f)

internal fun editAudio(clip: ProjectAudioClip, edit: AudioEdit, deltaMs: Long): ProjectAudioClip {
    if (deltaMs == 0L || clip.durationMs <= 0L) return clip
    val minimum = minOf(25L, clip.durationMs)
    return when (edit) {
        AudioEdit.MOVE -> clip.copy(startMs = (clip.startMs + deltaMs).coerceAtLeast(0L))
        AudioEdit.START -> {
            val from = (clip.fromMs + deltaMs).coerceIn(maxOf(0L, clip.fromMs - clip.startMs), clip.sourceEndMs - minimum)
            clip.copy(startMs = clip.startMs + from - clip.fromMs, fromMs = from)
        }
        AudioEdit.END -> {
            val end = (clip.sourceEndMs + deltaMs).coerceIn(clip.fromMs + minimum, clip.source.lengthMs)
            if (end == clip.sourceEndMs) clip else clip.copy(toMs = end.takeUnless { it == clip.source.lengthMs })
        }
    }
}

internal fun typicalBeatSpacing(beats: List<Long>): Long {
    val gaps = beats.zipWithNext { a, b -> b - a }.filter { it in 100L..2_000L }.sorted()
    return gaps.getOrNull(gaps.size / 2) ?: 500L
}

internal data class AudioBeatSnap(val clip: ProjectAudioClip, val timeMs: Long)

/** Compare the moving beat nearest the grip in each overlap. The raw finger position is used on every move. */
internal fun snapAudioMove(
    moving: ProjectAudioClip,
    others: List<ProjectAudioClip>,
    gripSourceMs: Long,
    toleranceMs: Long,
): AudioBeatSnap? {
    if (toleranceMs <= 0L || moving.source.beats.isEmpty()) return null
    var closest: AudioBeatSnap? = null
    var best = toleranceMs + 1L
    for (other in others) {
        if (other.id == moving.id) continue
        val from = maxOf(moving.startMs, other.startMs)
        val end = minOf(moving.endMs, other.endMs)
        if (from >= end) continue
        val nearGrip = nearestBeat(moving, from, end, moving.projectAt(gripSourceMs)) ?: continue
        val target = nearestBeat(other, from, end, nearGrip) ?: continue
        val delta = target - nearGrip
        if (abs(delta) >= best || moving.startMs + delta < 0L) continue
        best = abs(delta)
        closest = AudioBeatSnap(moving.copy(startMs = moving.startMs + delta), target)
    }
    return closest
}

private fun nearestBeat(clip: ProjectAudioClip, from: Long, end: Long, target: Long): Long? {
    val marks = clip.source.beats
    fun indexOf(at: Long): Int {
        val i = marks.binarySearch(clip.sourceAt(at))
        return if (i >= 0) i else -i - 1
    }
    val first = indexOf(from)
    val last = indexOf(end) - 1
    if (first > last) return null
    val right = indexOf(target).coerceIn(first, last)
    val a = clip.projectAt(marks[right])
    val b = clip.projectAt(marks[(right - 1).coerceAtLeast(first)])
    return if (abs(a - target) < abs(b - target)) a else b
}
