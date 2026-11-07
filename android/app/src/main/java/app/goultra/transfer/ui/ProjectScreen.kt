package app.goultra.transfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.MediaKind
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectClip

/**
 * A project's own screen: the pieces it is made of, in the order they will play (see `memory-bank/projects-plan.md`).
 * Dragging the cards around, the base with its cutaways, the preview and the export come next; the file behind a piece
 * is edited in the usual film strip — the card opens it.
 */
@Composable
fun ProjectScreen(vm: CameraController, p: Project) {
    // The files' pieces as they stand: a clip whose range is gone from its file is marked (plan §7). Re-read on opening
    // and after every edit in the file editor ([CameraController.piecesVersion]).
    val pieces by produceState<Map<String, List<Highlight>>>(emptyMap(), p.id, p.clips.size, vm.piecesVersion) {
        value = p.clips.map { it.file }.distinct().mapNotNull { name ->
            vm.projectFile(name)?.let { f ->
                val limit = f.durationSec?.let { (it + 1) * 1000L }
                name to Highlights.inEffect(vm.editedPieces(f), vm.highlights(f), limit, f.durationSec?.let { it * 1000L })
            }
        }.toMap()
    }
    val stale = remember(pieces, p.clips) { p.staleClips(pieces).toSet() }
    val totalMs = p.clips.sumOf { it.durationMs }

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
        if (playable.isNotEmpty()) ProjectPreview(vm, playable)
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
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(p.clips, key = { i, c -> "$i-${c.file}-${c.fromMs}" }) { _, clip ->
                    // «The file is not on the list any more» is only said when the list is complete: with the camera
                    // away a file it holds is simply out of reach, not gone.
                    ClipCard(
                        vm, p, clip, vm.projectFile(clip.file),
                        stale = clip in stale,
                        missing = vm.projectFile(clip.file) == null && vm.conn == ConnState.Connected,
                    )
                }
            }
        }
    }
}

/**
 * One piece of the project: its frame, how long it plays, whether the auto montage put it there, and its ✕. The card
 * opens the file — the piece itself is edited in the usual film strip, and the project picks the edit up (§7).
 */
@Composable
private fun ClipCard(vm: CameraController, p: Project, clip: ProjectClip, file: CameraFile?, stale: Boolean, missing: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, clip.file, clip.fromMs, clip.role) {
        value = file?.let {
            // A photo is not a video with a frame at a time: it has its own tile picture.
            if (clip.role == ProjectClip.Role.STILL) vm.thumbnail(it) else vm.timelineFrame(it, clip.fromMs)
        }
    }
    Column(Modifier.width(150.dp)) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0x22000000))
                .clickable(enabled = file != null) { vm.viewing = file },
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                formatDuration((clip.durationMs / 1000).toInt()),
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 11.sp,
            )
            if (clip.auto) {
                Text(
                    tr("Авто", "Auto"),
                    Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color(0xAA0B6E4F), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
            if (p.baseFile == clip.file) {
                Text("★", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.White, fontSize = 14.sp)
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
}

/**
 * The project's preview: the pieces in the order they will play, each cut to its own range. They are a media3 playlist
 * — the pieces one after another for now; the base with its cutaways needs the montage engine (P3). A camera file plays
 * its light copy over Wi-Fi and a phone copy plays itself — the same sources the viewer uses.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ProjectPreview(vm: CameraController, clips: List<ProjectClip>) {
    val context = LocalContext.current
    val signature = clips.joinToString("|") { "${it.file}:${it.fromMs}-${it.toMs}" }
    // The camera's own data source (its Wi-Fi is bound to this app) under the stock one: content and file URIs keep
    // going to the phone's own readers.
    val factory = remember(vm.conn) {
        val camera = vm.previewSource()
        if (camera == null) DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        else DefaultMediaSourceFactory(DefaultDataSource.Factory(context, camera))
    }
    val player = remember(factory) { ExoPlayer.Builder(context, factory).build().apply { playWhenReady = false } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val totalMs = clips.sumOf { it.durationMs }

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
        player.setMediaItems(
            clips.mapNotNull { clip ->
                val file = vm.projectFile(clip.file) ?: return@mapNotNull null
                val uri = if (file.isOnPhone) file.localUri ?: clip.file else vm.previewUrl(file)
                MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.fromMs)
                            .setEndPositionMs(clip.toMs)
                            .build(),
                    )
                    .build()
            },
        )
        player.prepare()
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = player.currentPosition.toInt()
            delay(250)
        }
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
                value = dragging ?: position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..maxOf(totalMs, 1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration((position / 1000).toInt()) + " / " + formatDuration((totalMs / 1000).toInt()),
                Modifier.padding(start = 8.dp),
                fontSize = 12.sp,
            )
        }
    }
}