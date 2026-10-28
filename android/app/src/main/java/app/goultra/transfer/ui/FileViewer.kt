package app.goultra.transfer.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Compare
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Switch
import app.goultra.transfer.camera.CubeLut
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Downloader
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.KeyFrames
import app.goultra.transfer.camera.LivePhoto
import app.goultra.transfer.camera.MediaKind

/**
 * The in-app viewer. A file still on the camera: video plays its low-res LRV copy (or the file itself if there is
 * none), photos open full size; «Скачать» takes just this file. A file on the phone ([CameraFile.isOnPhone]) shows
 * from there, full quality (a video's marks read from the file). Pinch to zoom; swipe up / down for the next file;
 * ♥ favorite, «i» info, share (files on the phone).
 */
@Composable
fun FileViewer(vm: CameraController, file: CameraFile, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Swipe up / down: the next / previous file of the gallery as it is listed (filter included). The list is
        // taken when the viewer opens; each page shows the phone's copy of a file when there is one.
        val list = remember { vm.visibleFiles.takeIf { l -> l.any { it.name == file.name } } ?: listOf(file) }
        val pager = rememberPagerState(initialPage = list.indexOfFirst { it.name == file.name }.coerceAtLeast(0)) { list.size }
        var locked by remember { mutableStateOf(false) } // editing pieces: the timeline owns the gestures
        VerticalPager(
            pager,
            Modifier.fillMaxSize().background(Color.Black),
            userScrollEnabled = !locked,
            key = { list[it].name },
        ) { page ->
            val listed = list[page]
            val shown by produceState(if (listed.name == file.name) file else null, listed.name) {
                if (value == null) value = vm.withPhoneCopy(listed)
            }
            shown?.let { FilePage(vm, it, active = pager.settledPage == page, onClose = onClose, onLock = { l -> locked = l }) }
        }
    }
}

/** One file in the viewer: video, photo or live photo, with its top bar. [active]: the page on screen (plays). */
@Composable
private fun FilePage(vm: CameraController, file: CameraFile, active: Boolean, onClose: () -> Unit, onLock: (Boolean) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val clip = vm.liveClipOf(file)
        var playing by remember(file.uri) { mutableStateOf(false) }
        // Video: the pieces to play, show in the film strip and download — the camera's marks as its film strip
        // shows them (2 s pieces, nearby ones merged), or the pieces as edited on the phone.
        val isVideo = file.kind == MediaKind.VIDEO
        // The video's real length once the player knows it; until then the listed whole seconds + 1 s. A mark set
        // just before the recording stopped must not reach past the end.
        var exactMs by remember(file.uri) { mutableStateOf<Long?>(null) }
        val limitMs = exactMs ?: file.durationSec?.let { (it + 1) * 1000L }
        val marks by produceState(emptyList<Highlight>(), file.uri, file.localUri) { if (isVideo) value = vm.highlights(file) }
        val cameraPieces = remember(marks, limitMs) { Highlights.pieces(marks, limitMs) }
        var edited by remember(file.name) { mutableStateOf(vm.editedPieces(file)) }
        val pieces = remember(edited, cameraPieces, limitMs) { Highlights.clip(edited ?: cameraPieces, limitMs) }
        /** Saves an edit (null: back to the camera's marks); returns the pieces now in effect. */
        fun edit(new: List<Highlight>?): List<Highlight> {
            val tidy = new?.let { Highlights.normalize(it, limitMs) }
            edited = tidy
            vm.savePieces(file, tidy)
            return tidy ?: cameraPieces
        }
        var choose by remember(file.uri) { mutableStateOf(false) }
        var info by remember(file.uri) { mutableStateOf(false) }
        // Setting the colour: only the video and the panel — the top bar and the ♥ / «i» / share column hide.
        var colouring by remember(file.uri) { mutableStateOf(false) }
        if (info) FileInfo(vm, file) { info = false }
        // A clip of this video already in the gallery: replace it, or keep both (…_HL2.mp4).
        val scope = rememberCoroutineScope()
        var replaceClip by remember(file.uri) { mutableStateOf(false) }
        fun startClip() {
            scope.launch {
                if (vm.clipExists(file)) replaceClip = true else { vm.saveClip(file, pieces); onClose() }
            }
        }
        if (replaceClip) {
            AlertDialog(
                onDismissRequest = { replaceClip = false },
                title = { Text(tr("Клип уже есть", "The clip already exists")) },
                text = {
                    Text(
                        tr(
                            "В галерее уже есть ${vm.clipName(file)}. Заменить его новым или сохранить новый отдельно?",
                            "${vm.clipName(file)} is already in the gallery. Replace it, or save the new one separately?",
                        ),
                    )
                },
                confirmButton = {
                    TextButton({ replaceClip = false; vm.saveClip(file, pieces); onClose() }) { Text(tr("Заменить", "Replace")) }
                },
                dismissButton = {
                    TextButton({
                        scope.launch {
                            val name = vm.freeClipName(file)
                            replaceClip = false
                            vm.saveClip(file, pieces, name)
                            onClose()
                        }
                    }) { Text(tr("Сохранить отдельно", "Save separately")) }
                },
            )
        }
        when {
            isVideo -> VideoPreview(vm, file, pieces, edited != null, ::edit, active, onLock, { colouring = it }) { ms ->
                // A camera file plays its light copy (.lrv), which may end a little before the file clips are cut
                // from: never cut the pieces shorter than the listed whole seconds.
                exactMs = if (file.isOnPhone) ms else maxOf(ms, (file.durationSec ?: 0) * 1000L)
            }
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
        if (!colouring) Row(
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
                Button({ startClip() }, enabled = !vm.isDownloading) {
                    Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Клип", "Clip"))
                }
            }
        }
        // ♥, «i», share: a column at the right edge, mid-height — the top bar keeps room for the name.
        if (!colouring) Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val round = Modifier.background(Color(0x66000000), CircleShape)
            IconButton({ vm.toggleFavorite(file) }, round) {
                val fav = file.name in vm.favorites
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    tr("Избранное", "Favorite"),
                    tint = if (fav) Color(0xFFFF5252) else Color.White,
                )
            }
            IconButton({ info = true }, round) { Icon(Icons.Outlined.Info, tr("Сведения", "Info"), tint = Color.White) }
            // Sharing needs the file on the phone.
            if (file.localUri != null) {
                IconButton({ share(context, listOf(file)) }, round) { Icon(Icons.Filled.Share, tr("Поделиться", "Share"), tint = Color.White) }
            }
        }
        if (choose) {
            DownloadChoice(
                file, pieces, vm.clipName(file),
                onWhole = { choose = false; vm.downloadOne(file); onClose() },
                onPieces = { choose = false; startClip() },
                onDismiss = { choose = false },
            )
        }
    }
}

