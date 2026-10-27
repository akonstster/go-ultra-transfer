package app.goultra.transfer.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Flag
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.KeyFrames
import app.goultra.transfer.camera.LivePhoto
import app.goultra.transfer.camera.MediaKind

/**
 * Quick look at a file that is still on the camera: video plays its low-res LRV copy (or the file itself if there is
 * none) in the built-in player, photos open full size with pinch zoom. «Скачать» takes just this file.
 * A video already on the phone ([CameraFile.isOnPhone]) plays from there, full quality, marks read from the file.
 */
@Composable
fun FileViewer(vm: CameraController, file: CameraFile, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val clip = vm.liveClipOf(file)
            var playing by remember(file.uri) { mutableStateOf(false) }
            // Video: the pieces to play, show in the film strip and download — the camera's marks as its film strip
            // shows them (2 s pieces, nearby ones merged), or the pieces as edited on the phone.
            val isVideo = file.kind == MediaKind.VIDEO
            val limitMs = file.durationSec?.let { (it + 1) * 1000L }
            val marks by produceState(emptyList<Highlight>(), file.uri, file.localUri) { if (isVideo) value = vm.highlights(file) }
            val cameraPieces = remember(marks) { Highlights.pieces(marks, limitMs) }
            var edited by remember(file.name) { mutableStateOf(vm.editedPieces(file)) }
            val pieces = edited ?: cameraPieces
            /** Saves an edit (null: back to the camera's marks); returns the pieces now in effect. */
            fun edit(new: List<Highlight>?): List<Highlight> {
                val tidy = new?.let { Highlights.normalize(it, limitMs) }
                edited = tidy
                vm.savePieces(file, tidy)
                return tidy ?: cameraPieces
            }
            var choose by remember(file.uri) { mutableStateOf(false) }
            when {
                isVideo -> VideoPreview(vm, file, pieces, edited != null, ::edit)
                clip != null && playing -> FramePicker(vm, file, clip) { playing = false }
                else -> PhotoPreview(vm, file)
            }
            // Live photo: its clip, where a frame can become the cover; and the way back to the camera's photo.
            if (clip != null && !playing && vm.coverBusy == null) {
                Column(
                    Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button({ playing = true }) { Text(tr("▶ Живое фото · выбрать обложку", "▶ Live photo · pick the cover")) }
                    if (vm.coverReplaced(file)) {
                        OutlinedButton({ vm.restoreLiveCover(file) }) { Text(tr("Вернуть исходное фото", "Back to the original photo"), color = Color.White) }
                    }
                }
            }
            vm.coverBusy?.let { busy ->
                Column(
                    Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(busy, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).safeDrawingPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClose) { Icon(Icons.Filled.Close, tr("Закрыть", "Close"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (file.isOnPhone) {
                            tr("на телефоне", "on the phone")
                        } else if (file.kind == MediaKind.VIDEO && vm.previewUrl(file).endsWith(".lrv")) {
                            tr("с камеры · низкое качество", "from the camera · low quality")
                        } else {
                            tr("с камеры", "from the camera")
                        },
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (!file.isOnPhone && file.name !in vm.downloaded) {
                    // With pieces marked: the whole video or one clip of the pieces.
                    Button(
                        { if (isVideo && pieces.isNotEmpty()) choose = true else { vm.downloadOne(file); onClose() } },
                        enabled = !vm.isDownloading,
                    ) { Text(tr("Скачать", "Download")) }
                } else if (isVideo && pieces.isNotEmpty()) {
                    // Already on the phone: a clip of the pieces, made from the phone's copy.
                    Button({ vm.saveClip(file, pieces); onClose() }, enabled = !vm.isDownloading) {
                        Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Клип", "Clip"))
                    }
                }
            }
            if (choose) {
                DownloadChoice(
                    file, pieces, vm.clipName(file),
                    onWhole = { choose = false; vm.downloadOne(file); onClose() },
                    onPieces = { choose = false; vm.saveClip(file, pieces); onClose() },
                    onDismiss = { choose = false },
                )
            }
        }
    }
}

/** «Скачать» for a video with marked pieces: the whole file, or one clip of just the pieces. */
@Composable
private fun DownloadChoice(
    file: CameraFile,
    pieces: List<Highlight>,
    clipName: String,
    onWhole: () -> Unit,
    onPieces: () -> Unit,
    onDismiss: () -> Unit,
) {
    val piecesMs = pieces.sumOf { it.endMs!! - it.startMs }
    val clipBytes = file.size?.let { size -> file.durationSec?.takeIf { it > 0 }?.let { size * piecesMs / (it * 1000L) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Скачать видео", "Download the video")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Целиком", "Whole video") + (file.size?.let { " — ${formatSize(it)}" } ?: ""))
                Text(
                    tr("Только отмеченное", "Marked pieces only") + " — ${pieces.size} · " + "%.1f ".format(piecesMs / 1000.0) +
                        tr("с", "s") + (clipBytes?.let { ", ~${formatSize(it)}" } ?: "") + "\n" +
                        tr(
                            "Одним клипом $clipName. Начало куска сдвигается назад к ключевому кадру, с камеры читаются только куски.",
                            "One clip, $clipName. A piece's start moves back to a key frame; only the pieces are read from the camera.",
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onPieces) { Text(tr("Только отмеченное", "Marked only")) } },
        dismissButton = { TextButton(onWhole) { Text(tr("Целиком", "Whole")) } },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(
    vm: CameraController,
    file: CameraFile,
    pieces: List<Highlight>,
    edited: Boolean,
    onEdit: (List<Highlight>?) -> List<Highlight>,
) {
    val context = LocalContext.current
    val source = remember { if (file.isOnPhone) null else vm.previewSource() }
    if (source == null && !file.isOnPhone) {
        Text(tr("Нет связи с камерой по Wi-Fi", "No Wi-Fi link to the camera"), color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    val player = remember(file.uri) {
        ExoPlayer.Builder(context).build().apply {
            if (source == null) {
                setMediaItem(MediaItem.fromUri(file.localUri!!)) // the phone's copy: the default (content://) source
            } else {
                setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(vm.previewUrl(file))))
            }
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release(); vm.closeTimeline() } }
    var durationMs by remember(file.uri) { mutableLongStateOf(file.durationSec?.let { it * 1000L } ?: 0L) }
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) durationMs = player.duration
            delay(500)
        }
    }
    // Ticks on the seek bar at the pieces; the flag switches to the film strip — only the pieces play, the rest is
    // skipped. In the strip the pieces can be edited (kept on the phone): then playback is free, for finding spots.
    var film by remember(file.uri) { mutableStateOf(false) }
    var editing by remember(file.uri) { mutableStateOf(false) }
    var current by remember(file.uri) { mutableIntStateOf(-1) }
    LaunchedEffect(player, film, editing, pieces) {
        if (!film || editing || pieces.isEmpty()) return@LaunchedEffect
        current = -1
        player.seekTo(pieces[0].startMs)
        player.play()
        while (true) {
            val pos = player.currentPosition
            val i = pieces.indexOfFirst { pos < it.endMs!! }
            if (i < 0) {
                // Past the last piece: back to the first, stopped (the play button runs the strip again).
                player.pause()
                player.seekTo(pieces[0].startMs)
                current = 0
            } else {
                if (pos < pieces[i].startMs - SKIP_SLACK_MS) player.seekTo(pieces[i].startMs)
                current = i
            }
            delay(40)
        }
    }
    // Frames for the strip: read when the strip is open and the pieces change (Range reads of the light copy, or the
    // phone's file).
    var frames by remember(file.uri) { mutableStateOf<List<ImageBitmap?>>(emptyList()) }
    var framesOf by remember(file.uri) { mutableStateOf<List<Highlight>?>(null) }
    LaunchedEffect(film, pieces) {
        if (film && framesOf != pieces) {
            frames = vm.pieceFrames(file, pieces)
            framesOf = pieces
        }
    }
    // Key frames of the full-quality file: a piece's start snaps to the nearest one (the clip starts on a key frame),
    // so the strip shows what the clip will be. Read from the file's index when editing first starts.
    var keys by remember(file.uri) { mutableStateOf<KeyFrames?>(null) }
    LaunchedEffect(editing) { if (editing && keys == null) keys = vm.keyFrames(file) }
    /** An edit: the pieces in effect come back; [focusMs] picks the piece to keep selected. */
    fun commit(new: List<Highlight>?, focusMs: Long?) {
        val now = onEdit(new)
        current = focusMs?.let { f -> now.indexOfFirst { f >= it.startMs && f < it.endMs!! } } ?: -1
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
                update = { view -> view.setExtraAdGroupMarkers(pieces.map { it.startMs }.toLongArray(), BooleanArray(pieces.size)) },
                modifier = Modifier.fillMaxSize(),
            )
            // Shown without marks too: pieces can be added by hand in the strip.
            FilterChip(
                selected = film,
                onClick = { film = !film; if (!film) editing = false },
                label = { Text(if (film) tr("Отмеченное", "Highlights only") else "${pieces.size}") },
                leadingIcon = { Icon(Icons.Filled.Flag, tr("Отметки", "Highlights"), tint = MARK_COLOR) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0x99000000), labelColor = Color.White,
                    selectedContainerColor = Color(0xCC000000), selectedLabelColor = MARK_COLOR,
                ),
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(top = 60.dp, start = 12.dp),
            )
        }
        if (film) {
            if (pieces.isNotEmpty() && !editing) {
                FilmStrip(pieces, frames, current) { i ->
                    current = i
                    player.seekTo(pieces[i].startMs)
                    if (!editing) player.play()
                }
            }
            if (editing || pieces.isEmpty()) {
                TimelineEditor(
                    vm, file, player, pieces, current, durationMs, edited, keys,
                    onSelect = { current = it },
                    onChange = { i, piece -> commit(pieces.toMutableList().also { it[i] = piece }, piece.startMs) },
                    onAdd = { at ->
                        // Not on top of another piece: into the free gap there (Highlights.pieceAt).
                        val added = Highlights.pieceAt(pieces, at, durationMs.takeIf { it > 0 })
                            ?.let { p -> keys?.snapStart(p, pieces) ?: p }
                        if (added == null) {
                            android.widget.Toast.makeText(context, tr("Здесь нет места для куска", "No room for a piece here"), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            commit(pieces + added, added.startMs)
                        }
                    },
                    onDelete = { i -> commit(pieces.filterIndexed { j, _ -> j != i }, null) },
                    onReset = { commit(null, null) },
                    onDone = { editing = false },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton({ editing = true; player.pause() }) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = MARK_COLOR)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Править куски", "Edit pieces"), color = MARK_COLOR)
                    }
                }
            }
        }
    }
}

