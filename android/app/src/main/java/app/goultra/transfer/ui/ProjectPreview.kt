package app.goultra.transfer.ui

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.material3.MaterialTheme
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.ProjectClip
import app.goultra.transfer.camera.ProjectMusic
import app.goultra.transfer.camera.ProjectAudioClip

/**
 * One piece as a media3 item: its file's URI, cut to its range. A still has no range to cut — it is one picture, and
 * its length is how long the picture stands: the player holds it that long and moves on to the next piece.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun mediaItem(vm: CameraController, clip: ProjectClip, file: CameraFile, id: String): MediaItem {
    val uri = vm.previewPlaybackUri(file)
    // A stable per-card id: the same piece may legally appear twice in a project, and the preload manager keys
    // sources by MediaItem equality, while the player needs two independent items.
    val builder = MediaItem.Builder().setUri(uri).setMediaId(id)
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
 * The pieces' lengths, ms: the project's own numbers, never the player's. media3 knows a length only once an item is
 * prepared, and for a while it reported a still's length as its loading time — a clock built from its windows grew as it
 * played, a seek landed in the wrong piece, and the timeline (which draws by the clips' own lengths) and the click (which
 * counts project time) came apart at the joins. One sum for the picture, the lines and the sound: the model.
 */
private fun lengthsOf(clips: List<ProjectClip>): List<Long> = clips.map { it.durationMs }

/**
 * The project's clock at [posMs] into item [index]: everything before it plus the position in it. The position is kept
 * inside the piece — the player may read a moment past the model's end at a join, and the clock must not.
 */