/** «i»: what is known about the file (EXIF for photos, the stream for videos). */
@Composable
private fun FileInfo(vm: CameraController, file: CameraFile, onDismiss: () -> Unit) {
    val rows by produceState<List<Pair<String, String>>?>(null, file.uri, file.localUri) { value = vm.fileInfo(file) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Сведения", "Info")) },
        text = {
            val list = rows
            if (list == null) {
                CircularProgressIndicator()
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((name, value) in list) {
                        Row {
                            Text(name, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Text(value, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}

/** The phone's share sheet for files on the phone (one or several). */
fun share(context: android.content.Context, files: List<CameraFile>) {
    val uris = files.mapNotNull { f -> f.localUri?.let(android.net.Uri::parse) }
    if (uris.isEmpty()) return
    val kinds = files.map { it.kind }.toSet()
    val type = when {
        kinds == setOf(MediaKind.VIDEO) -> "video/*"
        kinds == setOf(MediaKind.PHOTO) -> "image/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
    }.setType(type).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, tr("Поделиться", "Share"))) }
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
    active: Boolean,
    onLock: (Boolean) -> Unit,
    onColouring: (Boolean) -> Unit,
    onDuration: (Long) -> Unit,
) {
    val context = LocalContext.current
    val source = remember { if (file.isOnPhone) null else vm.previewSource() }
    if (source == null && !file.isOnPhone) {
        Text(tr("Нет связи с камерой по Wi-Fi", "No Wi-Fi link to the camera"), color = Color.White, modifier = Modifier.padding(24.dp))
        return
    }
    // Colour: every video goes through the player's effects (they exist only if set up before it starts):
    // Insta360's LUT for I-Log files, exposure / saturation as a matrix the sliders change live, clipping stripes on
    // demand. Only the LUT and the stripes rebuild the effects.
    val iLog by produceState<Boolean?>(null, file.uri, file.localUri) { value = vm.isILog(file) }
    val lut by produceState<CubeLut?>(null) { value = vm.lut() }
    var saved by remember(file.name) { mutableStateOf(vm.look(file)) }
    val look = saved ?: ColorLook()
    var colorPanel by remember(file.uri) { mutableStateOf(false) }
    val ilog = iLog == true
    val table = lut
    if (iLog == null || (ilog && table == null)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        return
    }
    // The effects didn't show a picture on this phone (or failed): the video is shown as shot.
    var effectsFailed by remember(file.uri) { mutableStateOf(false) }
    val effects = !effectsFailed
    val matrix = remember(file.uri) { LiveColorMatrix(look.exposure, look.saturation) }
    val sharpen = remember(file.uri) { Sharpen(look.sharpness, look.sharpRadius) }
    val filmLook = remember(file.uri) { FilmLook(look) }
    val resumeAt = remember(file.uri) { longArrayOf(0) } // where a restarted player picks up
    val firstFrame = remember(file.uri, effects) { BooleanArray(1) } // set by the player's listener
    // Preview weight. Playing, the effects work on a picture no bigger than the screen needs (PLAY_SIZES[level] lines,
    // never upscaled); a phone that can't keep up goes a step lower, the last step with lighter halation and
    // sharpening. Paused, the frame is done in full: settings are seen as they will be.
    var level by remember(file.uri) { mutableIntStateOf(0) }
    // «До / после» in the colour panel: the video as shot (the clipping stripes stay, they measure, not grade).
    var bypass by remember(file.uri) { mutableStateOf(false) }
    val bypassNow by rememberUpdatedState(bypass)
    var paused by remember(file.uri) { mutableStateOf(!active) }
    var sourceLines by remember(file.uri) { mutableIntStateOf(0) } // the video's short side, once the player knows it
    val previewLines = PLAY_SIZES[level].takeIf { !paused && sourceLines > it }
    val light = !paused && level == PLAY_SIZES.lastIndex
    fun stepDown(why: String): Boolean {
        if (level == PLAY_SIZES.lastIndex) return false
        level++
        vm.log("цвет ${file.name}: $why — превью проще: ${PLAY_SIZES[level]} строк${if (level == PLAY_SIZES.lastIndex) ", облегчённые ореол и резкость" else ""}")
        return true
    }
    fun dropEffects(why: String) {
        vm.log("цвет ${file.name}: $why — без обработки цвета")
        effectsFailed = true
        android.widget.Toast.makeText(context, tr("Цвет на этом телефоне не показывается", "Colour can't be shown on this phone"), android.widget.Toast.LENGTH_LONG).show()
    }
    val player = remember(file.uri, effects) {
        ExoPlayer.Builder(context).build().apply {
            if (effects) setVideoEffects(colorEffects(table, ilog, look, matrix, sharpen, filmLook, vm.clipping, previewLines, bypass))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame[0] = true }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) sourceLines = minOf(videoSize.width, videoSize.height)
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    vm.log("плеер ${file.name}: ${error.errorCodeName} ${error.message ?: ""} ${error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
                    // Too heavy while playing: a lighter preview first (the effects are set again and the player
                    // restarted below); with nothing lighter left, no colour.
                    if (effects && !(playWhenReady && stepDown("ошибка плеера"))) dropEffects("ошибка плеера")
                }
            })
            if (resumeAt[0] > 0) seekTo(resumeAt[0])
            if (source == null) {
                setMediaItem(MediaItem.fromUri(file.localUri!!)) // the phone's copy: the default (content://) source
            } else {
                setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(vm.previewUrl(file))))
            }
            playWhenReady = active
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release(); vm.closeTimeline() } }
    // Paused, the player shows no new frame by itself: after a change the same frame is decoded and drawn again
    // (from the key frame before it, so a moment later). Changes in a row make one redraw.
    var redraw by remember(player) { mutableIntStateOf(0) }
    LaunchedEffect(redraw) {
        if (redraw == 0) return@LaunchedEffect
        delay(150)
        if (!player.playWhenReady) player.seekTo(player.currentPosition)
    }
    // The LUT, the stripes or the preview size changed: the effects again (not right after the player got them at its
    // start). After an error the player starts again with them.
    val applied = remember(player) { arrayOf<Any>(listOf(look.lut, look.strength, vm.clipping, previewLines, level, bypass)) }
    LaunchedEffect(player, look.lut, look.strength, vm.clipping, previewLines, level, bypass) {
        val now = listOf(look.lut, look.strength, vm.clipping, previewLines, level, bypass)
        if (effects && now != applied[0]) {
            applied[0] = now
            player.setVideoEffects(colorEffects(table, ilog, look, matrix, sharpen, filmLook, vm.clipping, previewLines, bypass))
            if (player.playerError != null) player.prepare() else redraw++
        }
    }
    LaunchedEffect(light) { sharpen.light = light; filmLook.light = light }
    val shownLook = remember(player) { arrayOf(look) }
    LaunchedEffect(look) {
        matrix.set(look.exposure, look.saturation)
        sharpen.amount = look.sharpness
        sharpen.radius = look.sharpRadius
        filmLook.set(look)
        if (look != shownLook[0]) { shownLook[0] = look; redraw++ }
    }
    // Playing slower than the video's frame rate (frames counted where the film look draws them): a lighter preview.
    LaunchedEffect(player) {
        if (!effects) return@LaunchedEffect
        var last = filmLook.frames.get()
        var settle = 2 // windows to skip after a start or a change
        while (true) {
            delay(2_000)
            val now = filmLook.frames.get()
            val drawn = now - last
            last = now
            // Bypassed, the film look draws nothing: no measure then.
            if (bypassNow || !player.isPlaying || player.playbackState != Player.STATE_READY) { settle = 2; continue }
            if (settle > 0) { settle--; continue }
            val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
            val wanted = fps * player.playbackParameters.speed * 2
            if (drawn < wanted * 0.8f && stepDown("${drawn / 2} кадров/с из ${Math.round(fps)}")) settle = 2
        }
    }
    // While setting the colour the video goes round, so the change is always in sight.
    LaunchedEffect(player, colorPanel) { player.repeatMode = if (colorPanel) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    // With effects: no frame after 5 s of playing means the picture doesn't come through — back to no effects.
    LaunchedEffect(player) {
        if (!effects) return@LaunchedEffect
        var playedMs = 0
        while (!firstFrame[0] && playedMs < 5_000) {
            delay(250)
            if (player.isPlaying && player.playbackState == Player.STATE_READY) playedMs += 250
        }
        if (!firstFrame[0]) dropEffects("нет кадров за 5 с")
    }
    // Swiped away: stop; swiped back: play on.
    LaunchedEffect(active) { if (active) player.play() else player.pause() }
    // Zoom with two fingers; one finger moves the zoomed picture. Taken before the player view sees the touches,
    // so the player's own taps (controls, seek bar) still work when not zooming.
    var zoom by remember(file.uri) { mutableFloatStateOf(1f) }
    var boxSize by remember(file.uri) { mutableStateOf(IntSize.Zero) }
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    var shift by remember(file.uri) { mutableStateOf(Offset.Zero) }
    var durationMs by remember(file.uri) { mutableLongStateOf(file.durationSec?.let { it * 1000L } ?: 0L) }
    LaunchedEffect(player) {
        var reported = false
        while (true) {
            if (player.duration > 0 && (!reported || player.duration != durationMs)) {
                durationMs = player.duration
                onDuration(durationMs)
                reported = true
            }
            resumeAt[0] = player.currentPosition
            delay(500)
        }
    }
    // Ticks on the seek bar at the pieces; the flag switches to the film strip — only the pieces play, the rest is
    // skipped. In the strip the pieces can be edited (kept on the phone): then playback is free, for finding spots.
    var film by remember(file.uri) { mutableStateOf(false) }
    var editing by remember(file.uri) { mutableStateOf(false) }
    val colouring = colorPanel && !editing
    val colouringNow by rememberUpdatedState(colouring) // for the gesture handler, set up once
    // No swiping to the next file while the timeline or the colour sliders own the gestures.
    LaunchedEffect(editing, colouring) { onLock(editing || colouring) }
    LaunchedEffect(colouring) {
        onColouring(colouring)
        if (!colouring) bypass = false
    }
    // The video's box changes size with the panel. Paused, the player shows no new frame, so the old picture would stay
    // where it was until playback starts: once the new layout is in, the same frame is shown again.
    val shownColouring = remember(player) { booleanArrayOf(colouring) }
    LaunchedEffect(player, colouring) {
        if (colouring == shownColouring[0]) return@LaunchedEffect
        shownColouring[0] = colouring
        redraw++
    }
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
    /** «Обрезать»: one piece over the whole video, selected in the timeline — drag its ends to trim. */
    fun trimWhole() {
        val end = durationMs.takeIf { it > 0 } ?: file.durationSec?.let { it * 1000L } ?: return
        commit(listOf(Highlight(0, end)), 0)
        film = true
        editing = true
        player.pause()
    }
    var askTrim by remember(file.uri) { mutableStateOf(false) }
    if (askTrim) {
        AlertDialog(
            onDismissRequest = { askTrim = false },
            title = { Text(tr("Обрезать видео", "Trim the video")) },
            text = {
                Text(
                    tr(
                        "Уже есть отмеченные куски (${pieces.size}). Заменить их одним куском на всё видео, чтобы обрезать начало и конец? Или оставить куски и править их.",
                        "There are marked pieces (${pieces.size}). Replace them with one piece over the whole video to trim its start and end? Or keep the pieces and edit them.",
                    ),
                )
            },
            confirmButton = { TextButton({ askTrim = false; trimWhole() }) { Text(tr("Заменить", "Replace")) } },
            dismissButton = {
                TextButton({ askTrim = false; film = true; editing = true; player.pause() }) { Text(tr("Оставить куски", "Keep pieces")) }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        // Setting the colour: the panel lies over the lower part of the video's box and the picture moves up into the
        // rest. The box itself keeps its size — the player's SurfaceView doesn't follow a resize while paused, a move
        // and a scale it does.
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { boxSize = it }.pointerInput(file.uri) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // On the colour panel the touches are the sliders'.
                    if (colouringNow && down.position.y > size.height * VIDEO_SHARE) return@awaitEachGesture
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            shift = clampShift(shift + event.calculatePan(), zoom, size.width.toFloat(), size.height.toFloat(), player.videoSize)
                            event.changes.forEach { it.consume() }
                        } else if (fingers == 1 && zoom > 1f) {
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                shift = clampShift(shift + pan, zoom, size.width.toFloat(), size.height.toFloat(), player.videoSize)
                                event.changes.forEach { it.consume() }
                            }
                        }
                        if (zoom == 1f) shift = Offset.Zero
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            AndroidView(
                // Controls only on a tap on the picture, not on their own when the video starts or a slider moves.
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        controllerAutoShow = false
                        hideController()
                    }
                },
                update = { view ->
                    // The player is made again when the colour effects start or stop: the view must follow it.
                    if (view.player !== player) view.player = player
                    view.setExtraAdGroupMarkers(pieces.map { it.startMs }.toLongArray(), BooleanArray(pieces.size))
                    // Only the picture zooms, not the player's controls; setting the colour, it sits above the panel.
                    val (fit, up) = if (colouring) colouringFit(boxSize, player.videoSize, statusTop) else 1f to 0f
                    view.videoSurfaceView?.apply {
                        scaleX = zoom * fit
                        scaleY = zoom * fit
                        translationX = shift.x
                        translationY = shift.y + up
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The phone couldn't keep up: playing shows a lighter preview (a paused frame is in full).
            if (effects && level > 0 && !paused) {
                Text(
                    tr("упрощённо", "simplified") + " · ${PLAY_SIZES[level]}p",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.align(if (colouring) Alignment.TopStart else Alignment.TopEnd).safeDrawingPadding()
                        .padding(top = if (colouring) 12.dp else 64.dp, start = 12.dp, end = 12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Setting the colour: before / after (the processing off while lit) and «Готово», over the video's top.
            if (colouring) Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    { bypass = !bypass },
                    Modifier.background(if (bypass) MARK_COLOR else Color(0x99000000), CircleShape),
                ) {
                    Icon(Icons.Filled.Compare, tr("До / после", "Before / after"), tint = if (bypass) Color.Black else Color.White)
                }
                Button({ colorPanel = false }) { Text(tr("Готово", "Done")) }
            }
            if (!colouring) Row(
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(top = 60.dp, start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chipColors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0x99000000), labelColor = Color.White,
                    selectedContainerColor = Color(0xCC000000), selectedLabelColor = MARK_COLOR,
                )
                // Shown without marks too: pieces can be added by hand in the strip.
                FilterChip(
                    selected = film,
                    onClick = { film = !film; if (!film) editing = false },
                    label = { Text(if (film) tr("Отмеченное", "Highlights only") else "${pieces.size}") },
                    leadingIcon = { Icon(Icons.Filled.Flag, tr("Отметки", "Highlights"), tint = MARK_COLOR) },
                    colors = chipColors,
                )
                // Colour: a LUT (I-Log → Rec.709) with its strength, brightness, saturation — for viewing.
                if (!editing) {
                    FilterChip(
                        selected = !look.isPlain(ilog),
                        onClick = { colorPanel = !colorPanel },
                        label = { Text(if (ilog) "I-Log" else tr("Цвет", "Colour")) },
                        leadingIcon = { Icon(Icons.Filled.Palette, null, tint = if (!look.isPlain(ilog)) MARK_COLOR else Color.White) },
                        colors = chipColors,
                    )
                }
                // Trimming: the whole video as one piece, its ends on handles in the timeline.
                if (!editing) {
                    FilterChip(
                        selected = false,
                        onClick = { if (pieces.isEmpty()) trimWhole() else askTrim = true },
                        label = { Text(tr("Обрезать", "Trim")) },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, null, tint = Color.White) },
                        colors = chipColors,
                    )
                }
            }
            if (colouring) {
                ColorPanel(
                    Modifier.align(Alignment.BottomCenter).fillMaxHeight(1f - VIDEO_SHARE),
                    ilog, look, saved != null, effectsFailed, vm.clipping,
                    onLook = { saved = it; vm.saveLook(file, it) },
                    onLive = { ev, sat -> matrix.set(ev, sat); redraw++ },
                    onSharpLive = { amount, radius -> sharpen.amount = amount; sharpen.radius = radius; redraw++ },
                    onFilmLive = { filmLook.set(it); redraw++ },
                    onClipping = vm::toggleClipping,
                    onReset = { saved = null; vm.saveLook(file, null) },
                )
            }
        }
        if (film && !colouring) {
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

/**
 * The player's effects, in order: the picture made smaller for a lighter preview ([previewLines] lines, while
 * playing), Insta360's LUT for an I-Log file (rebuilt for a new strength), the live exposure /
 * saturation [matrix], the live [sharpen] (on the coloured picture, as editors do), the live [film] look (after the
 * sharpening, so its grain isn't sharpened), the clipping stripes when on. [bypass]: only the size and the stripes.
 */
@OptIn(UnstableApi::class)
private fun colorEffects(
    lut: CubeLut?,
    iLog: Boolean,
    look: ColorLook,
    matrix: LiveColorMatrix,
    sharpen: Sharpen,
    film: FilmLook,
    clipping: Boolean,
    previewLines: Int?,
    bypass: Boolean,
): List<androidx.media3.common.Effect> {
    val out = mutableListOf<androidx.media3.common.Effect>()
    if (previewLines != null) out += androidx.media3.effect.Presentation.createForHeight(previewLines)
    if (bypass) {
        if (clipping) out += ClippingStripes()
        return out
    }
    if (iLog && lut != null && look.lut && look.strength > 0f) out += androidx.media3.effect.SingleColorLut.createFromCube(lut.cube(look.strength))
    out += matrix
    out += sharpen
    out += film
    if (clipping) out += ClippingStripes()
    return out
}

/** Setting the colour: the share of the video's box (from the top) the picture keeps above the panel. */
private const val VIDEO_SHARE = 0.45f

/**
 * Setting the colour: the scale and the move down (px, negative = up) that put the picture, fitted into the [box] as the
 * player shows it, whole into the box's top [VIDEO_SHARE] below [top] px (the status bar).
 */
private fun colouringFit(box: IntSize, video: androidx.media3.common.VideoSize, top: Float): Pair<Float, Float> {
    val w = box.width.toFloat()
    val h = box.height.toFloat()
    if (w <= 0f || h <= 0f) return 1f to 0f
    val aspect = if (video.width > 0 && video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else w / h
    val shownH = if (aspect > w / h) w / aspect else h
    val bottom = h * VIDEO_SHARE
    return minOf(1f, (bottom - top) / shownH) to (top + bottom) / 2 - h / 2
}

/** Preview sizes while playing (lines), from the first choice to the lightest. */
private val PLAY_SIZES = intArrayOf(1080, 720, 540)

/**
 * Colour while viewing (the file stays as shot), on two tabs so the sliders don't cover the video. «Свет и цвет» —
 * I-Log: Insta360's «I-Log → Rec.709» on / off and its strength; any video: exposure (stops), saturation, sharpness.
 * «Плёнка» — the print look (FilmLook): how much, warmth, skin protection, grain, halation. Seen as the slider moves;
 * clipping stripes on / off. Settings are kept per video; «Сбросить» goes back to the default (I-Log: LUT on).
 */
@Composable
private fun ColorPanel(
    modifier: Modifier,
    iLog: Boolean,
    look: ColorLook,
    changed: Boolean,
    failed: Boolean,
    clipping: Boolean,
    onLook: (ColorLook) -> Unit,
    onLive: (exposure: Float, saturation: Float) -> Unit,
    onSharpLive: (amount: Float, radius: Float) -> Unit,
    onFilmLive: (ColorLook) -> Unit,
    onClipping: () -> Unit,
    onReset: () -> Unit,
) {
    var filmTab by remember { mutableStateOf(false) }
    // The tabs stay; the sliders under them scroll. Before / after and «Готово» sit over the video.
    Column(modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tabColors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = Color.White,
                selectedContainerColor = Color(0x33FFFFFF),
                selectedLabelColor = Color.White,
            )
            FilterChip(!filmTab, { filmTab = false }, { Text(tr("Свет и цвет", "Light & colour")) }, colors = tabColors)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                filmTab, { filmTab = true }, { Text(tr("Плёнка", "Film")) },
                colors = tabColors,
                leadingIcon = if (look.film > 0f || look.grain > 0f || look.halation > 0f || look.warmth != 0f) {
                    { Box(Modifier.size(8.dp).background(MARK_COLOR, CircleShape)) }
                } else null,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (filmTab) {
                FilmSliders(look, onLook, onFilmLive)
            } else {
                LightSliders(iLog, look, onLook, onLive, onSharpLive)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = clipping, onCheckedChange = { onClipping() })
                Spacer(Modifier.width(10.dp))
                Text(tr("Пересвет и провалы", "Clipping"), color = Color.White, fontSize = 13.sp)
                Text(tr("  красным / синим", "  red / blue"), color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (changed) TextButton(onReset) { Text(tr("Сбросить", "Reset"), color = Color.White) }
            }
            if (failed) {
                Text(
                    tr("Обработка цвета на этом телефоне не показала картинку — видео показано как снято.", "Colour processing showed no picture on this phone — the video is shown as shot."),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
            Text(tr("Только для просмотра — файл не меняется", "For viewing — the file stays as shot"), color = Color(0x99FFFFFF), fontSize = 12.sp)
        }
    }
}

/** «Свет и цвет»: the I-Log LUT and its strength, exposure, saturation, sharpness and its radius. */
@Composable
private fun LightSliders(
    iLog: Boolean,
    look: ColorLook,
    onLook: (ColorLook) -> Unit,
    onLive: (exposure: Float, saturation: Float) -> Unit,
    onSharpLive: (amount: Float, radius: Float) -> Unit,
) {
    if (iLog) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = look.lut, onCheckedChange = { onLook(look.copy(lut = it)) })
            Spacer(Modifier.width(10.dp))
            Text("I-Log → Rec.709", color = Color.White, fontSize = 13.sp)
        }
    }
    // The LUT strength applies when the slider is let go (it rebuilds the colour table); exposure and saturation
    // show as the slider moves.
    if (iLog && look.lut) {
        LookSlider(tr("Сила LUT", "LUT strength"), look.strength, 0f..1f, { "${Math.round(it * 100)} %" }) { onLook(look.copy(strength = it)) }
    }
    LookSlider(
        tr("Экспозиция", "Exposure"), look.exposure, -2f..2f, { "%+.1f EV".format(it) },
        live = { onLive(it, look.saturation) },
    ) { onLook(look.copy(exposure = it)) }
    LookSlider(
        tr("Насыщенность", "Saturation"), look.saturation, 0f..2f, { "${Math.round(it * 100)} %" },
        live = { onLive(look.exposure, it) },
    ) { onLook(look.copy(saturation = it)) }
    // Unsharp mask on brightness: how much, and how wide the contours it lifts (small = fine texture, large =
    // shapes; too large shows halos).
    LookSlider(
        tr("Резкость", "Sharpness"), look.sharpness, 0f..1f, { "${Math.round(it * 100)} %" },
        live = { onSharpLive(it, look.sharpRadius) },
    ) { onLook(look.copy(sharpness = it)) }
    if (look.sharpness > 0f) {
        LookSlider(
            tr("Радиус", "Radius"), look.sharpRadius, 0.5f..3f, { "%.1f ".format(it) + tr("пкс", "px") },
            live = { onSharpLive(look.sharpness, it) },
        ) { onLook(look.copy(sharpRadius = it)) }
    }
}