/** The camera-style film strip: a frame per marked piece with its length; the playing one framed in yellow. */
@Composable
private fun FilmStrip(pieces: List<Highlight>, frames: List<ImageBitmap?>, current: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(maxOf(0, current - 1)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(vertical = 10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pieces) { i, piece ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(i) }) {
                Box(
                    Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222222))
                        .border(2.dp, if (i == current) MARK_COLOR else Color.Transparent, RoundedCornerShape(6.dp)),
                ) {
                    frames.getOrNull(i)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    val seconds = (piece.endMs!! - piece.startMs) / 1000.0
                    Text(
                        "%.1f ".format(seconds) + tr("с", "s"),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                }
                Text(clock(piece.startMs), color = if (i == current) MARK_COLOR else Color(0xCCFFFFFF), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Editing the pieces as in a video editor: frames along the whole video under a fixed playhead in the middle — drag
 * the strip to move through the video (the picture follows), pinch to zoom from the whole video to a couple of
 * seconds. Pieces are yellow bands over the frames; a tap selects one, its edges get handles: the start snaps to the
 * nearest key frame (ticks at the top), the end is free (snaps too if the video has B-frames), neither crosses a
 * neighbour. «+ Кусок» adds one at the playhead (into the free gap there).
 */
@Composable
private fun TimelineEditor(
    vm: CameraController,
    file: CameraFile,
    player: ExoPlayer,
    pieces: List<Highlight>,
    current: Int,
    durationMs: Long,
    edited: Boolean,
    keys: KeyFrames?,
    onSelect: (Int) -> Unit,
    onChange: (Int, Highlight) -> Unit,
    onAdd: (Long) -> Unit,
    onDelete: (Int) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    val tilePx = with(density) { TILE_WIDTH.toPx() }
    val handlePx = with(density) { HANDLE_WIDTH.toPx() }
    val duration = maxOf(durationMs, 1L)
    var widthPx by remember { mutableIntStateOf(0) }
    // Zoom (px per ms; 0 until the width is known) and the time under the playhead.
    var pxPerMs by remember(file.uri) { mutableFloatStateOf(0f) }
    var pos by remember(file.uri) { mutableLongStateOf(player.currentPosition) }
    val minPpm = if (widthPx > 0) widthPx.toFloat() / duration else 0f
    val maxPpm = if (widthPx > 0) maxOf(minPpm, widthPx / MIN_SPAN_MS) else 0f
    LaunchedEffect(widthPx, duration) {
        if (widthPx > 0) pxPerMs = if (pxPerMs == 0f) widthPx / minOf(duration, START_SPAN_MS).toFloat() else pxPerMs.coerceIn(minPpm, maxPpm)
    }
    // While the strip is being dragged (and a moment after) it leads and the player follows; otherwise the strip
    // follows the playing video.
    var touchedAt by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Highlight?>(null) } // the selected piece while a handle is dragged
    LaunchedEffect(player) {
        while (true) {
            if (drag == null && android.os.SystemClock.uptimeMillis() - touchedAt > 400) pos = player.currentPosition
            delay(30)
        }
    }
    val shown = if (drag != null && current in pieces.indices) pieces.toMutableList().also { it[current] = drag!! } else pieces
    val latestShown by rememberUpdatedState(shown)
    val latestSelect by rememberUpdatedState(onSelect)

    // Frames: one per tile, at multiples of 2 s (the camera's key frame spacing), wider steps when zoomed out —
    // the same times at every zoom, so loaded frames are reused. Nearest the playhead first.
    val frames = remember(file.uri) { mutableStateMapOf<Long, ImageBitmap>() }
    val step = if (pxPerMs > 0f) tileStep(pxPerMs, tilePx) else 0L
    val half = if (pxPerMs > 0f) (widthPx / 2f / pxPerMs).toLong() else 0L
    val firstTile = if (step > 0) maxOf(0L, (pos - 2 * half) / step) else 0L
    val lastTile = if (step > 0) minOf((duration - 1) / step, (pos + 2 * half) / step) else -1L
    LaunchedEffect(file.uri, step, firstTile, lastTile) {
        if (step <= 0) return@LaunchedEffect
        val wanted = (firstTile..lastTile).map { it * step }.filter { it !in frames }.sortedBy { abs(it - pos) }
        for (t in wanted) vm.timelineFrame(file, t)?.let { frames[t] = it }
    }

    Column(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(vertical = 6.dp)) {
        // Where the playhead is, and the selected piece.
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val sel = shown.getOrNull(current)
            Text(clockTenths(pos), color = Color.White, fontSize = 13.sp)
            Text(
                if (sel != null) {
                    "   ${clockTenths(sel.startMs)} – ${clockTenths(sel.endMs!!)} · " + "%.1f ".format((sel.endMs - sel.startMs) / 1000.0) + tr("с", "s")
                } else if (pieces.isEmpty()) {
                    "   " + tr("Кусков нет — добавьте у линии", "No pieces — add one at the line")
                } else {
                    "   " + tr("Коснитесь куска, чтобы править", "Tap a piece to edit it")
                },
                color = if (sel != null) MARK_COLOR else Color(0xCCFFFFFF),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pieces.isNotEmpty()) Button(onDone, Modifier.padding(start = 8.dp)) { Text(tr("Готово", "Done")) }
        }
        Box(
            Modifier.fillMaxWidth().height(TIMELINE_HEIGHT).onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        if (pxPerMs <= 0f) return@detectTapGestures
                        val ms = pos + ((o.x - size.width / 2f) / pxPerMs).toLong()
                        latestSelect(latestShown.indexOfFirst { ms >= it.startMs && ms < it.endMs!! })
                    }
                }
                .pointerInput(widthPx, duration) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (pxPerMs <= 0f) return@detectTransformGestures
                        pxPerMs = (pxPerMs * zoom).coerceIn(minPpm, maxPpm)
                        if (pan.x != 0f) {
                            pos = (pos - (pan.x / pxPerMs).toLong()).coerceIn(0L, duration)
                            touchedAt = android.os.SystemClock.uptimeMillis()
                            player.pause()
                            player.seekTo(pos)
                        }
                    }
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val ppm = pxPerMs
                if (ppm <= 0f || step <= 0) return@Canvas
                val cx = size.width / 2f
                fun x(ms: Long) = cx + (ms - pos) * ppm
                val stripTop = KEY_TICKS_PX
                val stripH = size.height - stripTop
                for (k in firstTile..lastTile) {
                    val t = k * step
                    val left = x(t)
                    val right = x(minOf(t + step, duration))
                    if (right < 0 || left > size.width) continue
                    // A dark gap between frames, so each one's edges show.
                    val img = frames[t]
                    if (img == null) {
                        drawRect(Color(0xFF262626), Offset(left, stripTop), Size(right - left - FRAME_GAP_PX, stripH))
                    } else {
                        drawCover(img, left, stripTop, right - left - FRAME_GAP_PX, stripH)
                    }
                }
                // Key frames: where a piece can start (only when they aren't packed too tight to see).
                keys?.ms?.let { ks ->
                    if (ks.size > 1 && (ks[1] - ks[0]) * ppm >= 6f) {
                        for (k in ks) {
                            val kx = x(k)
                            if (kx in 0f..size.width) drawLine(Color(0xCCFFFFFF), Offset(kx, 0f), Offset(kx, stripTop - 2), 2f)
                        }
                    }
                }
                // Pieces: a band, bold edges and a triangle inside each edge at the top — two pieces that meet still
                // show where one ends and the next begins.
                val edge = MARK_COLOR.copy(alpha = 1f)
                val tri = EDGE_TRIANGLE_PX
                for ((i, p) in shown.withIndex()) {
                    val left = x(p.startMs)
                    val right = x(p.endMs!!)
                    if (right < 0 || left > size.width) continue
                    drawRect(MARK_COLOR.copy(alpha = if (i == current) 0.45f else 0.3f), Offset(left, stripTop), Size(right - left, stripH))
                    drawLine(edge, Offset(left + 2, stripTop), Offset(left + 2, size.height), 4f)
                    drawLine(edge, Offset(right - 2, stripTop), Offset(right - 2, size.height), 4f)
                    val t = minOf(tri, (right - left) / 2)
                    drawPath(Path().apply { moveTo(left, stripTop); lineTo(left + t, stripTop); lineTo(left, stripTop + t); close() }, edge)
                    drawPath(Path().apply { moveTo(right, stripTop); lineTo(right - t, stripTop); lineTo(right, stripTop + t); close() }, edge)
                    if (i == current) drawRect(edge, Offset(left, stripTop), Size(right - left, stripH), style = Stroke(2f))
                }
                drawLine(Color.White, Offset(cx, 0f), Offset(cx, size.height), 4f)
            }
            // Handles on the selected piece's edges.
            val sel = shown.getOrNull(current)
            if (sel != null) {
                for (edge in listOf(Edge.START, Edge.END)) {
                    Box(
                        Modifier
                            .offset {
                                val ms = if (edge == Edge.START) sel.startMs else sel.endMs!!
                                val cx = widthPx / 2f
                                IntOffset((cx + (ms - pos) * pxPerMs - handlePx / 2).roundToInt(), 0)
                            }
                            .size(HANDLE_WIDTH, TIMELINE_HEIGHT)
                            .pointerInput(current, edge, pieces, keys, duration) {
                                var raw = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        val p = pieces.getOrNull(current) ?: return@detectHorizontalDragGestures
                                        drag = p
                                        raw = (if (edge == Edge.START) p.startMs else p.endMs!!).toFloat()
                                        player.pause()
                                    },
                                    onDragEnd = {
                                        drag?.let { p -> onChange(current, p); pos = if (edge == Edge.START) p.startMs else p.endMs!! }
                                        drag = null
                                    },
                                    onDragCancel = { drag = null },
                                ) { change, dx ->
                                    change.consume()
                                    val p = drag ?: return@detectHorizontalDragGestures
                                    raw += dx / pxPerMs
                                    val before = pieces.take(current).maxOfOrNull { it.endMs!! } ?: 0L
                                    val after = pieces.drop(current + 1).minOfOrNull { it.startMs } ?: duration
                                    val minLen = Highlights.MIN_PIECE_MS
                                    val moved = if (edge == Edge.START) {
                                        val hi = p.endMs!! - minLen
                                        // A key frame, or right onto the end of the piece before (they then meet).
                                        val touch = if (current > 0) before else null
                                        (keys?.nearestStart(raw.toLong(), before, hi, touch) ?: raw.toLong()).coerceIn(before, maxOf(before, hi))
                                    } else {
                                        val lo = p.startMs + minLen
                                        val free = raw.toLong()
                                        (if (keys?.bFrames == true) keys.nearest(free, lo, after) else free).coerceIn(minOf(lo, after), after)
                                    }
                                    drag = if (edge == Edge.START) p.copy(startMs = moved) else p.copy(endMs = moved)
                                    player.seekTo(moved)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(HANDLE_WIDTH - 6.dp, TIMELINE_HEIGHT - 6.dp).background(MARK_COLOR, RoundedCornerShape(4.dp)))
                        Box(Modifier.size(2.dp, 18.dp).background(Color.Black))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onAdd(pos) }) { Text(tr("+ Кусок", "+ Piece"), color = Color.White) }
            if (current in pieces.indices) TextButton({ onDelete(current) }) { Text(tr("Удалить", "Delete"), color = Color.White) }
            if (edited) TextButton(onReset) { Text(tr("Как на камере", "As on camera"), color = Color.White) }
        }
    }
}

