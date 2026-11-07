package app.goultra.transfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.MediaKind
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip

/** The files' pieces as they stand ([all]), and the same for the files edited on the phone ([edited]). */
private data class FilePieces(
    val all: Map<String, List<Highlight>>,
    val edited: Map<String, List<Highlight>>,
)

/** The card in the hand: the piece under the finger while the timeline slides (the joystick's knob). */
@Composable
private fun HeldCard(vm: CameraController, clip: ProjectClip, file: CameraFile?, modifier: Modifier) {
    val thumb by produceState<ImageBitmap?>(null, clip.file, clip.fromMs, clip.role) {
        value = file?.let {
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs)
        }
    }
    Column(modifier.width(CARD_W).graphicsLayer { scaleX = 1.06f; scaleY = 1.06f; alpha = 0.72f }) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC000000))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                formatDuration((clip.durationMs / 1000).toInt()),
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(file?.stem ?: clip.file, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The slot nearest the middle of the strip: the card at the eye while the timeline slides under it. */
private fun centerSlot(state: LazyListState, pitch: Float, padPx: Float, stripW: Float, count: Int): Int {
    if (count <= 0 || pitch <= 0f) return 0
    val atMiddle = state.firstVisibleItemIndex * pitch + state.firstVisibleItemScrollOffset + stripW / 2f
    return ((atMiddle - padPx) / pitch).roundToInt().coerceIn(0, count - 1)
}

/** One card of the strip: its width and the room between two of them (a drag counts places by that distance). */
private val CARD_W = 150.dp
private val CARD_GAP = 10.dp

/** How much the cards recede while one is carried: five or six of them across a phone screen. */
private const val DRAG_SCALE = 0.45f

/**
 * The stick's speed in px/s: nothing inside the middle, then the further the finger is from where the card was taken
 * the faster the timeline slides, capped so a long move stays controllable — a transmitter stick, not a drag.
 */
private fun stickSpeed(offsetPx: Float): Float {
    val dead = 24f
    val full = 150f
    val capped = 900f
    val away = abs(offsetPx)
    if (away <= dead) return 0f
    val ramp = ((away - dead) / (full - dead)).coerceIn(0f, 1f)
    return ramp * capped * (if (offsetPx > 0f) 1f else -1f)
}

/**
 * A project's own screen: the pieces it is made of, in the order they will play (see `memory-bank/projects-plan.md`).
 * Dragging the cards around, the base with its cutaways, the preview and the export come next; the file behind a piece
 * is edited in the usual film strip — the card opens it.
 */
@Composable
fun ProjectScreen(vm: CameraController, p: Project) {
    // The files' pieces as they stand, and the files edited on the phone apart: the project's clips follow those edits
    // (resync below). Re-read on opening and after every edit in the film strip ([CameraController.piecesVersion]).
    val loaded by produceState<FilePieces>(FilePieces(emptyMap(), emptyMap()), p.id, p.clips.size, vm.piecesVersion) {
        val all = mutableMapOf<String, List<Highlight>>()
        val edited = mutableMapOf<String, List<Highlight>>()
        for (name in p.clips.map { it.file }.distinct()) {
            val f = vm.projectFile(name) ?: continue
            val limit = f.durationSec?.let { (it + 1) * 1000L }
            val own = vm.editedPieces(f)
            all[name] = Highlights.inEffect(own, vm.highlights(f), limit, f.durationSec?.let { it * 1000L })
            if (own != null) edited[name] = all.getValue(name)
        }
        value = FilePieces(all, edited)
    }
    // A piece added, dropped or re-cut in the film strip is what the project takes of that file from then on (§7).
    LaunchedEffect(loaded.edited) { vm.resyncProjectClips(loaded.edited) }
    val stale = remember(loaded.all, p.clips) { p.staleClips(loaded.all).toSet() }
    val totalMs = p.clips.sumOf { it.durationMs }

    // A project's cards are all one width: the finger's travel counts places by it (see the strip below).
    var play by remember(p.id) { mutableStateOf<ProjectClip?>(null) }
    var lifted by remember(p.id) { mutableStateOf<ProjectClip?>(null) }
    var heldFrom by remember(p.id) { mutableIntStateOf(-1) }
    var baseTarget by remember(p.id) { mutableIntStateOf(0) }
    var targetArray by remember(p.id) { mutableIntStateOf(-1) }
    var stickPx by remember(p.id) { mutableFloatStateOf(0f) }
    var heldX by remember(p.id) { mutableFloatStateOf(0f) }
    var stripW by remember(p.id) { mutableIntStateOf(0) }
    var playingNow by remember(p.id) { mutableStateOf<ProjectClip?>(null) }
    val listState = rememberLazyListState()
    // The cards are all one width and the strip is padded so any slot can sit under its middle; while a card is
    // carried they lay themselves out smaller, so five or six read at once and the eye keeps the timeline.
    val density = LocalDensity.current
    val cardWNow by animateDpAsState(if (lifted != null) CARD_W * DRAG_SCALE else CARD_W)
    val pitchPx = with(density) { (cardWNow + CARD_GAP).toPx() }
    val padNow = with(density) { maxOf(0.dp, (stripW.toDp() - cardWNow) / 2) }
    val padPx = with(density) { padNow.toPx() }
    val cardWpx = with(density) { cardWNow.toPx() }
    // The stick's own loop: near the middle nothing happens, further out the timeline slides under the card in the
    // hand; the point of insertion is the slot at the middle of the strip, so it never leaves the eye.
    LaunchedEffect(lifted, pitchPx, padPx) {
        while (lifted != null) {
            val speed = stickSpeed(stickPx)
            if (speed != 0f) listState.scrollBy(-speed * 0.016f)
            val slot = centerSlot(listState, pitchPx, padPx, stripW.toFloat(), p.clips.size)
            targetArray = (heldFrom + (slot - baseTarget)).coerceIn(0, p.clips.size - 1)
            delay(16)
        }
    }
    // While something plays, its card is marked and kept in view (asked for on the phone, 07.11).
    LaunchedEffect(playingNow, lifted, stripW) {
        if (lifted != null || stripW <= 0) return@LaunchedEffect
        val at = playingNow?.let { p.clips.indexOf(it) } ?: -1
        if (at >= 0) listState.animateScrollToItem(at, -(stripW / 2 - (cardWpx / 2).toInt()))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(vm::closeProject) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад", "Back")) }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tr("%s кусков", "%s clips").format(p.clips.size) + " · " +
                        tr("Всего %s", "%s total").format(formatDuration((totalMs / 1000).toInt())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The pieces that can play: a photo is a still, and waits for the slideshow way of showing them (P4).
        val playable = p.clips.filter { vm.projectFile(it.file)?.kind == MediaKind.VIDEO }
        if (playable.isNotEmpty()) ProjectPreview(vm, playable, play, onPlayed = { play = null }, onPlaying = { playingNow = it })
        if (p.clips.isEmpty()) {
            Text(
                tr(
                    "Пусто: добавьте файлы из списка кнопкой «В проект»",
                    "Empty: add files from the list with «Add to project»",
                ),
                Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // The strip is padded so any slot can sit under its middle — that middle is where a carried card lands,
            // so the point of insertion never leaves the eye while the timeline slides (asked for on the phone, 07.11).
            Box(Modifier.fillMaxWidth().onSizeChanged { stripW = it.width }) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = padNow),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    itemsIndexed(p.clips, key = { i, c -> "$i-${c.file}-${c.fromMs}" }) { i, clip ->
                        // «The file is not on the list any more» is only said when the list is complete: with the
                        // camera away a file it holds is simply out of reach, not gone.
                        ClipCard(
                            vm, p, clip, vm.projectFile(clip.file),
                            stale = clip in stale,
                            missing = vm.projectFile(clip.file) == null && vm.conn == ConnState.Connected,
                            width = cardWNow,
                            alpha = if (lifted == clip) 0.25f else 1f,
                            target = lifted != null && i == targetArray,
                            playing = clip == playingNow,
                            // Tapping plays the piece in the preview; a photo has nothing to play, so it opens instead.
                            onTap = {
                                val f = vm.projectFile(clip.file)
                                if (clip.role == ProjectClip.Role.STILL) vm.viewing = f else play = clip
                            },
                            onEdit = { vm.viewing = vm.projectFile(clip.file) },
                            onDragStart = { localX ->
                                // Only the pieces of the row are carried: a cutaway is pinned to the base's time (P3).
                                if (clip.atMs == null && p.sequence.indexOf(clip) >= 0) {
                                    lifted = clip
                                    heldFrom = i
                                    stickPx = 0f
                                    // Where the middle of the strip stands now: the landing place is counted from it,
                                    // so letting go without moving the stick leaves the piece where it was.
                                    baseTarget = centerSlot(listState, pitchPx, padPx, stripW.toFloat(), p.clips.size)
                                    targetArray = i
                                    val scrolled = listState.firstVisibleItemIndex * pitchPx + listState.firstVisibleItemScrollOffset
                                    heldX = padPx + i * pitchPx + localX - scrolled - cardWpx / 2f
                                }
                            },
                            onDrag = { dx ->
                                // The card is a transmitter stick: where the finger is (not how it moves) drives the
                                // slide — the loop above does the moving.
                                if (lifted == clip) stickPx += dx
                            },
                            onDragEnd = {
                                if (lifted == clip) {
                                    val from = p.sequence.indexOf(clip)
                                    val to = p.clips.getOrNull(targetArray)?.let { p.sequence.indexOf(it) } ?: from
                                    if (from >= 0 && to >= 0 && to != from) vm.moveProjectClip(from, to)
                                    lifted = null
                                    targetArray = -1
                                }
                            },
                        )
                    }
                }
                if (lifted != null) {
                    // The point of insertion: the middle of the strip. It stays put; the timeline moves under it.
                    Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                lifted?.let { clip ->
                    // The card in the hand, half seen so the moving timeline shows through it.
                    HeldCard(vm, clip, vm.projectFile(clip.file), Modifier.offset { IntOffset(heldX.roundToInt(), 0) }.zIndex(2f))
                }
            }
        }
    }
}

/**
 * One piece of the project: its frame, how long it plays, whether the auto montage put it there, and its ✕. Tapping it
 * plays the piece in the preview; the pencil opens the file, where the piece itself is edited in the usual film strip
 * (the project picks the edit up at once, §7); a long press lifts the card for dragging it along the strip.
 */
@Composable
private fun ClipCard(
    vm: CameraController,
    p: Project,
    clip: ProjectClip,
    file: CameraFile?,
    stale: Boolean,
    missing: Boolean,
    width: Dp,
    alpha: Float,
    target: Boolean,
    playing: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    var askingLength by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, clip.file, clip.fromMs, clip.role) {
        value = file?.let {
            // A photo is not a video with a frame at a time: it has its own tile picture.
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs)
        }
    }
    Column(
        Modifier.width(width).graphicsLayer {
            // The slot a carried card came from keeps its place as a ghost.
            this.alpha = alpha
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0x22000000))
                .then(
                    when {
                        target -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        // What is playing right now wears a bright border: the strip itself moves along with it.
                        playing -> Modifier.border(3.dp, Color(0xFFFFC107), RoundedCornerShape(10.dp))
                        else -> Modifier
                    },
                )
                .clickable(enabled = file != null && alpha == 1f) { onTap() }
                .pointerInput(clip) {
                    // A long press takes the card: the finger then slides the whole timeline (see the strip's callers).
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at -> onDragStart(at.x) },
                        onDrag = { _, amount -> onDrag(amount.x) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Row(Modifier.align(Alignment.BottomStart).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (clip.role == ProjectClip.Role.STILL)
                        tr("%s с", "%s s").format("%.1f".format(clip.durationMs / 1000f).trimEnd('0').trimEnd('.'))
                    else formatDuration((clip.durationMs / 1000).toInt()),
                    Modifier
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        // A still has neither sound nor movement to play: its length is what its card is about, and
                        // tapping the length opens it (asked for on the phone, 07.11).
                        .then(if (clip.role == ProjectClip.Role.STILL) Modifier.clickable { askingLength = true } else Modifier),
                    color = Color.White,
                    fontSize = 11.sp,
                )
                if (clip.auto) {
                    Text(
                        tr("Авто", "Auto"),
                        Modifier.padding(start = 4.dp).background(Color(0xAA0B6E4F), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
            if (p.baseFile == clip.file) {
                Text("★", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.White, fontSize = 14.sp)
            }
            // The pencil on the left, the cross on the right: opposite corners, so neither is hit by accident.
            IconButton(onEdit, Modifier.align(Alignment.TopStart).size(28.dp)) {
                Icon(Icons.Filled.Edit, tr("Править куски", "Edit pieces"), tint = Color.White, modifier = Modifier.size(16.dp))
            }
            IconButton({ confirming = true }, Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(Icons.Filled.Close, tr("Убрать кусок", "Remove the clip"), tint = Color.White, modifier = Modifier.size(16.dp))
            }
            // The file was edited since (its piece is gone) or is not on the list any more: say so rather than play it.
            if (missing || stale) {
                Box(Modifier.fillMaxSize().background(Color(0xAA7F1D1D)), contentAlignment = Alignment.Center) {
                    Text(
                        if (missing) tr("Файла больше нет в списке", "The file is not on the list any more")
                        else tr("Кусок убран из файла", "The file no longer has this piece"),
                        Modifier.padding(8.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(file?.stem ?: clip.file, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // The ✕ asks first: a project is put together by hand and a stray tap must not take a piece out of it. The files
    // themselves are never touched — that is what the dialog says outright (asked for on the phone, 07.11).
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Filled.Close, null) },
            title = { Text(tr("Убрать кусок из проекта?", "Remove the clip from the project?")) },
            text = {
                Text(
                    tr(
                        "Из проекта уйдёт только этот кусок. Сам файл остаётся на камере и на телефоне — ничего не удаляется.",
                        "Only this piece leaves the project. The file itself stays on the camera and on the phone — " +
                            "nothing is deleted.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    { vm.removeProjectClip(clip); confirming = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("Убрать", "Remove")) }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    // A photo has neither sound nor movement: how long it stays in the project is set here, from its own card.
    if (askingLength) {
        var seconds by remember(clip) { mutableFloatStateOf((clip.durationMs / 1000f).coerceIn(1f, 10f)) }
        AlertDialog(
            onDismissRequest = { askingLength = false },
            icon = { Icon(Icons.Filled.Edit, null) },
            title = { Text(tr("Длительность показа фото", "On screen for")) },
            text = {
                Column {
                    Text(tr("%s с", "%s s").format("%.1f".format(seconds).trimEnd('0').trimEnd('.')))
                    Slider(
                        value = seconds,
                        onValueChange = { seconds = it },
                        valueRange = 1f..10f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton({ vm.setClipLength(clip, (seconds * 1000f).toLong()); askingLength = false }) {
                    Text(tr("Готово", "Done"))
                }
            },
            dismissButton = { TextButton({ askingLength = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** One piece as a media3 item: its file's URI, cut to its range. */
@OptIn(UnstableApi::class)
private fun mediaItem(vm: CameraController, clip: ProjectClip, file: CameraFile): MediaItem {
    val uri = if (file.isOnPhone) file.localUri ?: clip.file else vm.previewUrl(file)
    return MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.fromMs)
                .setEndPositionMs(clip.toMs)
                .build(),
        )
        .build()
}

/**
 * The project's own clock, not the player's: a playlist reports the position inside the item being played, so the
 * whole timeline is put together from its windows — the pieces' real lengths as the player sees them. Returns the
 * absolute position in the project and its total (0 while the playlist hasn't said).
 */
private fun playerClock(player: Player): Pair<Long, Long> {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return player.currentPosition.coerceAtLeast(0L) to 0L
    val window = Timeline.Window()
    var before = 0L
    var total = 0L
    for (i in 0 until timeline.windowCount) {
        timeline.getWindow(i, window)
        val ms = window.durationMs
        if (ms == C.TIME_UNSET) continue
        if (i < player.currentMediaItemIndex) before += ms
        total += ms
    }
    return (before + player.currentPosition.coerceAtLeast(0L)) to total
}

/** Seeks the playlist to an absolute time of the project's timeline: the window that holds it, then inside it. */
private fun seekAbsolute(player: Player, atMs: Long) {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return
    val window = Timeline.Window()
    var left = atMs.coerceAtLeast(0L)
    for (i in 0 until timeline.windowCount) {
        timeline.getWindow(i, window)
        val ms = window.durationMs
        if (ms == C.TIME_UNSET || left <= ms) {
            player.seekTo(i, left)
            return
        }
        left -= ms
    }
}

/**
 * The project's preview: the pieces in the order they will play, each cut to its own range. They are a media3 playlist
 * — the pieces one after another for now; the base with its cutaways needs the montage engine (P3). A camera file plays
 * its light copy over Wi-Fi and a phone copy plays itself — the same sources the viewer uses.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ProjectPreview(
    vm: CameraController,
    clips: List<ProjectClip>,
    play: ProjectClip?,
    onPlayed: () -> Unit,
    onPlaying: (ProjectClip?) -> Unit,
) {
    val context = LocalContext.current
    // A piece whose file isn't around is left out of the playlist — and out of the numbering the cards seek by.
    val items = clips.mapNotNull { clip -> vm.projectFile(clip.file)?.let { file -> clip to mediaItem(vm, clip, file) } }
    val signature = items.joinToString("|") { "${it.first.file}:${it.first.fromMs}-${it.first.toMs}" }
    // The camera's own data source (its Wi-Fi is bound to this app) under the stock one: content and file URIs keep
    // going to the phone's own readers.
    val factory = remember(vm.conn) {
        val camera = vm.previewSource()
        if (camera == null) DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        else DefaultMediaSourceFactory(DefaultDataSource.Factory(context, camera))
    }
    val player = remember(factory) { ExoPlayer.Builder(context, factory).build().apply { playWhenReady = false } }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val totalMs = clips.sumOf { it.durationMs }
    // The clock of the whole timeline: absolute milliseconds, and its total (the pieces' own lengths once known).
    var shown by remember { mutableLongStateOf(0L) }
    var shownTotal by remember { mutableLongStateOf(totalMs) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // The playlist follows the pieces: one item per piece, cut at its range. DefaultMediaSourceFactory honours the
    // clipping — a progressive source would play the whole file.
    LaunchedEffect(signature, factory) {
        // Pieces changing under the preview (an edit in the film strip re-cuts them) must not lose the place.
        val at = player.currentPosition.coerceAtLeast(0L)
        player.setMediaItems(items.map { it.second })
        player.prepare()
        if (at > 0L) player.seekTo(minOf(at, maxOf(totalMs, 1L)))
    }
    // The clock follows the player whether or not it plays, and it never counts only the item being played: the strip
    // shows the whole timeline (a seek on a pause used to snap back to the old spot too).
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) {
                val (at, whole) = playerClock(player)
                shown = at
                shownTotal = if (whole > 0L) whole else totalMs
            }
            // Told to the screen: its card is marked in the strip and the strip follows it.
            onPlaying(items.getOrNull(player.currentMediaItemIndex)?.first)
            delay(250)
        }
    }
    // A card tapped: the preview jumps to that piece and plays it (a photo never reaches the playlist).
    LaunchedEffect(play, signature) {
        val at = items.indexOfFirst { it.first == play }
        if (at >= 0) {
            player.seekTo(at, 0L)
            player.play()
        }
        if (play != null) onPlayed()
    }

    Column(Modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (playing) player.pause() else player.play() }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) tr("Пауза", "Pause") else tr("Играть", "Play"),
                )
            }
            Slider(
                value = dragging ?: shown.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { ms ->
                        seekAbsolute(player, ms.toLong())
                        shown = ms.toLong()
                    }
                    dragging = null
                },
                valueRange = 0f..maxOf(shownTotal, 1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration((shown / 1000).toInt()) + " / " + formatDuration((shownTotal / 1000).toInt()),
                Modifier.padding(start = 8.dp),
                fontSize = 12.sp,
            )
        }
    }
}