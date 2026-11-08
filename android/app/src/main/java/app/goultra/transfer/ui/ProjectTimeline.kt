package app.goultra.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip
import app.goultra.transfer.camera.ProjectMusic
import app.goultra.transfer.camera.TrackAudio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The project as a film strip in time (see `memory-bank/task-timeline.md`): the pieces as wide as they last, the track
 * under them in the same scale with its grid running through both, and one line for the clock. The order is not made
 * here — the cards above do that; this is the same project looked at by its length.
 *
 * It is drawn by hand, and every place is read where it is drawn: a scrolling lane must not recompose the screen. The
 * zoom is the lane's own ([TimelineGeometry]), the scroll is a plain number the `scrollable` moves, and the two fingers
 * are watched in a gesture of our own — a pinch that took a single finger would leave nothing to scroll with.
 */
@Composable
internal fun ProjectTimeline(
    vm: CameraController,
    p: Project,
    clockMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
) {
    if (p.clips.isEmpty()) return
    val density = LocalDensity.current
    val edge = with(density) { TIMELINE_EDGE.toPx() }
    val joint = with(density) { TIMELINE_JOINT.toPx() }
    val videoH = with(density) { TIMELINE_VIDEO_H.toPx() }
    val musicH = with(density) { TIMELINE_MUSIC_H.toPx() }

    val starts = remember(p.clips) { startsOf(p.clips) }
    val totalMs = remember(p.clips) { p.clips.sumOf { it.durationMs } }
    val music = p.music
    val peaks = remember(music?.uri, vm.musicVersion) { music?.let { vm.musicPeaks(it.uri) } }

    var width by remember(p.id) { mutableFloatStateOf(0f) }
    // The zoom as a multiple of "the whole project on the width": 1 is the default, and it follows the width and the
    // project's own length without extra bookkeeping.
    var zoomMul by remember(p.id) { mutableFloatStateOf(1f) }
    var scroll by remember(p.id) { mutableFloatStateOf(0f) }
    var touchedAt by remember(p.id) { mutableLongStateOf(0L) }
    // The music under the finger: the shift is shown at once, written into the project only when the finger is let go.
    var shifting by remember(p.id) { mutableStateOf(false) }
    var shiftPx by remember(p.id) { mutableFloatStateOf(0f) }

    // The gesture and draw lambdas outlive a composition: they read what stands now, never a list captured once.
    val startsNow by rememberUpdatedState(starts)
    val totalNow by rememberUpdatedState(totalMs)
    val musicNow by rememberUpdatedState(music)
    val seekNow by rememberUpdatedState(onSeek)

    fun zoomNow(): Float = (fitZoom(totalNow, width, edge) * zoomMul).coerceIn(0f, TIMELINE_MAX_PX_PER_MS)
    fun geomNow(): TimelineGeometry = TimelineGeometry(startsNow, totalNow, zoomNow(), edge, width)
    fun maxOffsetNow(): Long = (musicNow?.lengthMs?.minus(TIMELINE_OFFSET_TAIL_MS) ?: 0L).coerceAtLeast(0L)
    // The other way: the track may start after the picture (silence first), but never so late that less than a second
    // of the film is left for it.
    fun minOffsetNow(): Long = -(totalNow - TIMELINE_OFFSET_TAIL_MS).coerceAtLeast(0L)
    fun shownOffset(): Long {
        val track = musicNow ?: return 0L
        return if (shifting) draggedOffset(track.offsetMs, shiftPx, zoomNow(), maxOffsetNow(), minOffsetNow())
        else track.offsetMs
    }

    val scrollState = rememberScrollableState { delta ->
        // As the card strip has it: the page moves against the finger, and what is reported back is what was used.
        val old = scroll
        scroll = (scroll - delta).coerceIn(0f, geomNow().maxScroll)
        touchedAt = System.currentTimeMillis()
        old - scroll
    }
    // A zoom, a shorter project or another screen width may leave the lane scrolled past its end.
    LaunchedEffect(zoomMul, width, totalMs) { scroll = scroll.coerceIn(0f, geomNow().maxScroll) }

    // The pieces' frames: the same ones their cards show, kept on the phone, so the lane is drawn with the camera away.
    val frames = remember(p.id) { mutableStateMapOf<String, ImageBitmap>() }
    val offline = remember(p.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(p.id, p.clips, vm.conn) {
        for (c in p.clips) {
            val file = vm.projectFile(c.file)
            offline[c.file] = file == null
            val key = frameKey(c)
            if (frames.containsKey(key)) continue
            val image = if (c.role == ProjectClip.Role.STILL) vm.thumbnail(file ?: vm.offlineFile(c.file))
            else vm.timelineFrame(file ?: vm.offlineFile(c.file), c.fromMs, keep = true)
            if (image != null) frames[key] = image
        }
    }

    // While the clock runs and nobody is touching the lane, it follows the line and keeps it a third from the left.
    LaunchedEffect(playing, clockMs) {
        if (!playing || width <= 0f) return@LaunchedEffect
        if (System.currentTimeMillis() - touchedAt < TIMELINE_FOLLOW_PAUSE_MS) return@LaunchedEffect
        val target = geomNow().scrollToKeep(clockMs, width * TIMELINE_FOLLOW_SHARE)
        if (abs(target - scroll) > 1f) scroll = target
    }
    // Two fingers zoom about the point between them; one finger is left to the scroll (a pinch that took a single
    // finger would leave nothing to scroll at all).
    val pinch = Modifier.pointerInput(p.id, p.clips) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } < 2) break
                val zoomChange = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (!zoomChange.isFinite() || zoomChange <= 0f || !centroid.x.isFinite()) continue
                val fitNow = fitZoom(totalNow, width, edge)
                if (fitNow <= 0f || fitNow >= TIMELINE_MAX_PX_PER_MS) continue
                val wanted = (zoomNow() * zoomChange).coerceIn(fitNow, TIMELINE_MAX_PX_PER_MS)
                val (zoomed, at) = geomNow().zoomedAbout(wanted, centroid.x, scroll)
                zoomMul = (wanted / fitNow).coerceAtLeast(1f)
                scroll = at.coerceIn(0f, zoomed.maxScroll)
                touchedAt = System.currentTimeMillis()
                event.changes.forEach { it.consume() }
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(TIMELINE_VIDEO_H + TIMELINE_MUSIC_H)
            .clipToBounds()
            .onSizeChanged { width = it.width.toFloat() }
            .scrollable(scrollState, Orientation.Horizontal)
            .then(pinch),
    ) {
        Lane(
            clips = p.clips,
            starts = starts,
            frames = frames,
            offline = offline,
            music = music,
            peaks = peaks,
            clockMs = clockMs,
            videoH = videoH,
            musicH = musicH,
            joint = joint,
            geometry = ::geomNow,
            offset = ::shownOffset,
            scroll = { scroll },
        )
        // A tap on the picture is a jump in the film. Not on the music track: a press there moves the track itself.
        Box(
            Modifier.fillMaxWidth().height(TIMELINE_VIDEO_H).align(Alignment.TopCenter)
                .pointerInput(p.id, p.clips) { detectTapGestures { at -> seekNow(geomNow().msAt(at.x, scroll)) } },
        )
        if (music != null) {
            val haptic = LocalHapticFeedback.current
            Box(
                Modifier.fillMaxWidth().height(TIMELINE_MUSIC_H).align(Alignment.BottomCenter)
                    .pointerInput(p.id, music.uri) {
                        // A long press takes the track, as it takes a card: a plain swipe must still scroll the lane.
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                shifting = true
                                shiftPx = 0f
                            },
                            onDragEnd = {
                                val offset = draggedOffset(
                                    musicNow?.offsetMs ?: 0L, shiftPx, zoomNow(), maxOffsetNow(), minOffsetNow(),
                                )
                                shifting = false
                                shiftPx = 0f
                                // One write for the whole gesture, never one per pixel.
                                vm.updateProjectMusic { m -> m.copy(offsetMs = offset) }
                            },
                            onDragCancel = {
                                shifting = false
                                shiftPx = 0f
                            },
                        ) { change, drag ->
                            change.consume()
                            shiftPx += drag.x
                            touchedAt = System.currentTimeMillis()
                        }
                    },
            )
        }
    }
}
/**
 * The two tracks in one pass: the pieces at their own lengths with their frames, the track's wave under them, the beat
 * grid over both, and the clock's line. Every place is read inside the draw lambda — the scroll, the zoom and the
 * frames as they are at that moment — so scrolling, zooming and playing never recompose the screen.
 */