private enum class Edge { START, END }

/** A frame fills its tile, cropped to the tile's shape. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCover(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float) {
    if (w <= 0f || h <= 0f) return
    val tileAspect = w / h
    val imgAspect = img.width.toFloat() / img.height
    val (sw, sh) = if (imgAspect > tileAspect) (img.height * tileAspect).toInt() to img.height else img.width to (img.width / tileAspect).toInt()
    drawImage(
        img,
        srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
        srcSize = IntSize(maxOf(1, sw), maxOf(1, sh)),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(maxOf(1, w.roundToInt()), maxOf(1, h.roundToInt())),
    )
}

/** Time per frame tile: 2 s (the key frame spacing), doubled until a tile is at least [tilePx] wide. */
private fun tileStep(pxPerMs: Float, tilePx: Float): Long {
    var step = 2_000L
    while (step * pxPerMs < tilePx) step *= 2
    return step
}

private val TIMELINE_HEIGHT = 64.dp
private val TILE_WIDTH = 56.dp
private val HANDLE_WIDTH = 22.dp

/** Dark gap between frames on the timeline. */
private const val FRAME_GAP_PX = 3f

/** The triangles marking a piece's edges (inside it, at the top of the frames). */
private const val EDGE_TRIANGLE_PX = 26f

