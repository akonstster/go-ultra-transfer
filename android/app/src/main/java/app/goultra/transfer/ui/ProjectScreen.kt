package app.goultra.transfer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder
import androidx.media3.exoplayer.image.ImageDecoderException
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip

/** The files' pieces as they stand ([all]), and the same for the files edited on the phone ([edited]). */
private data class FilePieces(
    val all: Map<String, List<Highlight>>,
    val edited: Map<String, List<Highlight>>,
)

/**
 * The strip's geometry at a scale [k] (1 = as it is, [CARRY_SCALE] = a card carried): where a slot's middle is drawn
 * and which slot is under a point. Slots are counted in cards, so a card's place does not change when the strip
 * gathers — only where it is drawn does. Pure, so the drag's arithmetic is checked rather than guessed at.
 */
internal class StripGeometry(
    val cardW: Float,
    val gap: Float,
    val edge: Float,
    val stripW: Float,
    val count: Int,
    val k: Float,
) {
    val w: Float get() = cardW * k
    val pitch: Float get() = (cardW + gap) * k

    /** The scroll at which the last card's far side meets the strip's end. */
    val maxScroll: Float get() = if (count <= 0) 0f else maxOf(0f, 2f * edge + (count - 1) * pitch + w - stripW)

    /** Where the middle of [slot] (fractional while it moves) is drawn on screen. */
    fun center(slot: Float, scroll: Float): Float = edge + slot * pitch + w / 2f - scroll

    /** The slot whose middle is drawn at [x] — fractional, the inverse of [center]. */
    fun slotAt(x: Float, scroll: Float): Float = if (pitch <= 0f) 0f else (x + scroll - edge - w / 2f) / pitch

    /**
     * The scroll that puts the middle of [slot] at [x], kept inside the strip's ends — give or take [slack]: a gathered
     * strip may stand off its ends, so the cards close in about the carried one instead of sliding under it.
     */
    fun scrollFor(slot: Float, x: Float, slack: Float = 0f): Float =
        (edge + slot * pitch + w / 2f - x).coerceIn(-slack, maxScroll + slack)
}

/** How fast the strip slides when the carried card is at [x]: nothing in the middle, easing in toward either end. */
internal fun edgeSpeed(x: Float, stripW: Float): Float {
    if (stripW <= 0f) return 0f
    val zone = stripW * EDGE_ZONE
    val v = when {
        x < zone -> -(zone - x) / zone
        x > stripW - zone -> (x - (stripW - zone)) / zone
        else -> 0f
    }.coerceIn(-1f, 1f)
    return v * abs(v) * EDGE_SPEED
}

/**
 * The place in [Project.sequence] a card goes to when it is let go of before [others]`[insertAt]` ([others] is the
 * strip without it): the clips of the sequence in front of that point. Cutaways keep their moment and don't count.
 */
internal fun sequenceIndexFor(others: List<ProjectClip>, insertAt: Int): Int =
    others.take(insertAt.coerceIn(0, others.size)).count { it.atMs == null }

