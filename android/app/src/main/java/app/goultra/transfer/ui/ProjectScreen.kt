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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
private fun HeldCard(
    vm: CameraController,
    clip: ProjectClip,
    file: CameraFile?,
    modifier: Modifier,
    alpha: Float = 0.72f,
    scale: Float = 1.06f,
) {
    val thumb by produceState<ImageBitmap?>(null, clip.file, clip.fromMs, clip.role) {
        value = file?.let {
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs)
        }
    }
    // A little bigger than a card of the strip: it is held under the finger, where a thumbnail of 66 dp would be lost.
    Column(modifier.width(CARD_W * 1.9f).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC000000))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                formatDuration((clip.durationMs / 1000).toInt()),
                Modifier.align(Alignment.BottomStart).padding(3.dp)
                    .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** A card on its way home: where it was let go of, and where it lands (the slot it was carried to). */
private data class Landing(val clip: ProjectClip, val fromX: Float, val fromY: Float, val toX: Float)

/** The stick's lean of the point of insertion, and how far it may go (a share of the strip's width). */
private const val LEAN = 0.3f
private const val LEAN_MAX = 0.15f

/** How much of the way left to the place the timeline covers each frame: a glide, not a hop. */
private const val GLIDE = 0.22f

/** The drag loop's step, ms (and its share of a second, for the stick's speed). */
private const val TICK_MS = 16L

/** The lean of the point of insertion: a share of the finger's travel, kept inside a share of the strip. */
private fun leanOf(stickPx: Float, stripW: Int): Float =
    (stickPx * LEAN).coerceIn(-stripW * LEAN_MAX, stripW * LEAN_MAX)

/** One card of the strip: its width and the room between two of them (a drag counts places by that distance). */
private val CARD_W = 66.dp
private val CARD_GAP = 6.dp

/** How much the cards recede while one is carried: the strip reads the same, only a little further away. */
private const val DRAG_SCALE = 0.85f

/**
 * The stick's speed in px/s: nothing inside the middle, then the further the finger is from where the card was taken
 * the faster the timeline slides, capped so a long move stays controllable — a transmitter stick, not a drag.
 */
internal fun stickSpeed(offsetPx: Float): Float {
    val dead = 24f
    val full = 150f
    val capped = 900f
    val away = abs(offsetPx)
    if (away <= dead) return 0f
    val ramp = ((away - dead) / (full - dead)).coerceIn(0f, 1f)
    return ramp * capped * (if (offsetPx > 0f) 1f else -1f)
}

/**
 * The slot nearest [atMiddle] on the strip's own grid: the arithmetic of the drag, kept pure so it can be tested
 * without a screen (a wrong sum here threw the carried card about, so it is not guessed at any more).
 */