/**
 * «Плёнка» (see FilmMath): how much of the print look — contrast with a soft shoulder, dense colour, split toning;
 * warmth as a white balance; skin protection (shown while there is something to protect from); grain and halation.
 */
@Composable
private fun FilmSliders(look: ColorLook, onLook: (ColorLook) -> Unit, onFilmLive: (ColorLook) -> Unit) {
    fun percent(v: Float) = "${Math.round(v * 100)} %"
    LookSlider(tr("Плёнка", "Film"), look.film, 0f..1f, ::percent, live = { onFilmLive(look.copy(film = it)) }) {
        onLook(look.copy(film = it))
    }
    LookSlider(
        tr("Холод ↔ тепло", "Cool ↔ warm"), look.warmth, -1f..1f, { "%+d".format(Math.round(it * 100)) },
        live = { onFilmLive(look.copy(warmth = it)) },
    ) { onLook(look.copy(warmth = it)) }
    if (look.film > 0f || look.warmth != 0f) {
        LookSlider(tr("Кожа", "Skin"), look.skin, 0f..1f, ::percent, live = { onFilmLive(look.copy(skin = it)) }) {
            onLook(look.copy(skin = it))
        }
    }
    LookSlider(tr("Зерно", "Grain"), look.grain, 0f..1f, ::percent, live = { onFilmLive(look.copy(grain = it)) }) {
        onLook(look.copy(grain = it))
    }
    LookSlider(tr("Ореол", "Halation"), look.halation, 0f..1f, ::percent, live = { onFilmLive(look.copy(halation = it)) }) {
        onLook(look.copy(halation = it))
    }
}