/** Room above the frames for the key frame ticks. */
private const val KEY_TICKS_PX = 14f

/** The timeline opens showing this much (or the whole video if shorter) and zooms in to [MIN_SPAN_MS] per screen. */
private const val START_SPAN_MS = 20_000L
private const val MIN_SPAN_MS = 1_500f

private fun clockTenths(ms: Long): String = "%d:%02d.%d".format(ms / 60_000, ms / 1000 % 60, ms / 100 % 10)

/** A seek can land a little before the piece's start (key frames); don't bounce on that. */
private const val SKIP_SLACK_MS = 300L

/** The seek bar's default marker colour (media3 ad markers), so the chips match the ticks. */
private val MARK_COLOR = Color(0xB2FFFF00)

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/**
 * A live photo's clip (its low-res copy, straight from the camera) as a frame picker: our own slider over the whole
 * clip, the frame large, a play button to watch it. «Сделать обложкой» replaces the photo in the gallery file with
 * that frame (taken from the full-resolution clip).
 */
@OptIn(UnstableApi::class)
@Composable
private fun FramePicker(vm: CameraController, photo: CameraFile, clip: CameraFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val source = remember { vm.previewSource() }
    if (source == null) {
        Text(tr("Нет связи с камерой по Wi-Fi", "No Wi-Fi link to the camera"), color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    val player = remember(clip.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(vm.previewUrl(clip))))
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            seekTo(LivePhoto.DEFAULT_COVER_US / 1000) // where the photo was taken
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(LivePhoto.DEFAULT_COVER_US / 1000) }
    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) durationMs = player.duration
            if (player.isPlaying) positionMs = player.currentPosition
            isPlaying = player.isPlaying
            delay(50)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 64.dp)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Column(
            Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (player.isPlaying) player.pause() else player.play() }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                }
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = { player.pause(); positionMs = it.toLong(); player.seekTo(positionMs) },
                    valueRange = 0f..maxOf(durationMs, 1L).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                tr("Кадр: ", "Frame: ") + "%.2f ".format(positionMs / 1000.0) + tr("с", "s") + " · " +
                    tr("фото снято на 1,5 с", "the photo was taken at 1.5 s"),
                color = Color(0xCCFFFFFF),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                { player.pause(); vm.setLiveCover(photo, player.currentPosition * 1000); onBack() },
                Modifier.fillMaxWidth(),
            ) { Text(tr("Сделать обложкой", "Make it the cover")) }
            OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text(tr("Отмена", "Cancel"), color = Color.White) }
            Text(
                tr(
                    "Фото в галерее заменится этим кадром из видео 3840×2880 (чуть мягче фото). Вернуть исходное — кнопкой в просмотре.",
                    "The gallery photo is replaced by this frame of the 3840×2880 clip (a bit softer). Get the original back from the viewer.",
                ),
                color = Color(0x99FFFFFF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PhotoPreview(vm: CameraController, file: CameraFile) {
    val config = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) { (maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 1.5f).toInt() }
    // Reloaded after a cover change (the photo is then the chosen frame).
    val image by produceState<ImageBitmap?>(null, file.uri, vm.coverVersion) { value = vm.previewPhoto(file, maxSide) }
    var loaded by remember(file.uri) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = image
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            loaded = true
            Image(
                bmp,
                file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        // Double tap: 1x ↔ 2.5x.
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .transformable(zoom)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}
