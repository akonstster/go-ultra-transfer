package app.goultra.transfer.ui

import app.goultra.transfer.camera.ProjectClip
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
    fun x(ms: Long, scroll: Float): Float = edgePx + ms.coerceIn(0L, totalMs.coerceAtLeast(0L)) * pxPerMs - scroll

    /** The project's moment under [x] at [scroll], kept inside the project. */
    fun msAt(x: Float, scroll: Float): Long {
        if (pxPerMs <= 0f) return 0L
        val ms = ((x + scroll - edgePx) / pxPerMs).roundToLong()
        return ms.coerceIn(0L, totalMs.coerceAtLeast(0L))
    }

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

/**
 * The music's offset after a drag of [dxPx] at [pxPerMs]: the finger to the right brings the track later than the
 * picture (its start moves right, silence before it — that is a negative offset), to the left — earlier in the track
 * (the head is cut off); kept inside [minOffsetMs]…[maxOffsetMs].
 */
internal fun draggedOffset(offsetMs: Long, dxPx: Float, pxPerMs: Float, maxOffsetMs: Long, minOffsetMs: Long = 0L): Long {
    val high = maxOffsetMs.coerceAtLeast(0L)
    val low = minOffsetMs.coerceAtMost(0L).coerceAtMost(high)
    if (pxPerMs <= 0f) return offsetMs.coerceIn(low, high)
    return (offsetMs - (dxPx / pxPerMs).roundToLong()).coerceIn(low, high)
}