internal fun clockAt(lengths: List<Long>, index: Int, posMs: Long): Long {
    if (lengths.isEmpty()) return posMs.coerceAtLeast(0L)
    val at = index.coerceIn(0, lengths.size - 1)
    return lengths.take(at).sum() + posMs.coerceIn(0L, lengths[at].coerceAtLeast(0L))
}

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
    audioClips: List<ProjectAudioClip>,
    beats: List<Long>,
    audioEndMs: Long,
    clickVolume: Double,
    /** The project's own level for the clips' sound under the music (0 — music only, 1 — as recorded). */
    videoVolume: Double = ProjectMusic.UNDER_MUSIC,
    /** The project's own music level: it is kept whether a track is picked or not. */
    musicVolume: Double = 1.0,
    heard: MusicClock,
    /** The project's own clock, told out every tick: where the film is, and whether it plays (the timeline draws by it). */
    onClock: (timeMs: Long, playing: Boolean) -> Unit = { _, _ -> },
    /** A jump asked from outside (a tap on the timeline), in project ms; [onSeeked] says it has been taken. */
    seek: Long? = null,
    onSeeked: () -> Unit = {},
    onScrub: (Long) -> Unit,
    keepProjectTime: Boolean = false,
    editorOpen: Boolean,
    openEditor: (() -> Unit)?,
) {
    val context = LocalContext.current
    // Every piece with its file: the real one, or — out of reach — a stand-in played as its card's frame.
    val items = clips.map { e ->
        val real = vm.previewFile(e.value.file)
        Piece(e, real ?: vm.offlineFile(e.value.file), offline = real == null)
    }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val availableProxyVersion = vm.previewProxyVersion
    var playlistProxyVersion by remember { mutableIntStateOf(availableProxyVersion) }
    // A proxy can finish while Play is being pressed. Switch copies at the next pause, when rebuilding is safe.
    SideEffect {
        if (!playing && dragging == null) playlistProxyVersion = availableProxyVersion
    }
    val signature = items.joinToString("|") { "${it.clip.file}:${it.clip.fromMs}-${it.clip.toMs}:${it.offline}:${it.clip.muteSource}" } +
        ":proxy$playlistProxyVersion"
    // Read by the clock's loop, which outlives a composition: a list captured once named the wrong card after a change.
    val onPlayingNow by rememberUpdatedState(onPlaying)
    val onClockNow by rememberUpdatedState(onClock)
    val onSeekedNow by rememberUpdatedState(onSeeked)
    val videoVolumeNow by rememberUpdatedState(videoVolume)
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
    // The preload manager reads the pieces around the current one into the same disk cache the player uses. The
    // target control keeps it to the neighbours: a project may hold hundreds of pieces, but only a few are useful now.
    val preloadIndex = remember { AtomicInteger(0) }
    // Photos and offline frames are skipped: their decode is local and quick, and their image renderer shares the
    // PhotoSink the preview draws — a preloaded next photo must not appear over the current piece.
    val noPreload = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() }
    // The stock renderers, and an image renderer after them: a still is shown by the player itself, so the playlist
    // plays photos and pieces alike and one clock counts them both.
    var prewarmVideo by remember { mutableStateOf(true) }
    val engine = remember(factory, photo, prewarmVideo) {
        // In place of the stock image renderer, not after it: the track selector gives a picture to the first one
        // that takes it, so a renderer added at the end never saw a photo (the stock one decoded it whole, to nowhere).
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildImageRenderers(out: java.util.ArrayList<Renderer>) {
                out.add(ImageRenderer(BitmapFactoryImageDecoder.Factory(context).setMaxOutputSize(STILL_SIDE), photo))
            }
        }.experimentalSetEnableMediaCodecVideoRendererPrewarming(prewarmVideo)
        val target = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { index ->
            if (index in noPreload) {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            } else when (index - preloadIndex.get()) {
                // A montage piece is often 2–4 s, but a hand-cut one can be longer: eight seconds ahead covers a whole
                // ordinary piece without leaving its tail to be fetched during playback.
                1, 2 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(8_000L)
                -1, 3, 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
        val builder = DefaultPreloadManager.Builder(context, target)
            .setMediaSourceFactory(factory)
            .setRenderersFactory(renderers)
        val player = builder.buildExoPlayer(ExoPlayer.Builder(context)).apply { playWhenReady = false }
        player to builder.build()
    }
    val player = engine.first
    val preload = engine.second
    val videoSync = remember(player) { PreviewVideoSync() }
    // The media items currently held by the manager, so a changed playlist releases exactly those.
    val loadedMedia = remember(player) { mutableListOf<MediaItem>() }
    fun preloadAt(index: Int) {
        preloadIndex.set(index)
        preload.setCurrentPlayingIndex(index)
        preload.invalidate()
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
    // The piece the player is on: its picture is what the preview shows when that piece is a still.
    var current by remember(player) { mutableIntStateOf(0) }
    val totalMs = items.sumOf { it.clip.durationMs }
    // The clock of the whole timeline: absolute milliseconds, and its total.
    var shown by remember { mutableLongStateOf(0L) }
    val shownTotal = maxOf(totalMs, audioEndMs)
    val musicPlayer = rememberProjectMusicPlayer(audioClips, shownTotal, beats, musicVolume, clickVolume)
    var audioBuffering by remember(musicPlayer) { mutableStateOf(false) }
    var audioFailed by remember(musicPlayer) { mutableStateOf(false) }

    fun setVideoSpeed(speed: Float) {
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }

    fun setPlaying(enabled: Boolean) {
        playing = enabled
        if (enabled && musicPlayer?.playerError != null) musicPlayer.prepare()
        musicPlayer?.playWhenReady = enabled && dragging == null
        player.playWhenReady = enabled && dragging == null &&
            (musicPlayer == null || musicPlayer.isPlaying || musicPlayer.playerError != null)
        if (!enabled) {
            videoSync.reset()
            setVideoSpeed(1f)
            val at = SystemClock.elapsedRealtime()
            heard.told(heard.now(at), at, false)
        }
        onClockNow(shown, enabled)
    }

    fun seekMusic(projectMs: Long) {
        val audio = musicPlayer ?: return
        heard.discontinuity()
        if (audio.currentPosition != projectMs || audio.playbackState == Player.STATE_ENDED) audio.seekTo(projectMs)
        audio.playWhenReady = playing && dragging == null
    }

    fun seekProject(projectMs: Long) {
        videoSync.reset()
        setVideoSpeed(1f)
        val lengths = lengthsOf(inPlayer.map { it.clip })
        val target = projectMs.coerceIn(0L, shownTotal)
        if (lengths.isNotEmpty()) {
            val (index, at) = seekPoint(lengths, target.coerceAtMost(lengths.sum()))
            player.seekTo(index, at)
            preloadAt(index)
        }
        seekMusic(target)
        shown = target
        onClockNow(target, playing && dragging == null)
    }

    LaunchedEffect(musicPlayer) {
        videoSync.reset()
        setVideoSpeed(1f)
        shown = shown.coerceAtMost(shownTotal)
        player.playWhenReady = playing && dragging == null && musicPlayer == null
        if (musicPlayer == null) heard.discontinuity() else seekMusic(shown)
    }

    DisposableEffect(player, musicPlayer) {
        val audio = musicPlayer
        val listener = object : Player.Listener {
            override fun onEvents(playerState: Player, events: Player.Events) {
                audioBuffering = playerState.playbackState == Player.STATE_BUFFERING
                audioFailed = playerState.playerError != null
                player.playWhenReady = playing && dragging == null && (playerState.isPlaying || audioFailed)
                if (audioFailed && clips.isEmpty() && playing) setPlaying(false)
            }

            override fun onPlayerError(error: PlaybackException) {
                vm.log("предпросмотр аудио: ${error.cause?.message ?: error.message}")
            }
        }
        if (audio != null) {
            audio.addListener(listener)
            audioBuffering = audio.playbackState == Player.STATE_BUFFERING
        }
        onDispose { audio?.removeListener(listener) }
    }

    DisposableEffect(player) {
        var joinAt = 0L
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = player.currentMediaItemIndex
                player.volume = if (inPlayer.getOrNull(current)?.clip?.muteSource == true) 0f
                    else videoVolumeNow.toFloat().coerceIn(0f, 1f)
                preloadAt(current)
                photo.shown = null
                joinAt = SystemClock.elapsedRealtime()
                vm.log("предпросмотр: стык ${player.currentMediaItemIndex + 1} из ${inPlayer.size}")
            }

            // A picture the phone cannot read must not stop the film: the piece is passed over and the rest plays on.
            override fun onPlayerError(error: PlaybackException) {
                if (prewarmVideo && (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED)) {
                    vm.log("предпросмотр: отключаю прогрев после ошибки декодера ${error.errorCodeName}")
                    prewarmVideo = false
                    return
                }
                val now = inPlayer.getOrNull(player.currentMediaItemIndex) ?: return
                if (now.clip.role != ProjectClip.Role.STILL && !now.offline) return
                photo.shown = null
                if (!player.hasNextMediaItem()) return
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
        player.addListener(listener)
        // The decoder a join has to rebuild is the reason a playlist of tiny pieces can still hitch. The log names
        // it and its start-up time, so a stutter can be told apart from a slow read.
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                vm.log("предпросмотр: видеодекодер $decoderName, запуск $initializationDurationMs мс")
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                surface: Any,
                renderTimeMs: Long,
            ) {
                if (joinAt > 0L) {
                    vm.log("предпросмотр: первый кадр после стыка через ${SystemClock.elapsedRealtime() - joinAt} мс")
                }
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                vm.log("предпросмотр: нехватка звука $bufferSize байт, пусто $bufferSizeMs мс")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                if (droppedFrames > 0) vm.log("предпросмотр: пропущено кадров $droppedFrames за $elapsedMs мс")
            }
        }
        player.addAnalyticsListener(analytics)
        onDispose {
            player.removeAnalyticsListener(analytics)
            player.removeListener(listener)
            preload.release()
            player.release()
        }
    }
    // A card in the hand: the preview stands still (its own playing got in the way of carrying a piece about).
    val openingEditor = openEditor != null
    LaunchedEffect(carrying, editorOpen, openingEditor) {
        if (carrying || editorOpen || openingEditor) setPlaying(false)
        openEditor?.invoke()
    }
    // The video's own sound: the project's slider always controls it, with or without a track. (Without music the
    // slider used to fall back to 1.0, so moving it changed the saved project and nothing that was heard.)
    LaunchedEffect(player, videoVolume, current, signature) {
        player.volume = if (inPlayer.getOrNull(player.currentMediaItemIndex)?.clip?.muteSource == true) 0f
            else videoVolume.toFloat().coerceIn(0f, 1f)
    }
    // The playlist follows the pieces: one item per piece, cut at its range. DefaultMediaSourceFactory honours the
    // clipping — a progressive source would play the whole file.
    LaunchedEffect(signature, player) {
        videoSync.reset()
        setVideoSpeed(1f)
        // A rebuild keeps the piece and the place in it: the piece is looked up in the list the player held (its index
        // meant that list), then found again in the new order — a move or an addition must not jump to another piece.
        val previous = inPlayer.getOrNull(player.currentMediaItemIndex)
        val was = previous?.clip?.let { it to player.currentPosition.coerceAtLeast(0L) }
            ?: resume
        // A piece out of reach plays as a picture held for its length: the clock and the order stay the project's own.
        val media = items.map { piece ->
            // The playlist signature keeps an old item from comparing equal to its replacement when a rebuild keeps
            // the same card (moving a card changes indices, adding one shifts them), so releasing the old is safe.
            val id = "piece-${signature.hashCode()}-${piece.at.index}"
            if (!piece.offline) mediaItem(vm, piece.clip, piece.file, id)
            else MediaItem.Builder()
                .setMediaId(id)
                .setUri(vm.offlineStill(piece.file, piece.clip.fromMs))
                .setImageDurationMs(piece.clip.durationMs.coerceAtLeast(1L))
                .build()
        }
        // The preloader reads the neighbours into the same disk cache; the player then opens its own sources and
        // finds those bytes already cached. Keeping the two source sets apart means a playlist rebuild can never
        // release a source out from under the player.
        val oldMedia = loadedMedia.toList()
        loadedMedia.clear()
        noPreload.clear()
        items.forEachIndexed { index, piece ->
            if (piece.offline || piece.clip.role == ProjectClip.Role.STILL) noPreload.add(index)
        }
        preload.addMediaItems(media, media.indices.toList())
        loadedMedia.addAll(media)
        // The player takes the preloaded sources themselves: setMediaItems(MediaItem) would build fresh sources
        // and throw away everything the preloader has read into the cache.
        val sources = media.map { item -> preload.getMediaSource(item) ?: factory.createMediaSource(item) }
        val at = was?.let { (clip, _) ->
            val sameCard = items.indexOfFirst { it.at.index == previous?.at?.index && it.clip == clip }
            if (sameCard >= 0) sameCard else items.indexOfFirst { it.clip == clip }
        } ?: -1
        val absolute = if (keepProjectTime && items.isNotEmpty()) seekPoint(lengthsOf(items.map { it.clip }), seek ?: shown) else null
        val startAt = absolute?.first ?: at.coerceAtLeast(0)
        val position = absolute?.second ?: if (at >= 0) minOf(was!!.second, items[at].clip.durationMs) else 0L
        player.playWhenReady = false
        inPlayer.clear()
        inPlayer.addAll(items)
        player.volume = if (items.getOrNull(startAt)?.clip?.muteSource == true) 0f
            else videoVolume.toFloat().coerceIn(0f, 1f)
        if (sources.isEmpty()) player.clearMediaItems() else player.setMediaSources(sources, startAt, position)
        if (oldMedia.isNotEmpty()) preload.removeMediaItems(oldMedia)
        preloadAt(startAt)
        player.prepare()
        player.playWhenReady = playing && dragging == null &&
            (musicPlayer == null || musicPlayer.isPlaying || musicPlayer.playerError != null)
    }
    LaunchedEffect(player, musicPlayer, shownTotal, totalMs) {
        while (true) {
            val now = inPlayer.toList()
            val lengths = lengthsOf(now.map { it.clip })
            val pictureAt = clockAt(lengths, player.currentMediaItemIndex, player.currentPosition)
            val at = SystemClock.elapsedRealtime()
            val musicAt = musicPlayer?.takeIf { it.playerError == null }?.currentPosition
            val musicRunning = musicAt != null && musicPlayer?.isPlaying == true
            heard.told(musicAt, at, musicRunning)
            if (dragging == null && (playing || musicAt != null || now.isNotEmpty() && shown <= totalMs)) {
                shown = (musicAt ?: pictureAt).coerceIn(0L, shownTotal)
                val videoEnded = player.playbackState == Player.STATE_ENDED
                if (playing && (musicAt != null && (shown >= shownTotal || musicPlayer?.playbackState == Player.STATE_ENDED) ||
                    musicAt == null && videoEnded)) {
                    setPlaying(false)
                }
            }
            val syncAt = musicAt?.takeIf {
                playing && dragging == null && musicRunning && player.isPlaying && lengths.isNotEmpty() && it < totalMs
            }
            val correction = videoSync.update(pictureAt, syncAt, at, player.playbackParameters.speed)
            setVideoSpeed(correction.speed)
            correction.seekToMs?.let { target ->
                val (index, pos) = seekPoint(lengths, target)
                player.seekTo(index, pos)
                preloadAt(index)
                vm.log("предпросмотр: видео догоняет музыку вперёд на ${target - pictureAt} мс после задержки")
            }
            now.getOrNull(player.currentMediaItemIndex)?.let { resume = it.clip to player.currentPosition.coerceAtLeast(0L) }
            // Told to the screen: its card is marked in the strip and the strip follows it.
            onPlayingNow(now.getOrNull(player.currentMediaItemIndex)?.at?.index?.takeIf { shown <= totalMs })
            // And the project's own clock, for the timeline: the line it draws must move with the picture, so the loop
            // is quick while the film plays and slow while it stands.
            onClockNow(dragging?.toLong() ?: shown, playing && dragging == null)
            // The line and the slider need a moving clock, not a 20 Hz recomposition of the whole screen: at
            // 20 Hz the UI missed ~21 % of frame deadlines on the phone while the film was playing (gfxinfo).
            delay(if (playing) 100 else 200)
        }
    }
    // A card tapped: the preview jumps to that piece — video or photo alike — and plays on from it.
    LaunchedEffect(play, signature, editorOpen, openingEditor) {
        val at = inPlayer.indexOfFirst { it.at.index == play }
        if (at >= 0 && !editorOpen && !openingEditor) {
            seekProject(clockAt(lengthsOf(inPlayer.map { it.clip }), at, 0L))
            setPlaying(true)
        }
        if (play != null) onPlayed()
    }
    // A jump asked from outside — a tap on the timeline — by the very arithmetic the slider uses, so a tap lands where
    // the strip's own seek would. The film does not start by itself: the tap moves the picture, it does not press play.
    LaunchedEffect(seek, signature) {
        val ms = seek ?: return@LaunchedEffect
        seekProject(ms)
        onSeekedNow()
    }

    Column(Modifier.fillMaxWidth()) {
        if (clips.isNotEmpty()) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; setKeepContentOnPlayerReset(true) } },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { it.player = null },
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
            IconButton(onClick = {
                if (!playing && shownTotal > 0L && shown >= shownTotal) seekProject(0L)
                setPlaying(!playing)
            }, enabled = clips.isNotEmpty() || musicPlayer != null) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) tr("Пауза", "Pause") else tr("Играть", "Play"),
                )
            }
            Slider(
                value = dragging ?: shown.toFloat(),
                onValueChange = {
                    if (dragging == null) {
                        musicPlayer?.playWhenReady = false
                        player.playWhenReady = false
                        heard.discontinuity()
                    }
                    dragging = it
                    val at = it.toLong()
                    onScrub(at)
                    onClockNow(at, false)
                },
                onValueChangeFinished = {
                    val position = dragging?.toLong()
                    dragging = null
                    position?.let {
                        seekProject(it)
                        setPlaying(playing)
                    }
                },
                valueRange = 0f..maxOf(shownTotal, 1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(((dragging?.toLong() ?: shown) / 1000).toInt()) + " / " + formatDuration((shownTotal / 1000).toInt()),
                Modifier.padding(start = 8.dp),
                fontSize = 12.sp,
            )
        }
        if (audioFailed || playing && audioBuffering) {
            Text(
                if (audioFailed) tr("Трек не читается", "The track could not be read")
                else tr("Загружаем звук…", "Buffering audio…"),
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (audioFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