@Composable
private fun Lane(
    clips: List<ProjectClip>,
    starts: List<Long>,
    frames: Map<String, ImageBitmap>,
    offline: Map<String, Boolean>,
    music: ProjectMusic?,
    peaks: FloatArray?,
    clockMs: Long,
    videoH: Float,
    musicH: Float,
    joint: Float,
    geometry: () -> TimelineGeometry,
    offset: () -> Long,
    scroll: () -> Float,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val gap = MaterialTheme.colorScheme.surface
    val wave = MaterialTheme.colorScheme.primary
    // The grid is red on purpose: over the frames and the wave it must be the one thing seen at a glance.
    val tick = Color(0xFFE53935).copy(alpha = 0.75f)
    val bar = Color(0xFFD50000)
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val offlineDim = Color(0x66000000)
    val playingEdge = Color(0xFFFFC107)
    val thin = with(LocalDensity.current) { 2.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        val g = geometry()
        val at = scroll()
        val musicTop = videoH
        drawRect(background, Offset(0f, 0f), Size(size.width, videoH))
        if (music != null) drawRect(background, Offset(0f, musicTop), Size(size.width, musicH))
        val playing = g.pieceAt(clockMs)
        clips.forEachIndexed { i, c ->
            val left = g.x(starts.getOrElse(i) { 0L }, at)
            val w = c.durationMs * g.pxPerMs
            if (w <= 0f || left + w < 0f || left > size.width) return@forEachIndexed
            val image = frames[frameKey(c)]
            if (image != null) frame(image, left, 0f, w, videoH) else drawRect(placeholder, Offset(left, 0f), Size(w, videoH))
            // A piece whose file is out of reach is dimmed, as on its card: the frame is a stand-in, not the film.
            if (offline[c.file] == true) drawRect(offlineDim, Offset(left, 0f), Size(w, videoH))
            if (i > 0) drawRect(gap, Offset(left - joint / 2f, 0f), Size(joint, videoH))
            if (i == playing) {
                drawRect(playingEdge, Offset(left, 0f), Size(w, videoH), style = Stroke(width = thin))
            }
        }
        if (music != null) {
            val trackOffset = offset()
            if (peaks != null && peaks.isNotEmpty() && g.pxPerMs > 0f) {
                // The visible share of the project in the track's own time: a track moment t stands at the project's
                // t - offset, so a track that begins after the picture leaves the head of the lane empty.
                val trackFrom = maxOf(g.msAt(0f, at) + trackOffset, 0L)
                val trackTo = g.msAt(size.width, at) + trackOffset
                if (trackTo > trackFrom) {
                    val columns = TrackAudio.window(peaks, trackFrom, trackTo, (size.width / 3f).toInt().coerceAtLeast(1))
                    if (columns.isNotEmpty()) {
                        val perMs = (trackTo - trackFrom).toDouble() / columns.size
                        val middle = musicTop + musicH / 2f
                        val step = (perMs * g.pxPerMs).toFloat()
                        columns.forEachIndexed { i, v ->
                            val x = g.x((trackFrom + (i + 0.5) * perMs).roundToLong() - trackOffset, at)
                            if (x < -2f || x > size.width + 2f) return@forEachIndexed
                            val h = (v.coerceIn(0f, 1f) * (musicH / 2f - 1f)).coerceAtLeast(0.5f)
                            drawLine(wave, Offset(x, middle - h), Offset(x, middle + h), strokeWidth = max(1f, step * 0.6f))
                        }
                    }
                }
            }
            music.beats.forEachIndexed { n, beat ->
                val projectMs = beat - trackOffset
                if (projectMs < 0L || projectMs > g.totalMs) return@forEachIndexed
                val x = g.x(projectMs, at)
                if (x < -2f || x > size.width + 2f) return@forEachIndexed
                val strong = n % TIMELINE_BAR_EVERY == 0
                val stroke = if (strong) 2.5f else 1.5f
                val colour = if (strong) bar else tick
                // Faintly through the picture too: a joint that lands on a beat is seen at once.
                drawLine(colour.copy(alpha = colour.alpha * 0.45f), Offset(x, 0f), Offset(x, videoH), strokeWidth = stroke)
                drawLine(colour, Offset(x, musicTop), Offset(x, musicTop + musicH), strokeWidth = stroke)
            }
        }
        val now = g.x(clockMs, at)
        if (now in -1f..(size.width + 1f)) {
            drawLine(line, Offset(now, 0f), Offset(now, musicTop + musicH), strokeWidth = thin)
        }
    }
}

/** A piece's frame in the map: the file, the moment and what it is (a photo's picture is not a video's frame). */
private fun frameKey(c: ProjectClip): String = "${c.file}@${c.fromMs}:${c.role}"

/** [image] across the rectangle at [left], cropped to fill it: the middle of the picture is what stays. */
private fun DrawScope.frame(image: ImageBitmap, left: Float, top: Float, width: Float, height: Float) {
    if (width <= 0f || height <= 0f || image.width <= 0 || image.height <= 0) return
    val dstW = width.roundToInt().coerceAtLeast(1)
    val dstH = height.roundToInt().coerceAtLeast(1)
    val aspect = dstW.toFloat() / dstH
    var srcW = image.width
    var srcH = image.height
    var srcX = 0
    var srcY = 0
    if (srcW.toFloat() / srcH > aspect) {
        srcW = (srcH * aspect).roundToInt().coerceAtLeast(1)
        srcX = (image.width - srcW) / 2
    } else {
        srcH = (srcW / aspect).roundToInt().coerceAtLeast(1)
        srcY = (image.height - srcH) / 2
    }
    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(dstW, dstH),
        filterQuality = FilterQuality.Low,
    )
}