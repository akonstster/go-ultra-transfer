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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
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
                            "Одним клипом $clipName. Куски режутся по ключевым кадрам (бывают чуть длиннее), с камеры читаются только они.",
                            "One clip, $clipName. Pieces are cut on key frames (may come out a bit longer); only they are read from the camera.",
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
    DisposableEffect(player) { onDispose { player.release() } }
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
    /** An edit: the pieces in effect come back; [focusMs] picks the piece to keep selected. */
    fun commit(new: List<Highlight>?, focusMs: Long?) {
        val now = onEdit(new)
        current = focusMs?.let { f -> now.indexOfFirst { f in it.startMs..it.endMs!! } } ?: -1
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
            if (pieces.isNotEmpty()) {
                FilmStrip(pieces, frames, current) { i ->
                    current = i
                    player.seekTo(pieces[i].startMs)
                    if (!editing) player.play()
                }
            }
            if (editing || pieces.isEmpty()) {
                PieceEditor(
                    player, pieces, current, durationMs, edited,
                    onChange = { i, piece -> commit(pieces.toMutableList().also { it[i] = piece }, piece.startMs) },
                    onAdd = { at ->
                        val end = if (durationMs > 0) minOf(durationMs, at + Highlights.AROUND_MS) else at + Highlights.AROUND_MS
                        commit(pieces + Highlight(maxOf(0, at - Highlights.AROUND_MS), end), at)
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
 * Editing the pieces under the film strip: the selected piece's start and end as two handles (the slider spans the
 * piece and [EDIT_MARGIN_MS] either side as it was when selected; the picture follows the handle), a new 2 s piece
 * where the video stands, deleting the selected one, and going back to the camera's marks.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PieceEditor(
    player: ExoPlayer,
    pieces: List<Highlight>,
    current: Int,
    durationMs: Long,
    edited: Boolean,
    onChange: (Int, Highlight) -> Unit,
    onAdd: (Long) -> Unit,
    onDelete: (Int) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val piece = pieces.getOrNull(current)
    Column(
        Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        // The handles' positions while dragging (committed when the drag ends).
        var range by remember(piece) { mutableStateOf((piece?.startMs ?: 0L).toFloat()..(piece?.endMs ?: 0L).toFloat()) }
        if (piece != null) {
            val end = piece.endMs!!
            // The scale stays put while this piece is selected (re-scaling it after a drag made the other handle
            // look moved); it only widens when the piece no longer fits (e.g. merged with a neighbour).
            val base = remember(current, durationMs) {
                val last = if (durationMs > 0) durationMs else end + EDIT_MARGIN_MS
                maxOf(0L, piece.startMs - EDIT_MARGIN_MS).toFloat()..minOf(last, end + EDIT_MARGIN_MS).toFloat()
            }
            val window = minOf(base.start, piece.startMs.toFloat())..maxOf(base.endInclusive, end.toFloat())
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    val moved = if (r.start != range.start) r.start else r.endInclusive
                    range = r
                    player.pause()
                    player.seekTo(moved.toLong())
                },
                onValueChangeFinished = { onChange(current, Highlight(range.start.toLong(), range.endInclusive.toLong())) },
                valueRange = window,
                colors = SliderDefaults.colors(
                    thumbColor = MARK_COLOR, activeTrackColor = MARK_COLOR, inactiveTrackColor = Color(0x55FFFFFF),
                ),
            )
        }
        // «Готово» on the line with the piece's times: the actions below need the whole width on a phone held upright.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (piece != null) {
                    "${clockTenths(range.start.toLong())} – ${clockTenths(range.endInclusive.toLong())} · " +
                        "%.1f ".format((range.endInclusive - range.start) / 1000.0) + tr("с", "s")
                } else if (pieces.isEmpty()) {
                    tr("Кусков нет — добавьте там, где стоит видео", "No pieces — add one where the video stands")
                } else {
                    tr("Выберите кусок в ленте или добавьте новый", "Pick a piece in the strip or add a new one")
                },
                color = if (piece != null) MARK_COLOR else Color(0xCCFFFFFF),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (pieces.isNotEmpty()) Button(onDone, Modifier.padding(start = 8.dp)) { Text(tr("Готово", "Done")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onAdd(player.currentPosition) }) { Text(tr("+ Кусок", "+ Piece"), color = Color.White) }
            if (piece != null) TextButton({ onDelete(current) }) { Text(tr("Удалить", "Delete"), color = Color.White) }
            if (edited) TextButton(onReset) { Text(tr("Как на камере", "As on camera"), color = Color.White) }
        }
    }
}

/** The slider for a piece spans it and this much either side. */
private const val EDIT_MARGIN_MS = 5_000L

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
