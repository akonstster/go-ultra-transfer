package app.goultra.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.Multicam
import app.goultra.transfer.camera.MulticamTake
import app.goultra.transfer.camera.TrackAudio
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private data class AudioRow(val clip: ProjectAudioClip, val row: Int)
private data class EndTrimPan(val clip: ProjectAudioClip, val downX: Float, val scroll: Float, val x: Float)

private fun audioRows(clips: List<ProjectAudioClip>): List<AudioRow> {
    val ends = mutableListOf<Long>()
    return clips.sortedBy { it.startMs }.map { clip ->
        val row = ends.indexOfFirst { it <= clip.startMs }
        if (row < 0) {
            ends.add(clip.endMs)
            AudioRow(clip, ends.lastIndex)
        } else {
            ends[row] = clip.endMs
            AudioRow(clip, row)
        }
    }
}

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
    musicClock: MusicClock,
    audioBeats: List<Long>,
    selectedDrop: MutableState<DropSelection?>,
    editorClipId: MutableState<String?>,
    showAngles: Boolean,
    audition: String?,
    shotHighlight: MulticamHighlight?,
    onShotSelected: (Long) -> Unit,
    onResult: () -> Unit,
    onInspect: (MulticamTake, Long?) -> Unit,
    onSeek: (Long, String?) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val edge = with(density) { TIMELINE_EDGE.toPx() }
    val joint = with(density) { TIMELINE_JOINT.toPx() }
    val videoHeight = if (p.clips.isEmpty()) 0.dp else TIMELINE_VIDEO_H
    val videoH = with(density) { videoHeight.toPx() }
    val musicH = with(density) { TIMELINE_MUSIC_H.toPx() }
    val trimWidth = with(density) { 16.dp.toPx() }
    val snapWidth = with(density) { 6.dp.toPx() }
    val beatSpacing = with(density) { 8.dp.toPx() }
    val trimEdge = with(density) { 32.dp.toPx() }
    val trimSpeed = with(density) { 420.dp.toPx() }
    val dropHit = with(density) { 11.dp.toPx() }
    val dropHead = with(density) { 15.dp.toPx() }

    val starts = remember(p.clips) { startsOf(p.clips) }
    val totalMs = maxOf(p.clips.sumOf { it.durationMs }, p.audioEndMs)
    val activeAudioId = vm.activeAudioClip(p)?.id
    val audio = remember(p.audioClips) { audioRows(p.audioClips.filter { it.durationMs > 0L }) }
    val periods = remember(audio) { audio.associate { it.clip.id to typicalBeatSpacing(it.clip.source.beats) } }
    val uris = p.audioClips.map { it.source.uri }.distinct()
    val peaks = remember(uris, vm.musicVersion) { uris.associateWith(vm::musicPeaks) }

    var width by remember(p.id) { mutableFloatStateOf(0f) }
    // The zoom as a multiple of "the whole project on the width": 1 is the default, and it follows the width and the
    // project's own length without extra bookkeeping.
    var zoomMul by remember(p.id) { mutableFloatStateOf(1f) }
    var scroll by remember(p.id) { mutableFloatStateOf(0f) }
    var touchedAt by remember(p.id) { mutableLongStateOf(0L) }
    var zoomClock by remember(p.id) { mutableStateOf<Long?>(null) }
    var pendingAudio by remember(p.id) { mutableStateOf<ProjectAudioClip?>(null) }
    var pendingBeat by remember(p.id) { mutableStateOf<Long?>(null) }
    var trimPan by remember(p.id) { mutableStateOf<EndTrimPan?>(null) }
    var dropSelection by selectedDrop
    var dropEditorClipId by editorClipId
    var draggedFrom by remember(p.id) { mutableStateOf<DropSelection?>(null) }
    var draggedDrop by remember(p.id) { mutableStateOf<DropSelection?>(null) }
    var draggingAngle by remember(p.id) { mutableStateOf(false) }
    var groupDrag by remember(p.id) { mutableStateOf<Float?>(null) }

    // The gesture and draw lambdas outlive a composition: they read what stands now, never a list captured once.
    val startsNow by rememberUpdatedState(starts)
    val totalNow by rememberUpdatedState(totalMs)
    val audioNow by rememberUpdatedState(audio)
    val periodsNow by rememberUpdatedState(periods)
    val selectedNow by rememberUpdatedState(activeAudioId)
    val seekNow by rememberUpdatedState(onSeek)
    val resultNow by rememberUpdatedState(onResult)
    val selectShotNow by rememberUpdatedState(onShotSelected)
    val auditionNow by rememberUpdatedState(audition)
    val playingNow by rememberUpdatedState(playing)
    val clockNow by rememberUpdatedState(clockMs)
    var drawMusicMs by remember(p.id) { mutableStateOf<Long?>(null) }

    fun zoomNow(): Float = (fitZoom(totalNow, width, edge) * zoomMul).coerceIn(0f, TIMELINE_MAX_PX_PER_MS)
    fun geomNow(): TimelineGeometry = TimelineGeometry(startsNow, totalNow, zoomNow(), edge, width)
    fun jumpDrop(clip: ProjectAudioClip, at: Long) {
        dropEditorClipId = clip.id
        dropSelection = DropSelection(clip.id, at)
        seekNow(clip.projectAt(at), clip.id)
    }
    fun moveDrop(clip: ProjectAudioClip, from: Long, wanted: Long): Boolean {
        if (clip.durationMs <= 0L) return false
        val at = wanted.coerceIn(clip.fromMs, clip.sourceEndMs - 1)
        if (at != from && !vm.editProjectAudioSource(clip.id, Phrase("Перемещён дроп", "Moved drop")) { it.movedDrop(from, at) }) return false
        jumpDrop(clip, at)
        return true
    }
    val scrollState = rememberScrollableState { delta ->
        if (zoomClock != null || pendingAudio != null || draggedDrop != null || groupDrag != null) return@rememberScrollableState 0f
        // As the card strip has it: the page moves against the finger, and what is reported back is what was used.
        val old = scroll
        scroll = (scroll - delta).coerceIn(0f, geomNow().maxScroll)
        touchedAt = System.currentTimeMillis()
        old - scroll
    }
    // A zoom, a shorter project or another screen width may leave the lane scrolled past its end.
    LaunchedEffect(zoomMul, width, totalMs) { scroll = scroll.coerceIn(0f, geomNow().maxScroll) }
    LaunchedEffect(trimPan?.clip?.id) {
        if (trimPan == null) return@LaunchedEffect
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frame ->
                val seconds = if (previousFrame == 0L) 0f else ((frame - previousFrame) / 1e9f).coerceIn(0f, 0.05f)
                previousFrame = frame
                val pan = trimPan ?: return@withFrameNanos
                val g = geomNow()
                if (g.pxPerMs <= 0f || zoomClock != null) return@withFrameNanos
                val edgeSize = minOf(trimEdge, width / 3f)
                if (edgeSize <= 0f) return@withFrameNanos
                val push = when {
                    pan.x > width - edgeSize -> (pan.x - (width - edgeSize)) / edgeSize
                    pan.x < edgeSize -> (pan.x - edgeSize) / edgeSize
                    else -> 0f
                }.coerceIn(-1f, 1f)
                if (push == 0f) return@withFrameNanos
                val next = (scroll + push * trimSpeed * seconds).coerceIn(0f, endTrimScrollLimit(g, pan.clip))
                if (next != scroll) {
                    scroll = next
                    val delta = ((pan.x - pan.downX + next - pan.scroll) / g.pxPerMs).roundToLong()
                    pendingAudio = editAudio(pan.clip, AudioEdit.END, delta)
                    touchedAt = System.currentTimeMillis()
                }
            }
        }
    }
    LaunchedEffect(clockMs, playing) {
        if (!playing && width > 0f && zoomClock == null && pendingAudio == null && draggedDrop == null) {
            val geometry = geomNow()
            if (geometry.x(clockMs, scroll) !in 0f..width) {
                scroll = geometry.scrollToKeep(clockMs, width * TIMELINE_FOLLOW_SHARE)
            }
        }
    }

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

    // Scroll and cursor must use the same sample. Following a coarse tick while animating the line between ticks
    // makes the line advance across the lane and then snap back every tick, especially at high zoom.
    LaunchedEffect(playing, musicClock) {
        drawMusicMs = null
        if (!playing) return@LaunchedEffect
        var cursor: MusicClock.Cursor? = null
        while (true) {
            withFrameNanos {
                val sample = musicClock.cursorAt(android.os.SystemClock.elapsedRealtime())
                cursor = sample?.after(cursor)
                drawMusicMs = cursor?.positionMs
                if (width > 0f && zoomClock == null && pendingAudio == null && draggedDrop == null && !draggingAngle &&
                    System.currentTimeMillis() - touchedAt >= TIMELINE_FOLLOW_PAUSE_MS) {
                    val target = geomNow().scrollToKeep(drawMusicMs ?: clockNow, width * TIMELINE_FOLLOW_SHARE)
                    val distance = target - scroll
                    scroll = if (abs(distance) <= 2f) target else scroll + distance * 0.30f
                }
            }
        }
    }
    // Once a pinch starts, keep it exclusive until both fingers are up. The visible playhead is the zoom anchor.
    val pinch = Modifier.pointerInput(p.id, p.clips) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2 && zoomClock == null) zoomClock = drawMusicMs ?: clockNow
                    val anchorMs = zoomClock ?: continue
                    event.changes.forEach { it.consume() }
                    val zoomChange = event.calculateZoom()
                    val fitNow = fitZoom(totalNow, width, edge)
                    if (zoomChange == 1f || fitNow <= 0f || fitNow >= TIMELINE_MAX_PX_PER_MS) continue
                    val geometry = geomNow()
                    val x = geometry.x(anchorMs, scroll)
                    val anchorX = if (x in 0f..width) x else event.calculateCentroid().x
                    if (!zoomChange.isFinite() || zoomChange <= 0f || !anchorX.isFinite()) continue
                    val wanted = (zoomNow() * zoomChange).coerceIn(fitNow, TIMELINE_MAX_PX_PER_MS)
                    val (_, at) = geometry.zoomedAbout(wanted, anchorX, scroll)
                    zoomMul = (wanted / fitNow).coerceAtLeast(1f)
                    scroll = at
                } while (event.changes.any { it.pressed })
            } finally {
                if (zoomClock != null) touchedAt = System.currentTimeMillis()
                zoomClock = null
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(videoHeight + if (audio.isNotEmpty()) TIMELINE_MUSIC_H else 0.dp)
            .clipToBounds()
            .onSizeChanged { width = it.width.toFloat() }
            .then(pinch)
            .scrollable(scrollState, Orientation.Horizontal),
    ) {
        Lane(
            clips = p.clips,
            starts = starts,
            frames = frames,
            offline = offline,
            audio = audio,
            activeAudioId = activeAudioId,
            pendingAudio = { pendingAudio },
            pendingBeat = { pendingBeat },
            dropSelection = { draggedDrop ?: dropSelection },
            draggedFrom = { draggedFrom },
            audioBeats = audioBeats,
            periods = periods,
            peaks = peaks,
            clockMs = zoomClock ?: clockMs,
            playhead = { zoomClock ?: drawMusicMs ?: clockMs },
            shotHighlight = shotHighlight,
            videoH = videoH,
            musicH = musicH,
            joint = joint,
            geometry = ::geomNow,
            scroll = { scroll },
        )
        // Tap either lane to move the playhead. In the audio lane a tap also selects the clip under the finger.
        if (p.clips.isNotEmpty()) Box(
            Modifier.fillMaxWidth().height(TIMELINE_VIDEO_H).align(Alignment.TopCenter)
                .pointerInput(p.id, p.clips) {
                    detectTapGestures { at ->
                        dropSelection = null
                        dropEditorClipId = null
                        resultNow()
                        val time = geomNow().msAt(at.x, scroll)
                        selectShotNow(time)
                        if (auditionNow == null || !playingNow) seekNow(time, null)
                    }
                }
                .then(if (p.clips.none { it.file == Multicam.GROUP_FILE }) Modifier else Modifier.pointerInput(p.clips) {
                    val master = p.multicamMaster ?: return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            if (master.contains(geomNow().msAt(at.x, scroll))) groupDrag = at.x
                        },
                        onDragEnd = {
                            groupDrag?.takeIf { zoomClock == null }?.let { vm.moveMulticamTo(geomNow().msAt(it, scroll)) }
                            groupDrag = null
                        },
                        onDragCancel = { groupDrag = null },
                        onDrag = { change, delta ->
                            if (zoomClock == null && groupDrag != null) {
                                change.consume()
                                groupDrag = groupDrag?.plus(delta.x)
                            }
                        },
                    )
                }),
        )
        if (groupDrag != null) {
            val ink = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(videoHeight)) {
                val x = checkNotNull(groupDrag).coerceIn(0f, size.width)
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
            }
        }
        if (audio.isNotEmpty()) {
            val haptic = LocalHapticFeedback.current
            Box(
                Modifier.fillMaxWidth().height(TIMELINE_MUSIC_H).align(Alignment.BottomCenter)
                    .pointerInput(p.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val geometry = geomNow()
                            val rowCount = (audioNow.maxOfOrNull { it.row } ?: 0) + 1
                            val row = (down.position.y / (size.height.toFloat() / rowCount)).toInt().coerceIn(0, rowCount - 1)
                            val clip = audioNow.firstOrNull { item ->
                                item.row == row && down.position.x in
                                    geometry.x(item.clip.startMs, scroll)..geometry.x(item.clip.endMs, scroll)
                            }?.clip
                            if (clip != null && down.position.y - row * size.height.toFloat() / rowCount <= dropHead) {
                                val mark = clip.visibleDrops().minByOrNull {
                                    abs(geometry.rawX(clip.projectAt(it), scroll) - down.position.x)
                                }?.takeIf { abs(geometry.rawX(clip.projectAt(it), scroll) - down.position.x) <= dropHit }
                                if (mark != null) {
                                    val original = DropSelection(clip.id, mark)
                                    if (dropEditorClipId != clip.id || dropSelection != original) {
                                        while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (change.isConsumed || zoomClock != null ||
                                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                            ) break
                                            if (!change.pressed) {
                                                change.consume()
                                                jumpDrop(clip, mark)
                                                break
                                            }
                                        }
                                        return@awaitEachGesture
                                    }
                                    down.consume()
                                    draggedFrom = original
                                    draggedDrop = original
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (change.isConsumed || zoomClock != null) break
                                            if (!change.pressed) {
                                                change.consume()
                                                val at = draggedDrop?.sourceMs ?: mark
                                                if (!moveDrop(clip, mark, at)) jumpDrop(clip, mark)
                                                break
                                            }
                                            change.consume()
                                            val dx = change.position.x - down.position.x
                                            if (draggedDrop == original && abs(dx) < viewConfiguration.touchSlop) continue
                                            val delta = (dx / geometry.pxPerMs.coerceAtLeast(0.0001f)).roundToLong()
                                            draggedDrop = original.copy(sourceMs = (mark + delta).coerceIn(clip.fromMs, clip.sourceEndMs - 1))
                                            touchedAt = System.currentTimeMillis()
                                        }
                                    } finally {
                                        draggedFrom = null
                                        draggedDrop = null
                                    }
                                    return@awaitEachGesture
                                }
                            }
                            dropSelection = null
                            dropEditorClipId = null
                            val left = clip?.let { geometry.x(it.startMs, scroll) } ?: 0f
                            val right = clip?.let { geometry.x(it.endMs, scroll) } ?: 0f
                            val handle = minOf(trimWidth, (right - left) / 3f)
                            val linkedAudio = p.multicam != null && clip?.id == Multicam.MASTER_ID
                            val lockedAudio = linkedAudio && !showAngles
                            val edit = when {
                                lockedAudio -> AudioEdit.MOVE
                                clip == null || clip.id != selectedNow -> AudioEdit.MOVE
                                down.position.x - left <= handle -> AudioEdit.START
                                right - down.position.x <= handle -> AudioEdit.END
                                else -> AudioEdit.MOVE
                            }
                            if (edit == AudioEdit.MOVE) {
                                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                            ?: return@withTimeoutOrNull false
                                        if (change.isConsumed || zoomClock != null ||
                                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                        ) return@withTimeoutOrNull false
                                        if (!change.pressed) {
                                            change.consume()
                                            return@withTimeoutOrNull true
                                        }
                                    }
                                    @Suppress("UNREACHABLE_CODE")
                                    false
                                }
                                if (released != null || clip == null || zoomClock != null || linkedAudio) {
                                    if (released == true) {
                                        seekNow(geometry.msAt(down.position.x, scroll), clip?.id)
                                    }
                                    return@awaitEachGesture
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tick(context)
                            }
                            if (clip == null) return@awaitEachGesture
                            down.consume()
                            vm.selectAudioClip(clip.id)
                            pendingAudio = clip
                            val startScroll = scroll
                            val gripSource = clip.sourceAt(geometry.msAt(down.position.x, scroll))
                            val canSnap = edit == AudioEdit.MOVE && geometry.pxPerMs > 0f &&
                                periodsNow.getValue(clip.id) * geometry.pxPerMs >= beatSpacing
                            val snapNeighbors = if (canSnap) audioNow.map { it.clip }.filter {
                                periodsNow.getValue(it.id) * geometry.pxPerMs >= beatSpacing
                            } else emptyList()
                            val tolerance = if (canSnap) minOf(35L, (snapWidth / geometry.pxPerMs).toLong()) else 0L
                            var moved = edit == AudioEdit.MOVE
                            var saved = false
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || zoomClock != null) break
                                    if (!change.pressed) {
                                        change.consume()
                                        val edited = pendingAudio ?: clip
                                        if (!moved) {
                                            seekNow(geometry.msAt(down.position.x, startScroll), clip.id)
                                        }
                                        else if (edited != clip) {
                                            val action = if (edit == AudioEdit.MOVE) Phrase("Перемещён аудиоклип", "Moved audio clip")
                                                else Phrase("Изменены границы аудиоклипа", "Trimmed audio clip")
                                            vm.updateAudioClip(clip.id, action) {
                                                it.copy(startMs = edited.startMs, fromMs = edited.fromMs, toMs = edited.toMs)
                                            }
                                            saved = true
                                        }
                                        break
                                    }
                                    change.consume()
                                    val dx = change.position.x - down.position.x + scroll - startScroll
                                    if (!moved && abs(dx) < viewConfiguration.touchSlop) continue
                                    moved = true
                                    val ms = (dx / geometry.pxPerMs.coerceAtLeast(0.0001f)).roundToLong()
                                    val raw = editAudio(clip, edit, ms)
                                    val snap = if (canSnap) {
                                        snapAudioMove(raw, snapNeighbors, gripSource, tolerance)
                                            ?.takeIf { geometry.rawX(it.timeMs, scroll) in 0f..width }
                                    } else null
                                    pendingAudio = snap?.clip ?: raw
                                    pendingBeat = snap?.timeMs
                                    if (edit == AudioEdit.END) {
                                        trimPan = EndTrimPan(clip, down.position.x, startScroll, change.position.x)
                                    }
                                    touchedAt = System.currentTimeMillis()
                                }
                            } finally {
                                pendingAudio = null
                                pendingBeat = null
                                if (trimPan != null) {
                                    trimPan = null
                                    if (!saved) scroll = scroll.coerceAtMost(geomNow().maxScroll)
                                }
                            }
                        }
                    },
            )
        }
    }
    val master = p.multicamMaster
    if (showAngles && p.multicam != null && master != null) {
        MulticamRows(vm, p.multicam, master, audition, ::geomNow, { scroll }, { zoomClock ?: drawMusicMs ?: clockMs },
            onInspect = onInspect, onDraggingChange = { draggingAngle = it }, modifier = Modifier.then(pinch))
    }
    val selection = dropSelection
    val clip = audio.firstOrNull { it.clip.id == dropEditorClipId }?.clip
    if (clip != null) {
        val selected = selection?.takeIf { it.clipId == clip.id && it.sourceMs in clip.visibleDrops() }?.sourceMs
        val cursor = clip.sourceAt(clockMs).takeIf { clip.contains(clockMs) }
        ProjectDropEditor(
            clip = clip,
            selected = selected,
            cursor = cursor,
            onJump = { at -> jumpDrop(clip, at) },
            onMove = { at -> selected?.let { moveDrop(clip, it, at) } },
            onAdd = {
                cursor?.let { at ->
                    if (vm.editProjectAudioSource(clip.id, Phrase("Добавлен дроп", "Added drop")) { it.addedDrop(at) }) {
                        jumpDrop(clip, at)
                    }
                }
            },
            onPrimary = {
                selected?.let { at ->
                    vm.editProjectAudioSource(clip.id, Phrase("Назначен главный дроп", "Set main drop")) { it.primaryDrop(at) }
                }
            },
            onDelete = {
                selected?.let { at ->
                    if (vm.editProjectAudioSource(clip.id, Phrase("Удалён дроп", "Deleted drop")) { it.removedDrop(at) }) {
                        dropSelection = null
                    }
                }
            },
            onDeleteAll = {
                if (vm.editProjectAudioSource(clip.id, Phrase("Удалены все дропы", "Deleted all drops")) { it.clearedDrops() }) {
                    dropSelection = null
                }
            },
            analysing = clip.source.uri in vm.musicAnalysing,
            onAnalyse = { mode ->
                dropSelection = null
                vm.reanalyseProjectMusic(clip.id, mode)
            },
        )
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
    audio: List<AudioRow>,
    activeAudioId: String?,
    pendingAudio: () -> ProjectAudioClip?,
    pendingBeat: () -> Long?,
    dropSelection: () -> DropSelection?,
    draggedFrom: () -> DropSelection?,
    audioBeats: List<Long>,
    periods: Map<String, Long>,
    peaks: Map<String, FloatArray?>,
    clockMs: Long,
    playhead: () -> Long,
    shotHighlight: MulticamHighlight?,
    videoH: Float,
    musicH: Float,
    joint: Float,
    geometry: () -> TimelineGeometry,
    scroll: () -> Float,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val gap = MaterialTheme.colorScheme.surface
    val wave = MaterialTheme.colorScheme.primary
    val clipColors = listOf(wave, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val colorIndices = remember(audio) { audio.mapIndexed { index, row -> row.clip.id to index }.toMap() }
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, lineHeight = 13.sp,
    )
    val text = rememberTextMeasurer()
    val multicamLabel = tr("Мультикам", "Multicam")
    // The grid is red on purpose: over the frames and the wave it must be the one thing seen at a glance.
    val tick = Color(0xFFFF5252).copy(alpha = 0.90f)
    val dropMark = Color(0xFF00E5FF)
    val playheadColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val offlineDim = Color(0x66000000)
    val playingEdge = Color(0xFFFFC107)
    val thin = with(LocalDensity.current) { 2.dp.toPx() }
    val typicalBeat = remember(audioBeats) { typicalBeatSpacing(audioBeats) }

    Canvas(Modifier.fillMaxSize()) {
        val g = geometry()
        val at = scroll()
        val musicTop = videoH
        drawRect(background, Offset(0f, 0f), Size(size.width, videoH))
        if (audio.isNotEmpty()) drawRect(background, Offset(0f, musicTop), Size(size.width, musicH))
        val playing = g.pieceAt(clockMs)
        clips.forEachIndexed { i, c ->
            val left = g.x(starts.getOrElse(i) { 0L }, at)
            val w = c.durationMs * g.pxPerMs
            if (w <= 0f || left + w < 0f || left > size.width) return@forEachIndexed
            val image = frames[frameKey(c)]
            if (c.file == Multicam.GROUP_FILE) {
                drawRect(wave.copy(alpha = 0.20f), Offset(left, 0f), Size(w, videoH))
                val x = left.coerceAtLeast(0f) + 5.dp.toPx()
                val space = (minOf(size.width, left + w) - x).toInt()
                if (space > 16.dp.toPx()) {
                    val label = text.measure(multicamLabel, labelStyle, overflow = TextOverflow.Ellipsis,
                        maxLines = 1, constraints = Constraints(maxWidth = space))
                    drawText(label, topLeft = Offset(x, (videoH - label.size.height) / 2))
                }
            } else if (image != null) frame(image, left, 0f, w, videoH)
            else drawRect(placeholder, Offset(left, 0f), Size(w, videoH))
            // A piece whose file is out of reach is dimmed, as on its card: the frame is a stand-in, not the film.
            if (offline[c.file] == true) drawRect(offlineDim, Offset(left, 0f), Size(w, videoH))
            if (i > 0) drawRect(gap, Offset(left - joint / 2f, 0f), Size(joint, videoH))
            if (i == playing) {
                drawRect(playingEdge, Offset(left, 0f), Size(w, videoH), style = Stroke(width = thin))
            }
        }
        shotHighlight?.let { selected ->
            val left = g.rawX(selected.start, at)
            val right = g.rawX(selected.end, at)
            drawRect(dropMark.copy(alpha = 0.22f), Offset(left, 0f), Size(right - left, videoH))
            drawRect(dropMark, Offset(left, 0f), Size(right - left, videoH), style = Stroke(3.dp.toPx()))
            selected.cut?.let { cut ->
                val x = g.rawX(cut, at)
                drawLine(Color.Black, Offset(x, 0f), Offset(x, videoH), 8.dp.toPx())
                drawLine(dropMark, Offset(x, 0f), Offset(x, videoH), 4.dp.toPx())
                drawCircle(dropMark, 6.dp.toPx(), Offset(x, videoH / 2))
            }
        }
        val pending = pendingAudio()
        val visibleAudio = if (pending == null) audio else audioRows(audio.map {
            if (it.clip.id == pending.id) pending else it.clip
        })
        val rowHeight = musicH / ((visibleAudio.maxOfOrNull { it.row } ?: 0) + 1)
        val labelHeight = 15.dp.toPx().takeIf { rowHeight >= 30.dp.toPx() } ?: 0f
        val beatSpacing = 8.dp.toPx()
        for (item in visibleAudio) {
            val clip = item.clip
            val source = clip.source
            val left = g.rawX(clip.startMs, at)
            val right = g.rawX(clip.endMs, at)
            if (right <= 0f || left >= size.width || right <= left) continue
            val top = musicTop + item.row * rowHeight
            val active = clip.id == activeAudioId
            val bounds = Size(right - left, rowHeight)
            val clipColor = clipColors[colorIndices.getValue(clip.id) % clipColors.size]
            drawRect(clipColor.copy(alpha = if (active) 0.27f else 0.18f), Offset(left, top), bounds)
            drawRect(clipColor.copy(alpha = 0.75f), Offset(left, top), bounds, style = Stroke(thin / 2))
            val trackFrom = maxOf(clip.fromMs, clip.sourceAt(g.rawMsAt(0f, at)))
            val trackTo = minOf(clip.sourceEndMs, clip.sourceAt(g.rawMsAt(size.width, at)))
            val samples = peaks[source.uri]
            if (samples != null && trackTo > trackFrom && g.pxPerMs > 0f) {
                val count = ((trackTo - trackFrom) * g.pxPerMs / 3f).toInt().coerceAtLeast(1)
                val columns = TrackAudio.window(samples, trackFrom, trackTo, count)
                val perMs = (trackTo - trackFrom).toDouble() / count
                val waveHeight = rowHeight - labelHeight
                val middle = top + labelHeight + waveHeight / 2
                for ((index, value) in columns.withIndex()) {
                    val x = g.rawX(clip.projectAt((trackFrom + (index + 0.5) * perMs).roundToLong()), at)
                    val h = (value * (waveHeight / 2f - 1f)).coerceAtLeast(0.5f)
                    drawLine(clipColor, Offset(x, middle - h), Offset(x, middle + h), strokeWidth = 1.5f)
                }
            }
            for (drop in clip.visibleDrops()) {
                val original = DropSelection(clip.id, drop)
                val selected = dropSelection()
                val shown = if (original == draggedFrom()) selected?.sourceMs ?: drop else drop
                val time = clip.projectAt(shown)
                val x = g.rawX(time, at)
                if (x in 0f..size.width) {
                    val main = drop == source.activeDropMs
                    drawLine(if (main) MainDropColor else dropMark, Offset(x, top), Offset(x, top + rowHeight),
                        if (main) 4f else 2f)
                }
            }
            if (pending != null && periods.getValue(clip.id) * g.pxPerMs >= beatSpacing) {
                for (beat in source.beats) {
                    if (beat < trackFrom || beat >= trackTo) continue
                    val x = g.rawX(clip.projectAt(beat), at)
                    drawLine(tick, Offset(x, top + labelHeight), Offset(x, top + rowHeight), strokeWidth = 2f)
                }
            }
            if (active) {
                val handle = minOf(thin * 3, bounds.width / 3f)
                drawRect(playingEdge, Offset(left, top), bounds, style = Stroke(thin))
                drawRect(playingEdge, Offset(left, top), Size(handle, rowHeight))
                drawRect(playingEdge, Offset(right - handle, top), Size(handle, rowHeight))
            }
        }
        if (pending == null && typicalBeat * g.pxPerMs >= beatSpacing) {
            val beats = audioBeats
            for ((index, beat) in beats.withIndex()) {
                val x = g.rawX(beat, at)
                if (x !in 0f..size.width) continue
                val prev = beats.getOrNull(index - 1) ?: (beat - typicalBeat)
                val next = beats.getOrNull(index + 1) ?: (beat + typicalBeat)
                if (minOf(beat - prev, next - beat) * g.pxPerMs < beatSpacing) continue
                drawLine(tick.copy(alpha = tick.alpha * 0.55f), Offset(x, 0f), Offset(x, videoH), strokeWidth = 2f)
                drawLine(tick, Offset(x, musicTop), Offset(x, musicTop + musicH), strokeWidth = 2.5f)
            }
        }
        val padding = 4.dp.toPx()
        for (item in visibleAudio) {
            val clip = item.clip
            val name = clip.source.name.substringBeforeLast('.')
            if (name.isBlank()) continue
            val left = maxOf(0f, g.rawX(clip.startMs, at))
            val right = minOf(size.width, g.rawX(clip.endMs, at))
            val available = (right - left - 2 * padding).toInt()
            val top = musicTop + item.row * rowHeight
            if (available < 20.dp.toPx() || labelHeight == 0f) continue
            val label = text.measure(name, style = labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = available))
            clipRect(left, top, right, top + labelHeight) {
                drawRect(labelBackground, Offset(left, top), Size(right - left, labelHeight))
                drawText(label, topLeft = Offset(left + padding, top + ((labelHeight - label.size.height) / 2).coerceAtLeast(0f)))
            }
        }
        val cursorX = g.x(playhead(), at)
        if (cursorX in -1f..(size.width + 1f)) {
            drawLine(playheadColor, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = thin)
        }
        for (item in visibleAudio) {
            val clip = item.clip
            val top = musicTop + item.row * rowHeight
            val selected = dropSelection()
            val main = clip.source.activeDropMs
            val drops = clip.visibleDrops()
            for (drop in drops.filterNot { it == main } + listOfNotNull(main?.takeIf { it in drops })) {
                val shown = if (DropSelection(clip.id, drop) == draggedFrom()) selected?.sourceMs ?: drop else drop
                val x = g.rawX(clip.projectAt(shown), at)
                if (x !in 0f..size.width) continue
                val center = Offset(x, top + minOf(rowHeight / 2, 7.dp.toPx()))
                if (drop == main) {
                    val radius = 5.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(center.x, center.y - radius)
                        lineTo(center.x + radius, center.y)
                        lineTo(center.x, center.y + radius)
                        lineTo(center.x - radius, center.y)
                        close()
                    }
                    drawPath(diamond, MainDropColor)
                    drawPath(diamond, Color(0xFF34154D), style = Stroke(1.dp.toPx()))
                } else {
                    drawCircle(dropMark, radius = 3.dp.toPx(), center = center)
                }
                if (selected == DropSelection(clip.id, shown)) {
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                    drawCircle(Color(0xFF22222A), radius = 6.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                }
            }
        }
        pendingBeat()?.let { beat ->
            val x = g.rawX(beat, at)
            if (x in 0f..size.width) {
                drawLine(playingEdge, Offset(x, musicTop), Offset(x, musicTop + musicH), strokeWidth = thin)
            }
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