internal fun slotAt(atMiddle: Float, pitch: Float, padPx: Float, count: Int): Int =
    if (count <= 0 || pitch <= 0f) 0 else ((atMiddle - padPx) / pitch).roundToInt().coerceIn(0, count - 1)
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
    // Where the carried card stands among the others (the slot it opens), and where that slot was when it was taken:
    // the slot moves with the stick, counted from where it started, so letting go without moving leaves it in place.
    var carriedIdx by remember(p.id) { mutableIntStateOf(-1) }
    // Where the slot stands on screen while a card is carried (px in the strip): where it was taken, kept between the
    // middle halves so it never leaves the eye. The finger moves the place; the timeline follows it.
    var markerX by remember(p.id) { mutableFloatStateOf(0f) }
    // How many slots the place has moved, as a fraction: the stick's speed drives it, so a long move stays a glide.
    var slotsFloat by remember(p.id) { mutableFloatStateOf(0f) }
    var targetArray by remember(p.id) { mutableIntStateOf(0) }
    var stickPx by remember(p.id) { mutableFloatStateOf(0f) }
    var stickY by remember(p.id) { mutableFloatStateOf(0f) }
    var heldX by remember(p.id) { mutableFloatStateOf(0f) }
    // Where the drawing is looked at while a card is carried: the point it was taken from, so it does not slide away
    // from under the finger as the strip gathers.
    var zoomAt by remember(p.id) { mutableFloatStateOf(0f) }
    var landing by remember(p.id) { mutableStateOf<Landing?>(null) }
    // The card flying into its place when the finger lets go: down to the strip, smaller, giving way to the card
    // standing there — the eye follows it in, and by the time the timeline has grown back it is already in its place.
    val landGo = remember { Animatable(0f) }
    LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        landGo.snapTo(0f)
        landGo.animateTo(1f, tween(260))
        landing = null
    }
    var stripW by remember(p.id) { mutableIntStateOf(0) }
    var playingNow by remember(p.id) { mutableStateOf<ProjectClip?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The cards are all one width and the strip is padded so any slot can sit under its middle; while a card is
    // carried they lay themselves out smaller, so five or six read at once and the eye keeps the timeline.
    // The cards keep their size in the layout — only the drawing gets smaller while a card is carried — so nothing
    // moves under the eye when one is taken (that jump was the layout being re-made). The strip is padded so any slot
    // can sit under its middle.
    val density = LocalDensity.current
    val pitchPx = with(density) { (CARD_W + CARD_GAP).toPx() }
    val padNow = with(density) { maxOf(0.dp, (stripW.toDp() - CARD_W) / 2) }
    val padPx = with(density) { padNow.toPx() }
    val cardWpx = with(density) { CARD_W.toPx() }
    // The eye's distance while a card is carried: the cards gather around the middle of the strip, five or six of them
    // reading at once. The drawing follows this; the layout never does.
    val zoom by animateFloatAsState(if (lifted != null) DRAG_SCALE else 1f, tween(220))
    // The stick's own loop: near the middle nothing happens, further out the timeline slides under the card in the
    // hand; the point of insertion is the slot at the middle of the strip, so it never leaves the eye.
    // The point of insertion leans the way the finger goes (a little), while the timeline slides the other way — the
    // eye then reads both: where it goes and how far it has come. The stick's own loop does the moving (16 ms a tick).
    val markerShift = leanOf(stickPx, stripW)
    // While a card is carried the strip shows the others with an empty slot for it at the place it will land: the slot
    // moves with the stick and the cards part as it comes (asked for on the phone, 07.11).
    val others = remember(p.clips, carriedIdx, lifted) {
        if (lifted == null) p.clips.mapIndexed { i, c -> i to c }
        else p.clips.withIndex().filter { it.index != carriedIdx }.map { it.index to it.value }
    }
    // The strip while a card is carried: the others in order, with an empty slot at the place it will land. The slot
    // keeps the carried card's own index, so its key in the list does not change — an item rebuilt under the finger
    // would lose the very gesture that is carrying it.
    val display: List<Pair<Int, ProjectClip?>> = remember(others, targetArray, lifted, carriedIdx) {
        buildList {
            if (lifted == null) {
                others.forEach { add(it) }
            } else {
                others.forEachIndexed { k, entry ->
                    if (k == targetArray) add(carriedIdx to null)
                    add(entry)
                }
                if (targetArray >= others.size) add(carriedIdx to null)
            }
        }
    }
    LaunchedEffect(lifted, pitchPx, padPx, display.size) {
        while (lifted != null) {
            val speed = stickSpeed(stickPx)
            // The finger moves the place of insertion at the stick's speed; the timeline then follows, so the place
            // never leaves the eye and always stands where the card will land (a sum per card, or a scroll that ran
            // on its own, threw the card about and even off the screen).
            if (speed != 0f) slotsFloat += speed * (TICK_MS / 1000f) / pitchPx
            val shift = leanOf(stickPx, stripW)
            targetArray = (carriedIdx + slotsFloat.roundToInt()).coerceIn(0, others.size)
            val want = padPx + targetArray * pitchPx - (markerX + shift)
            val now = listState.firstVisibleItemIndex * pitchPx + listState.firstVisibleItemScrollOffset
            // Toward it (a part of what is left each frame): the timeline glides to the place instead of hopping, and
            // the place itself follows the finger smoothly because its slots are counted as a fraction.
            if (want != now) listState.scrollBy((want - now) * GLIDE)
            delay(TICK_MS)
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
        if (playable.isNotEmpty()) {
            ProjectPreview(
                vm, playable, play,
                onPlayed = { play = null },
                onPlaying = { playingNow = it },
                // A card in the hand means the preview stands still: its own moving about got in the way of the drag.
                carrying = lifted != null,
            )
        }
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
            Box(
                Modifier.fillMaxWidth()
                    // The strip's own height, not the overlays': the card in the hand is larger than a card of the
                    // strip, and letting it size this box moved everything under the finger at the take.
                    .height(CARD_W / 1.5f + 2.dp)
                    .onSizeChanged { stripW = it.width },
            ) {
                LazyRow(
                    state = listState,
                    // The whole strip draws smaller toward the point of insertion while a card is carried: one
                    // transform for all of it, so nothing can shift between the cards as the slot passes them (a sum
                    // per card ran away exactly when the slot moved). The layout is untouched: there is nothing to jump.
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        transformOrigin = TransformOrigin(
                            if (stripW > 0) (if (zoomAt > 0f) zoomAt else stripW / 2f) / stripW else 0.5f,
                            0.5f,
                        )
                    },
                    contentPadding = PaddingValues(horizontal = padNow),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    items(display, key = { (i, _) -> "$i" }) { (i, clip) ->
                        // The gesture lives on this wrapper, not on the card: a carried card leaves the strip (its
                        // place becomes the empty slot), so a gesture bound to the card itself was cancelled the moment
                        // the drag started. The wrapper keeps the item's identity; the live values are read right here.
                        val shown by rememberUpdatedState(clip)
                        val project by rememberUpdatedState(p)
                        Column(
                            Modifier.width(CARD_W)
                                .clickable(enabled = clip != null) {
                                    val c = shown ?: return@clickable
                                    if (lifted != null) return@clickable
                                    val f = vm.projectFile(c.file)
                                    if (c.role == ProjectClip.Role.STILL) vm.viewing = f else play = c
                                }
                                .pointerInput(Unit) {
                                    fun pitchNow() = with(density) { (CARD_W + CARD_GAP).toPx() }
                                    fun padNow() = with(density) { maxOf(0.dp, (stripW.toDp() - CARD_W) / 2).toPx() }

                                    fun drop() {
                                        val c = lifted ?: return
                                        val pr = project
                                        val pitch = pitchNow()
                                        val othersNow = pr.clips.withIndex().filter { it.index != carriedIdx }.map { it.value }
                                        val scrolled = listState.firstVisibleItemIndex * pitch + listState.firstVisibleItemScrollOffset
                                        val from = pr.sequence.indexOf(c)
                                        val to = if (targetArray >= othersNow.size) pr.sequence.size - 1
                                        else othersNow.getOrNull(targetArray)?.let { pr.sequence.indexOf(it) } ?: from
                                        landing = Landing(c, heldX, stickY, padNow() + targetArray * pitch - scrolled)
                                        if (from >= 0 && to >= 0 && to != from) vm.moveProjectClip(from, to)
                                        lifted = null
                                        // The flight's target is the slot's own place: nothing to log.
                                    }

                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            val c = shown
                                            val pr = project
                                            if (c != null && lifted == null && pr.sequence.indexOf(c) >= 0) {
                                                val pitch = pitchNow()
                                                val pad = padNow()
                                                lifted = c
                                                carriedIdx = i
                                                stickPx = 0f
                                                stickY = 0f
                                                // A follow that is under way must not fight the stick.
                                                scope.launch { listState.scrollBy(0f) }
                                                targetArray = i
                                                val scrolled = listState.firstVisibleItemIndex * pitch + listState.firstVisibleItemScrollOffset
                                                heldX = pad + i * pitch + at.x - scrolled - with(density) { CARD_W.toPx() } / 2f
                                                val cardW = with(density) { CARD_W.toPx() }
                                                zoomAt = pad + i * pitch - scrolled + cardW / 2f
                                                // The place of insertion starts where the card was taken: nothing moves under the
                                                // finger at the take, and the timeline follows the place from there.
                                                markerX = (pad + i * pitch - scrolled + cardW / 2f).coerceIn(stripW * 0.25f, stripW * 0.75f)
                                                slotsFloat = 0f
                                                // Nothing to log: the take is the state above.
                                            }
                                        },
                                        onDrag = { _, amount ->
                                            if (lifted != null) {
                                                // The strip is drawn scaled: the finger's own movement is the local one
                                                // times that scale, so the stick keeps the feel it had.
                                                stickPx += amount.x * zoom
                                                stickY = (stickY + amount.y * zoom).coerceIn(-320f, 320f)
                                            }
                                        },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                    )
                                },
                        ) {
                            if (clip == null) {
                                // The empty slot: the place the carried card will land in, parting the cards as it comes.
                                Box(
                                    Modifier.fillMaxWidth().height(96.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                                )
                            } else {
                                // «The file is not on the list any more» is only said when the list is complete: with
                                // the camera away a file it holds is simply out of reach, not gone.
                                ClipCard(
                                    vm, p, clip, vm.projectFile(clip.file),
                                    stale = clip in stale,
                                    missing = vm.projectFile(clip.file) == null && vm.conn == ConnState.Connected,
                                    width = CARD_W,
                                    playing = clip == playingNow,
                                    onEdit = { vm.viewing = vm.projectFile(clip.file) },
                                )
                            }
                        }
                    }
                }
                if (lifted != null) {
                    // The point of insertion: standing where the card was taken (leaning a little with the stick), with
                    // the timeline following it, so it never leaves the eye.
                    Box(
                        Modifier.align(Alignment.Center)
                            .offset { IntOffset((markerX + markerShift - stripW / 2f).roundToInt(), 0) }
                            .width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary),
                    )
                }
                lifted?.let { clip ->
                    // The card in the hand, half seen so the moving timeline shows through it; the finger may carry it
                    // up or down as well, out of the way.
                    HeldCard(
                        vm, clip, vm.projectFile(clip.file),
                        Modifier.offset { IntOffset(heldX.roundToInt(), stickY.roundToInt()) }.zIndex(2f),
                    )
                }
                landing?.let { home ->
                    // Coming down: the card goes to the slot it was carried to, shrinking to its size and fading as
                    // the card standing there takes over.
                    val x = home.fromX + (home.toX - home.fromX) * landGo.value
                    val y = home.fromY * (1f - landGo.value)
                    HeldCard(
                        vm, home.clip, vm.projectFile(home.clip.file),
                        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.zIndex(3f),
                        alpha = 0.72f * (1f - landGo.value),
                        scale = 1.06f - 0.06f * landGo.value,
                    )
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
    playing: Boolean,
    onEdit: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    var askingLength by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, clip.file, clip.fromMs, clip.role) {
        value = file?.let {
            // A photo is not a video with a frame at a time: it has its own tile picture.
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs)
        }
    }
    Column(Modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(6.dp))
                .background(Color(0x22000000))
                // What is playing right now wears a bright border: the strip itself moves along with it.
                .then(if (playing) Modifier.border(2.dp, Color(0xFFFFC107), RoundedCornerShape(6.dp)) else Modifier),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Row(Modifier.align(Alignment.BottomStart).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (clip.role == ProjectClip.Role.STILL)
                        // A dot, not the phone's comma: the number is the one the slider sets, in every language.
                        tr("%s с", "%s s").format("%.1f".format(java.util.Locale.US, clip.durationMs / 1000f).trimEnd('0').trimEnd('.'))
                    else formatDuration((clip.durationMs / 1000).toInt()),
                    Modifier
                        .background(Color(0x99000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        // A still has neither sound nor movement to play: its length is what its card is about, and
                        // tapping the length opens it (asked for on the phone, 07.11).
                        .then(if (clip.role == ProjectClip.Role.STILL) Modifier.clickable { askingLength = true } else Modifier),
                    color = Color.White,
                    fontSize = 9.sp,
                )
                if (clip.auto) {
                    Text(
                        tr("Авто", "Auto"),
                        Modifier.padding(start = 3.dp).background(Color(0xAA0B6E4F), RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                    )
                }
            }
            if (p.baseFile == clip.file) {
                Text("★", Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.White, fontSize = 11.sp)
            }
            // The pencil on the left, the cross on the right: opposite corners, so neither is hit by accident.
            IconButton(onEdit, Modifier.align(Alignment.TopStart).size(20.dp)) {
                Icon(Icons.Filled.Edit, tr("Править куски", "Edit pieces"), tint = Color.White, modifier = Modifier.size(12.dp))
            }
            IconButton({ confirming = true }, Modifier.align(Alignment.TopEnd).size(20.dp)) {
                Icon(Icons.Filled.Close, tr("Убрать кусок", "Remove the clip"), tint = Color.White, modifier = Modifier.size(12.dp))
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
        Spacer(Modifier.height(2.dp))
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
                    Text(tr("%s с", "%s s").format("%.1f".format(java.util.Locale.US, seconds).trimEnd('0').trimEnd('.')))
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
@androidx.annotation.OptIn(UnstableApi::class)
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
    // -1 stands for «nothing to say» (a playlist being put together): the pointer then stays where it was.
    if (timeline.isEmpty) return -1L to 0L
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
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ProjectPreview(
    vm: CameraController,
    clips: List<ProjectClip>,
    play: ProjectClip?,
    onPlayed: () -> Unit,
    onPlaying: (ProjectClip?) -> Unit,
    carrying: Boolean,
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
    // A card in the hand: the preview stands still (its own playing got in the way of carrying a piece about).
    LaunchedEffect(carrying) {
        if (carrying) player.pause()
    }
    // The playlist follows the pieces: one item per piece, cut at its range. DefaultMediaSourceFactory honours the
    // clipping — a progressive source would play the whole file.
    LaunchedEffect(signature, factory) {
        // A rebuild keeps the piece and the place: an edit re-cuts it, a drag only moves it along the order — the
        // absolute time means nothing after a move (it used to drag the clock and the length about with it).
        val wasClip = items.getOrNull(player.currentMediaItemIndex)?.first
        val wasAt = player.currentPosition.coerceAtLeast(0L)
        player.setMediaItems(items.map { it.second })
        player.prepare()
        val at = items.indexOfFirst { it.first == wasClip }
        if (at >= 0) player.seekTo(at, minOf(wasAt, items[at].first.durationMs))
        else if (wasAt > 0L) player.seekTo(minOf(wasAt, maxOf(totalMs, 1L)))
    }
    // The clock follows the player whether or not it plays, and it never counts only the item being played: the strip
    // shows the whole timeline (a seek on a pause used to snap back to the old spot too).
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) {
                val (at, whole) = playerClock(player)
                if (at >= 0L) shown = at
                if (whole > 0L) shownTotal = whole
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