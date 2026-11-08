package app.goultra.transfer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.ProjectClip
import app.goultra.transfer.camera.ProjectMusic

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

/**
 * The long side of a photo as the preview decodes it: more than a phone screen needs, little enough to keep in hand ?
 * media3's own decoder samples a 50 MP photo down to it instead of taking it whole.
 */
private const val STILL_SIDE = 1280

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
internal fun ProjectPreview(
    vm: CameraController,
    clips: List<IndexedValue<ProjectClip>>,
    play: Int?,
    onPlayed: () -> Unit,
    onPlaying: (Int?) -> Unit,
    carrying: Boolean,
    music: ProjectMusic? = null,
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
                out.add(ImageRenderer(BitmapFactoryImageDecoder.Factory(context).setMaxOutputSize(STILL_SIDE), photo))
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
    // The video's own sound under the music: at its own slider's level while there is music (P2).
    LaunchedEffect(music?.uri, music?.videoVolume) {
        player.volume = music?.videoVolume?.toFloat()?.coerceIn(0f, 1f) ?: 1f
    }
    // The music itself: another player, kept at the project's own time — the picture may wait on the camera, the
    // sound need not.
    ProjectMusicSound(music, shown, playing)
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