/**
 * A project's own screen: the pieces it is made of, in the order they will play (see `memory-bank/projects-plan.md`).
 * A long press lifts a card: it follows the finger, the others gather smaller so more of them are seen and part for
 * it where it would land; let go, it settles into that place. The file behind a piece is edited in the usual film
 * strip — the card's pencil opens it.
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

    // Cards are told apart by their place in [Project.clips], not by equality: the same piece may be in twice.
    var play by remember(p.id) { mutableStateOf<Int?>(null) }
    var playingNow by remember(p.id) { mutableStateOf<Int?>(null) }

    val density = LocalDensity.current
    val cardWpx = with(density) { CARD_W.toPx() }
    val gapPx = with(density) { CARD_GAP.toPx() }
    val edgePx = with(density) { STRIP_EDGE.toPx() }
    val risePx = with(density) { LIFT_RISE.toPx() }
    val maxYpx = with(density) { LIFT_MAX_Y.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val count = p.clips.size

    var stripW by remember { mutableIntStateOf(0) }
    // The strip's own scroll, px: the strip is drawn by hand, so every card's place is one sum (see [StripGeometry]).
    var scroll by remember(p.id) { mutableFloatStateOf(0f) }
    // 0 = the strip as it is, 1 = a card carried (the others small); and how far the carried card is lifted.
    val zoom = remember(p.id) { Animatable(0f) }
    val lift = remember(p.id) { Animatable(0f) }
    val project by rememberUpdatedState(p)
    // A card's identity in the strip: the clip and which of its equals it is, so a card keeps its own movement across a
    // reorder instead of taking over its neighbour's.
    val keys = remember(p.clips) {
        val seen = HashMap<ProjectClip, Int>()
        p.clips.map { c -> c to seen.merge(c, 1, Int::plus)!! }
    }
    // The gesture and the drag's loop outlive a composition: they read the cards as they are now. A list captured once
    // lifted the card that used to stand there after a reorder, and the real one rode in the empty place instead.
    val keysNow by rememberUpdatedState(keys)
    val countNow by rememberUpdatedState(count)
    fun geometry() = StripGeometry(cardWpx, gapPx, edgePx, stripW.toFloat(), countNow, 1f - (1f - CARRY_SCALE) * zoom.value)
    // Where each card is drawn, in slots: a spring that follows its place, so the cards part and close organically.
    val slots = remember(p.id) { HashMap<Any, Animatable<Float, AnimationVector1D>>() }

    // The carried card (held under the finger) and the lifted one (held, or still settling into its place).
    var heldKey by remember(p.id) { mutableStateOf<Any?>(null) }
    var liftKey by remember(p.id) { mutableStateOf<Any?>(null) }
    var heldIdx by remember(p.id) { mutableIntStateOf(-1) }
    // The place among the others where the carried card would land.
    var target by remember(p.id) { mutableIntStateOf(0) }
    var fingerX by remember(p.id) { mutableFloatStateOf(0f) }
    var fingerY by remember(p.id) { mutableFloatStateOf(0f) }
    var downY by remember(p.id) { mutableFloatStateOf(0f) }
    // Where the finger took the card, from its middle at full size: the card does not jump to centre on the finger.
    var grab by remember(p.id) { mutableFloatStateOf(0f) }
    // The strip gathers and spreads about this slot standing at this point on screen; the slide at the ends moves it.
    var anchorSlot by remember(p.id) { mutableFloatStateOf(0f) }
    var anchorX by remember(p.id) { mutableFloatStateOf(0f) }
    // A card carried or still settling: the strip is driven by the drag then, not by the finger's scroll.
    var settling by remember(p.id) { mutableStateOf(false) }
    val landY = remember(p.id) { Animatable(0f) }
    val gapAt = remember(p.id) { Animatable(0f) }
    // The strip gathers only once the card is swept sideways fast: held still or moved slowly, nothing changes under it.
    var gathered by remember(p.id) { mutableStateOf(false) }
    val tracker = remember { VelocityTracker() }
    val gatherPx = with(density) { GATHER_SPEED.toPx() }

    fun scaleOf(lifted: Boolean): Float {
        val k = 1f - (1f - CARRY_SCALE) * zoom.value
        return if (lifted) k + (LIFT_SCALE - k) * lift.value else k
    }

    fun heldCenter(): Float = fingerX - grab * scaleOf(true)

    /** The slot a card stands in: its own index, or — while one is carried — its place among the others, parted. */
    fun baseSlot(i: Int): Int {
        if (heldKey == null || heldIdx < 0) return i
        if (i == heldIdx) return target
        val j = if (i > heldIdx) i - 1 else i
        return if (j >= target) j + 1 else j
    }

    val scrollState = rememberScrollableState { delta ->
        val old = scroll
        scroll = (scroll - delta).coerceIn(0f, geometry().maxScroll)
        old - scroll
    }
    LaunchedEffect(count, stripW) {
        if (!settling) scroll = scroll.coerceIn(0f, geometry().maxScroll)
    }
    // The drag's own loop, frame by frame: the strip gathers about the place it was taken from, slides when the card
    // is near an end, and the place of landing is read from where the card is drawn.
    LaunchedEffect(settling) {
        if (!settling) return@LaunchedEffect
        var last = 0L
        while (heldKey != null || zoom.isRunning || lift.isRunning || zoom.value > 0f) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1_000_000f).coerceIn(0f, TICK_MAX_MS)
            last = now
            val g = geometry()
            val last = maxOf(0, countNow - 1).toFloat()
            if (heldKey != null && dt > 0f && g.pitch > 0f) {
                anchorSlot = (anchorSlot + edgeSpeed(fingerX, g.stripW) * dt / 1000f / g.pitch).coerceIn(0f, last)
            }
            // Gathered, the strip may stand off its ends (as much as it has gathered): the cards close in about the
            // anchor instead of the whole strip sliding to an end under the card.
            scroll = g.scrollFor(anchorSlot, anchorX, g.stripW * zoom.value)
            // Held at an end, the anchor is where the end lets it be: the slide does not pile up beyond it.
            anchorSlot = g.slotAt(anchorX, scroll)
            if (heldKey != null) target = g.slotAt(heldCenter(), scroll).roundToInt().coerceIn(0, last.toInt())
        }
        settling = false
    }
    // The empty place follows the landing point with the same spring as the cards, so it never runs ahead of them.
    LaunchedEffect(target, heldKey) {
        if (heldKey != null) gapAt.animateTo(target.toFloat(), PART_SPRING)
    }
    // While something plays, its card is marked and kept in view (asked for on the phone, 07.11).
    LaunchedEffect(playingNow, settling, stripW) {
        if (settling || stripW <= 0) return@LaunchedEffect
        val at = playingNow ?: -1
        if (at in project.clips.indices) scrollState.animateScrollBy(scroll - geometry().scrollFor(at.toFloat(), stripW / 2f))
    }
    val visible by remember(count, cardWpx, gapPx, edgePx) {
        derivedStateOf {
            val g = geometry()
            (floor(g.slotAt(0f, scroll)).toInt() - 2)..(ceil(g.slotAt(g.stripW, scroll)).toInt() + 2)
        }
    }

    fun take(x: Float, y: Float) {
        val cards = keysNow
        if (heldKey != null || settling || cards.isEmpty()) return
        val g = geometry()
        val i = g.slotAt(x, scroll).roundToInt()
        if (i !in cards.indices || abs(x - g.center(i.toFloat(), scroll)) > g.w / 2f) return
        // Cutaways are pinned to the base's time (P3): they are not carried along the order.
        if (project.clips.getOrNull(i)?.atMs != null) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        heldIdx = i
        target = i
        heldKey = cards[i]
        liftKey = cards[i]
        gathered = false
        tracker.resetTracking()
        fingerX = x
        fingerY = y
        downY = y
        grab = x - g.center(i.toFloat(), scroll)
        anchorX = x
        anchorSlot = g.slotAt(x, scroll)
        settling = true
        scope.launch { scrollState.stopScroll() }
        scope.launch { gapAt.snapTo(i.toFloat()) }
        scope.launch { lift.animateTo(1f, LAND_SPRING) }
    }

    /** A swift sideways sweep gathers the strip about where the card is now, so the card stays over its own place. */
    fun sweep(timeMs: Long, at: Offset) {
        if (heldKey == null || gathered) return
        tracker.addPosition(timeMs, at)
        if (abs(tracker.calculateVelocity().x) < gatherPx) return
        gathered = true
        anchorX = heldCenter()
        anchorSlot = geometry().slotAt(anchorX, scroll)
        scope.launch { zoom.animateTo(1f, ZOOM_SPRING) }
    }

    fun drop() {
        val key = heldKey ?: return
        val g = geometry()
        val from = heldIdx
        val to = target
        val landFrom = g.slotAt(heldCenter(), scroll)
        val y = ((fingerY - downY) * LIFT_FOLLOW_Y).coerceIn(-maxYpx, maxYpx)
        scope.launch {
            // The card settles from where it was let go of: its spring starts there, not at its old place.
            slots[key]?.snapTo(landFrom)
            landY.snapTo(y)
            // The strip spreads back about the place of landing, so that place stays where the eye is.
            anchorSlot = to.toFloat()
            anchorX = g.center(to.toFloat(), scroll)
            val pr = project
            val clip = pr.clips.getOrNull(from)
            heldKey = null
            heldIdx = -1
            if (clip != null && to != from) {
                val a = pr.sequence.indexOf(clip)
                val b = sequenceIndexFor(pr.clips.filterIndexed { n, _ -> n != from }, to)
                if (a >= 0 && a != b) vm.moveProjectClip(a, b)
            }
            launch { zoom.animateTo(0f, ZOOM_SPRING) }
            launch { landY.animateTo(0f, LAND_SPRING) }
            lift.animateTo(0f, LAND_SPRING)
            if (liftKey == key) liftKey = null
        }
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
        // Every piece, photos included: a still plays as its own picture, held for its length (asked for on the phone,
        // 07.11); a piece out of reach plays as its card's frame, marked, so the project can be worked on without the
        // camera and plays for real once it is back.
        val pieces = p.clips.withIndex().toList()
        if (pieces.isNotEmpty()) {
            ProjectPreview(
                vm, pieces, play,
                onPlayed = { play = null },
                onPlaying = { playingNow = it },
                // A card in the hand means the preview stands still: its own moving about got in the way of the drag.
                carrying = heldKey != null,
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
            Box(
                Modifier.fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(CARD_W / 1.5f + 2.dp)
                    .onSizeChanged { stripW = it.width }
                    .scrollable(scrollState, Orientation.Horizontal, enabled = !settling)
                    // Inside the scroll: a long press wins over it, a plain swipe still scrolls the strip.
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at -> take(at.x, at.y) },
                            onDrag = { change, amount ->
                                if (heldKey != null) {
                                    change.consume()
                                    fingerX += amount.x
                                    fingerY += amount.y
                                    sweep(change.uptimeMillis, change.position)
                                }
                            },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        )
                    },
            ) {
                if (heldKey != null) {
                    // The empty place the card would land in: an outline the others part around.
                    Box(
                        Modifier
                            .offset { IntOffset((geometry().center(gapAt.value, scroll) - cardWpx / 2f).roundToInt(), 0) }
                            .graphicsLayer { scaleOf(false).let { scaleX = it; scaleY = it } }
                            .width(CARD_W).height(CARD_W / 1.5f)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                    )
                }
                p.clips.forEachIndexed { i, clip ->
                    val k = keys[i]
                    val base = baseSlot(i)
                    if (base !in visible && k != liftKey) return@forEachIndexed
                    key(k) {
                        val anim = slots.getOrPut(k) { Animatable(base.toFloat()) }
                        val held = k == heldKey
                        LaunchedEffect(anim, base, held) {
                            if (!held) anim.animateTo(base.toFloat(), PART_SPRING)
                        }
                        Box(
                            Modifier
                                // Read where the card is placed and drawn, never in the composition: the drag
                                // moves only the drawing, sixty or a hundred and twenty times a second.
                                .offset {
                                    val lifted = k == liftKey
                                    val x = if (k == heldKey) heldCenter() else geometry().center(anim.value, scroll)
                                    val y = when {
                                        k == heldKey -> ((fingerY - downY) * LIFT_FOLLOW_Y).coerceIn(-maxYpx, maxYpx)
                                        lifted -> landY.value
                                        else -> 0f
                                    } - (if (lifted) risePx * lift.value else 0f)
                                    IntOffset((x - cardWpx / 2f).roundToInt(), y.roundToInt())
                                }
                                .zIndex(if (k == liftKey) 1f else 0f)
                                .graphicsLayer {
                                    val lifted = k == liftKey
                                    val s = scaleOf(lifted)
                                    scaleX = s
                                    scaleY = s
                                    if (lifted) {
                                        shadowElevation = LIFT_SHADOW.toPx() * lift.value
                                        shape = RoundedCornerShape(6.dp)
                                    }
                                }
                                .width(CARD_W)
                                // A photo is played like a piece of video: the preview goes to it and plays on from
                                // there; the pencil is what opens the file (asked for on the phone, 07.11).
                                .clickable(enabled = !settling) { play = i },
                        ) {
                            // «The file is not on the list any more» is only said when the list is complete: with
                            // the camera away a file it holds is simply out of reach, not gone.
                            ClipCard(
                                // Out of reach (the camera away), the card stays as it was: its frame is kept on the phone.
                                vm, p, clip, vm.projectFile(clip.file) ?: vm.offlineFile(clip.file),
                                stale = clip in stale,
                                missing = vm.projectFile(clip.file) == null && vm.conn == ConnState.Connected,
                                width = CARD_W,
                                playing = i == playingNow,
                                onEdit = { vm.viewing = vm.projectFile(clip.file) },
                            )
                        }
                    }
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
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs, keep = true)
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
                    fontSize = 10.sp,
                )
                if (clip.auto) {
                    Text(
                        tr("Авто", "Auto"),
                        Modifier.padding(start = 3.dp).background(Color(0xAA0B6E4F), RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }
            }
            if (p.baseFile == clip.file) {
                Text("★", Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.White, fontSize = 13.sp)
            }
            // The pencil on the left, the cross on the right: opposite corners, so neither is hit by accident — and each
            // of them is a good third of a card, since a card of the strip is small and a finger is not (asked for on
            // the phone, 07.11: at 20 dp the pencil and the cross could not be hit at all).
            IconButton(onEdit, Modifier.align(Alignment.TopStart).padding(2.dp).size(34.dp).background(Color(0x66000000), CircleShape)) {
                Icon(Icons.Filled.Edit, tr("Править куски", "Edit pieces"), tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton({ confirming = true }, Modifier.align(Alignment.TopEnd).padding(2.dp).size(34.dp).background(Color(0x66000000), CircleShape)) {
                Icon(Icons.Filled.Close, tr("Убрать кусок", "Remove the clip"), tint = Color.White, modifier = Modifier.size(18.dp))
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

/**
 * One piece as a media3 item: its file's URI, cut to its range. A still has no range to cut — it is one picture, and
 * its length is how long the picture stands: the player holds it that long and moves on to the next piece.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun mediaItem(vm: CameraController, clip: ProjectClip, file: CameraFile): MediaItem {
    val uri = if (file.isOnPhone) file.localUri ?: clip.file else vm.previewUrl(file)
    val builder = MediaItem.Builder().setUri(uri)
    if (clip.role == ProjectClip.Role.STILL) {
        return builder.setImageDurationMs(clip.durationMs.coerceAtLeast(1L)).build()
    }
    return builder
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.fromMs)
                .setEndPositionMs(clip.toMs)
                .build(),
        )
        .build()
}

/**
 * The pieces' lengths as the playlist plays them: the player's own for the items it has prepared, the clip's range for
 * the rest. media3 knows a length only once an item is prepared (the current one and the next), so a clock built from
 * its windows alone grew as it played and a seek landed in the wrong piece.
 */
private fun lengthsOf(player: Player, clips: List<ProjectClip>): List<Long> {
    val timeline = player.currentTimeline
    val window = Timeline.Window()
    return clips.mapIndexed { i, clip ->
        val known = if (i < timeline.windowCount) timeline.getWindow(i, window).durationMs else C.TIME_UNSET
        if (known == C.TIME_UNSET || known <= 0L) clip.durationMs else known
    }
}

/** The project's clock at [posMs] into item [index]: everything before it plus the position in it. */
internal fun clockAt(lengths: List<Long>, index: Int, posMs: Long): Long =
    lengths.take(index.coerceIn(0, lengths.size)).sum() + posMs.coerceAtLeast(0L)

/** The item and the place in it that an absolute time of the project falls on (the end holds at the last item). */
internal fun seekPoint(lengths: List<Long>, atMs: Long): Pair<Int, Long> {
    if (lengths.isEmpty()) return 0 to 0L
    var left = atMs.coerceAtLeast(0L)
    for ((i, ms) in lengths.withIndex()) {
        if (left < ms || i == lengths.lastIndex) return i to left.coerceAtMost(ms)
        left -= ms
    }
    return lengths.lastIndex to 0L
}

/** The long side of a photo as the preview decodes it: more than a phone screen needs, little enough to keep in hand. */
private const val STILL_SIDE = 1280

/**
 * How many times a side has to be halved to fit [maxSide] — the same rule the viewer decodes photos by, so a photo is
 * never taken into memory whole (the phone's camera takes 12–50 MP, a screen asks for one or two).
 */
internal fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * A photo of the playlist, decoded small on purpose: media3's own decoder takes the picture whole, which a 50 MP photo
 * does not survive. Bounds first, then a sampled decode, both straight from the bytes the source has delivered.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun decodePhoto(data: ByteArray, length: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, length, bounds)
    val sample = sampleFor(bounds.outWidth, bounds.outHeight, STILL_SIDE)
    return BitmapFactory.decodeByteArray(data, 0, length, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw ImageDecoderException("not a picture: $length bytes, ${bounds.outWidth}×${bounds.outHeight}")
}

/**
 * Where the player puts the picture of a still. The player waits while it is not here (a still's length starts only
 * once its picture is ready), and the preview draws it over the black the video surface leaves.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class PhotoSink : ImageOutput {
    var shown by mutableStateOf<Bitmap?>(null)

    override fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap) {
        shown = bitmap
    }

    /** Another piece (or the end): the picture goes, so the next still never shows the last one's. */
    override fun onDisabled() {
        shown = null
    }
}

/**
 * One piece of the preview's playlist: its card ([at] — its place in the project's clips), its file, and whether that
 * file is out of reach right now ([offline]: then the piece plays as its card's frame, marked).
 */
private class Piece(val at: IndexedValue<ProjectClip>, val file: CameraFile, val offline: Boolean) {
    val clip: ProjectClip get() = at.value
}

/**
 * The project's preview: the pieces in the order they will play, each cut to its own range — a still is not cut, it is
 * one picture held for its length, and the player shows it (and waits for it, so its length never counts loading). They
 * are a media3 playlist: the pieces one after another for now; the base with its cutaways needs the montage engine
 * (P3). A camera file plays its light copy over Wi-Fi and a phone copy plays itself — the same sources the viewer uses.
 * A piece is known by its place in the project's clips ([clips] carries it), so the same piece put in twice is still
 * two cards.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ProjectPreview(
    vm: CameraController,
    clips: List<IndexedValue<ProjectClip>>,
    play: Int?,
    onPlayed: () -> Unit,
    onPlaying: (Int?) -> Unit,
    carrying: Boolean,
) {
    val context = LocalContext.current
    // Every piece with its file: the real one, or — out of reach — a stand-in played as its card's frame.
    val items = clips.map { e ->
        val real = vm.projectFile(e.value.file)
        Piece(e, real ?: vm.offlineFile(e.value.file), offline = real == null)
    }
    val signature = items.joinToString("|") { "${it.clip.file}:${it.clip.fromMs}-${it.clip.toMs}:${it.offline}" }
    // Read by the clock's loop, which outlives a composition: a list captured once named the wrong card after a change.
    val itemsNow by rememberUpdatedState(items)
    val onPlayingNow by rememberUpdatedState(onPlaying)
    // The camera's own data source (its Wi-Fi is bound to this app) under the stock one: content and file URIs keep
    // going to the phone's own readers. A photo is a plain JPEG by content, and the stock JPEG reader takes a JPEG for
    // a motion photo — the flag is what makes it a picture the playlist can show.
    val factory = remember(vm.conn) {
        val extractors = DefaultExtractorsFactory().setJpegExtractorFlags(JpegExtractor.FLAG_READ_IMAGE)
        val camera = vm.previewSource()
        if (camera == null) DefaultMediaSourceFactory(DefaultDataSource.Factory(context), extractors)
        else DefaultMediaSourceFactory(DefaultDataSource.Factory(context, camera), extractors)
    }
    // Where the picture of a still lands.
    val photo = remember { PhotoSink() }
    // The stock renderers, and an image renderer after them: a still is shown by the player itself, so the playlist
    // plays photos and pieces alike and one clock counts them both.
    val player = remember(factory, photo) {
        // In place of the stock image renderer, not after it: the track selector gives a picture to the first one
        // that takes it, so a renderer added at the end never saw a photo (the stock one decoded it whole, to nowhere).
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildImageRenderers(out: java.util.ArrayList<Renderer>) {
                out.add(ImageRenderer(BitmapFactoryImageDecoder.Factory { data, length -> decodePhoto(data, length) }, photo))
            }
        }
        ExoPlayer.Builder(context, factory)
            .setRenderersFactory(renderers)
            .build()
            .apply { playWhenReady = false }
    }
    // What the player holds right now: its indices mean this list, not the one just composed.
    val inPlayer = remember(player) { mutableListOf<Piece>() }
    // The same pieces in the same order, only their cards' places moved (a photo taken out before them): no rebuild,
    // but the cards they stand for are the new ones.
    SideEffect {
        if (inPlayer.map { it.clip to it.offline } == items.map { it.clip to it.offline }) {
            inPlayer.clear()
            inPlayer.addAll(items)
        }
    }
    // The piece and the place in it, kept across a new player (the camera coming or going makes one): the preview goes
    // on from there instead of starting over.
    var resume by remember { mutableStateOf<Pair<ProjectClip, Long>?>(null) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The piece the player is on: its picture is what the preview shows when that piece is a still.
    var current by remember(player) { mutableIntStateOf(0) }
    val totalMs = items.sumOf { it.clip.durationMs }
    // The clock of the whole timeline: absolute milliseconds, and its total.
    var shown by remember { mutableLongStateOf(0L) }
    var shownTotal by remember { mutableLongStateOf(totalMs) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = player.currentMediaItemIndex
                photo.shown = null
            }

            // A picture the phone cannot read must not stop the film: the piece is passed over and the rest plays on.
            override fun onPlayerError(error: PlaybackException) {
                val now = inPlayer.getOrNull(player.currentMediaItemIndex) ?: return
                if (now.clip.role != ProjectClip.Role.STILL && !now.offline) return
                photo.shown = null
                if (!player.hasNextMediaItem()) return
                player.seekToNextMediaItem()
                player.prepare()
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
        // A rebuild keeps the piece and the place in it: the piece is looked up in the list the player held (its index
        // meant that list), then found again in the new order — a move or an addition must not jump to another piece.
        val was = inPlayer.getOrNull(player.currentMediaItemIndex)?.clip?.let { it to player.currentPosition.coerceAtLeast(0L) }
            ?: resume
        // A piece out of reach plays as a picture held for its length: the clock and the order stay the project's own.
        val media = items.map { piece ->
            if (!piece.offline) mediaItem(vm, piece.clip, piece.file)
            else MediaItem.Builder()
                .setUri(vm.offlineStill(piece.file, piece.clip.fromMs))
                .setImageDurationMs(piece.clip.durationMs.coerceAtLeast(1L))
                .build()
        }
        player.setMediaItems(media)
        inPlayer.clear()
        inPlayer.addAll(items)
        player.prepare()
        val at = if (was == null) -1 else items.indexOfFirst { it.clip == was.first }
        if (at >= 0) player.seekTo(at, minOf(was!!.second, items[at].clip.durationMs))
    }
    // The clock follows the player whether or not it plays, and it counts the whole timeline, not the item played.
    LaunchedEffect(player) {
        while (true) {
            val now = inPlayer.toList()
            val lengths = lengthsOf(player, now.map { it.clip })
            if (dragging == null && now.isNotEmpty()) shown = clockAt(lengths, player.currentMediaItemIndex, player.currentPosition)
            shownTotal = if (lengths.isEmpty()) itemsNow.sumOf { it.clip.durationMs } else lengths.sum()
            now.getOrNull(player.currentMediaItemIndex)?.let { resume = it.clip to player.currentPosition.coerceAtLeast(0L) }
            // Told to the screen: its card is marked in the strip and the strip follows it.
            onPlayingNow(now.getOrNull(player.currentMediaItemIndex)?.at?.index)
            delay(250)
        }
    }
    // A card tapped: the preview jumps to that piece — video or photo alike — and plays on from it.
    LaunchedEffect(play, signature) {
        val at = inPlayer.indexOfFirst { it.at.index == play }
        if (at >= 0) {
            player.seekTo(at, 0L)
            player.play()
        }
        if (play != null) onPlayed()
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
                modifier = Modifier.fillMaxSize(),
            )
            // A photo is a picture of its own, not a frame of video: it is drawn over the black the video surface
            // leaves. The player clears it when another piece starts, so what stands here is never the last one's.
            val decoded = remember(photo.shown) { photo.shown?.asImageBitmap() }
            val piece = inPlayer.getOrNull(current)
            if (decoded != null) {
                Image(decoded, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                // Until it comes, the piece's own card picture stands in (it is already on the phone): a full photo
                // over the camera's Wi-Fi takes a moment.
                val now = piece?.takeIf { it.clip.role == ProjectClip.Role.STILL || it.offline }
                val cached by produceState<ImageBitmap?>(null, now?.clip) {
                    value = now?.let {
                        if (it.clip.role == ProjectClip.Role.STILL) vm.thumbnail(it.file)
                        else vm.timelineFrame(it.file, it.clip.fromMs, keep = true)
                    }
                }
                val stand = cached
                if (stand != null) Image(stand, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            // A piece out of reach says so over its frame: the camera is away, or this file is not on its card now.
            if (piece?.offline == true) {
                Text(
                    if (vm.conn == ConnState.Connected) tr("Файла нет на камере", "The file is not on the camera")
                    else tr("Камера не подключена — показан кадр", "Camera offline — showing a frame"),
                    Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
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
                        val (i, at) = seekPoint(lengthsOf(player, inPlayer.map { it.clip }), ms.toLong())
                        if (inPlayer.isNotEmpty()) player.seekTo(i, at)
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