/** One colour setting: name, slider, value; [live] as it moves, [onSet] when it is let go. */
@Composable
private fun LookSlider(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    live: (Float) -> Unit = {},
    onSet: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(104.dp))
        Slider(v, { v = it; live(it) }, Modifier.weight(1f), valueRange = range, onValueChangeFinished = { onSet(v) })
        Text(label(v), color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

/**
 * A zoomed picture may move only as far as its edges reach the frame's. The picture is fitted into the [w]×[h] box
 * (black bars on two sides), so the room to move comes from its shown size, not the box's.
 */
private fun clampShift(shift: Offset, zoom: Float, w: Float, h: Float, video: androidx.media3.common.VideoSize): Offset {
    val aspect = if (video.width > 0 && video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else w / h
    val (shownW, shownH) = if (aspect > w / h) w to w / aspect else h * aspect to h
    val maxX = maxOf(0f, (zoom * shownW - w) / 2)
    val maxY = maxOf(0f, (zoom * shownH - h) / 2)
    return Offset(shift.x.coerceIn(-maxX, maxX), shift.y.coerceIn(-maxY, maxY))
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

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // transformable(canPan)
@Composable
private fun PhotoPreview(vm: CameraController, file: CameraFile) {
    val config = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) { (maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 1.5f).toInt() }
    // Reloaded after a cover change (the photo is then the chosen frame).
    var failed by remember(file.uri) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, file.uri, file.localUri, vm.coverVersion) {
        value = vm.previewPhoto(file, maxSide)
        failed = value == null
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = image
        if (bmp == null && failed) {
            // E.g. a RAW .dng the phone can't decode here: offer the phone's own apps.
            val context = LocalContext.current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(tr("Не удалось показать файл", "Couldn't show the file"), color = Color.White)
                file.localUri?.let { uri ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton({
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(android.net.Uri.parse(uri), Downloader.mimeType(file.name))
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }) { Text(tr("Открыть в другом приложении", "Open in another app"), color = Color.White) }
                }
            }
        } else if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
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
                    // One finger moves the photo only when zoomed in; otherwise the swipe goes to the next file.
                    .transformable(zoom, canPan = { scale > 1f })
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}
