package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.ProjectExport
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.AudioLane
import app.goultra.transfer.camera.TrackAudio


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
    val loaded by produceState<Map<String, List<Highlight>>>(emptyMap(), p.id, p.clips.map { it.file }.distinct(), vm.piecesVersion) {
        val all = mutableMapOf<String, List<Highlight>>()
        for (name in p.clips.map { it.file }.distinct()) {
            val f = vm.projectFile(name) ?: continue
            val limit = f.durationSec?.let { (it + 1) * 1000L }
            val own = vm.editedPieces(f)
            all[name] = Highlights.inEffect(own, vm.highlights(f), limit, f.durationSec?.let { it * 1000L })
        }
        value = all
    }
    val stale = remember(loaded, p.clips) { p.staleClips(loaded).toSet() }
    val totalMs = p.clips.sumOf { it.durationMs }
    val audio = remember(p.audioClips) { AudioLane(p.audioClips) }
    val audioBeats = remember(audio) { audio.marks { it.beats } }

    // Cards are told apart by their place in [Project.clips], not by equality: the same piece may be in twice.
    var play by remember(p.id) { mutableStateOf<Int?>(null) }
    var playingNow by remember(p.id) { mutableStateOf<Int?>(null) }
    var pieceToEdit by remember(p.id) { mutableStateOf<Pair<CameraFile, Long>?>(null) }
    val editorOpen = vm.viewing != null

    var carrying by remember(p.id) { mutableStateOf(false) }
    var saving by remember(p.id) { mutableStateOf(false) }
    var choosingAudio by remember(p.id) { mutableStateOf(false) }
    // The film's own clock, told out by the preview and drawn by the timeline; and a jump the timeline asks for.
    var clockMs by remember(p.id) { mutableLongStateOf(0L) }
    val musicClock = remember(p.id) { MusicClock() }
    var clockPlaying by remember(p.id) { mutableStateOf(false) }
    var seekTo by remember(p.id) { mutableStateOf<Long?>(null) }
    val dropSelection = remember(p.id) { mutableStateOf<DropSelection?>(null) }
    val dropEditorClipId = remember(p.id) { mutableStateOf<String?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    var timelineBounds by remember { mutableStateOf(Rect.Zero) }
    fun selectAt(ms: Long, clipId: String? = null) {
        (clipId ?: audio.clipAt(ms)?.id)?.let(vm::selectAudioClip)
    }
    // Low-resolution copies are made only while the film stands still: the encoder and the player share the phone's
    // hardware, so encoding during playback is exactly the stutter the copies are meant to remove.
    LaunchedEffect(p.id, clockPlaying, editorOpen) {
        vm.prepareProjectProxies(p, clockMs, paused = !clockPlaying && !editorOpen)
    }
    DisposableEffect(p.id) {
        onDispose { vm.stopProjectProxies() }
    }

    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() }
            .pointerInput(p.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!timelineBounds.contains(screenOrigin + down.position)) {
                        dropSelection.value = null
                        dropEditorClipId.value = null
                    }
                }
            },
    ) {
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
            if (p.clips.isNotEmpty()) {
                // The progress itself, not the jobs: a job is still active while its last state is set, and the
                // button read that way stayed off for good after a failed save.
                IconButton({ saving = true }, enabled = vm.download == null && vm.export == null) {
                    Icon(Icons.Filled.Save, tr("Сохранить видео", "Save the video"))
                }
            }
        }
        for (analysis in vm.musicReanalyses(p.id)) {
            val stage by analysis.stage.collectAsState()
            val status = when (stage) {
                TrackAudio.Stage.READING -> tr("Читаем аудио", "Reading audio")
                TrackAudio.Stage.FINDING_BEATS -> tr("Проверяем ритм", "Checking rhythm")
                TrackAudio.Stage.FINDING_ACCENTS -> tr("Ищем дропы", "Finding drops")
                TrackAudio.Stage.APPLYING -> tr("Обновляем метки", "Updating markers")
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "$status · ${analysis.name}", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 3.dp))
            }
        }
        // The low-resolution copy is being encoded: show it, otherwise a paused project looks idle while the phone
        // is busy for a minute or more.
        vm.previewCacheTask?.let { task ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Готовлю кэш предпросмотра: файл %s из %s", "Preparing the preview cache: file %s of %s")
                            .format(task.file, task.count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
        }
        // A save under way: the app's own bars are hidden on this screen, so it shows here, with its «Стоп».
        val copying = vm.download
        val encoding = vm.export
        if (copying != null || encoding != null) {
            val share = encoding?.let { it.percent / 100f }
                ?: copying?.let { if (it.total > 0) (it.done.toFloat() / it.total).coerceIn(0f, 1f) else 0f } ?: 0f
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (encoding?.name ?: copying?.name).orEmpty() + " · " + (share * 100).toInt() + " %",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator({ share }, Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                TextButton(vm::cancelExport) { Text(tr("Стоп", "Stop")) }
            }
        }
        if (saving) {
            SaveProjectDialog(vm, p, onDismiss = { saving = false })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Every piece, photos included: a still plays as its own picture, held for its length (asked for on the phone,
            // 07.11); a piece out of reach plays as its card's frame, marked, so the project can be worked on without the
            // camera and plays for real once it is back.
            val pieces = p.clips.withIndex().toList()
            if (pieces.isNotEmpty() || p.audioClips.isNotEmpty()) {
                ProjectPreview(
                    vm, pieces, play,
                    onPlayed = { play = null },
                    onPlaying = { playingNow = it },
                    // A card in the hand means the preview stands still: its own moving about got in the way of the drag.
                    carrying = carrying,
                    audioClips = p.audioClips,
                    beats = audioBeats,
                    audioEndMs = p.audioEndMs,
                    clickVolume = p.clickVolume,
                    videoVolume = p.videoVolume,
                    musicVolume = p.musicVolume,
                    heard = musicClock,
                    onClock = { ms, plays ->
                        clockMs = ms
                        clockPlaying = plays
                    },
                    seek = seekTo,
                    onSeeked = { seekTo = null },
                    onScrub = { ms -> selectAt(ms) },
                    editorOpen = editorOpen || choosingAudio,
                    openEditor = pieceToEdit?.let { (file, fromMs) ->
                        {
                            play = null
                            pieceToEdit = null
                            vm.viewProjectPiece(file, fromMs)
                        }
                    },
                )
            }
            if (p.clips.isEmpty() && p.audioClips.isEmpty()) {
                Text(
                    tr(
                        "Пусто: добавьте файлы из списка кнопкой «В проект»",
                        "Empty: add files from the list with «Add to project»",
                    ),
                    Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (p.clips.isNotEmpty()) {
                ProjectStrip(
                    vm, p, stale, playingNow, onPlay = { play = it }, onCarrying = { carrying = it },
                    onEdit = { file, clip -> pieceToEdit = file to clip.fromMs },
                )
                // The same pieces in time: as wide as they last, the track under them, one line for the clock.
            }
            if (p.clips.isNotEmpty() || p.audioClips.isNotEmpty()) {
                Column(Modifier.onGloballyPositioned { timelineBounds = it.boundsInRoot() }) {
                    ProjectTimeline(
                        vm, p, clockMs, clockPlaying, musicClock, audioBeats, dropSelection, dropEditorClipId,
                        onSeek = { ms, clip ->
                            selectAt(ms, clip)
                            seekTo = ms
                        },
                    )
                }
            }
        }
        ProjectMusicLane(vm, p, clockMs, choosingAudio, onChooseAudio = { choosingAudio = it },
            onOpenDrops = {
                val active = vm.activeAudioClip(p)
                val clip = active?.takeIf { it.contains(clockMs) } ?: audio.clipAt(clockMs) ?: active
                dropSelection.value = null
                dropEditorClipId.value = clip?.id
            },
        )
    }
}
/**
 * How to save the project as one video: as shot (fast, pieces start on key frames) or precisely (re-encoded, cut where
 * marked, photos held) — always asked, since the two differ in both time and result. A project with a photo is only
 * saved precisely; one with a piece out of reach waits for the camera.
 */
@Composable
private fun SaveProjectDialog(vm: CameraController, p: Project, onDismiss: () -> Unit) {
    val canCopy = ProjectExport.canCopy(p.clips)
    val coloured = remember(p.clips) { vm.projectHasColour(p) }
    var precise by remember { mutableStateOf(!canCopy || coloured) }
    var sound by remember { mutableStateOf(true) }
    val away = vm.projectUnreachable(p)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Save, null) },
        title = { Text(tr("Сохранить видео проекта", "Save the project's video")) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = canCopy) { precise = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(!precise, { precise = false }, enabled = canCopy)
                    Column {
                        Text(tr("Быстро — как снято", "Fast — as shot"))
                        Text(
                            if (!canCopy) tr("В проекте есть фото — только точно", "The project has photos — precise only")
                            else if (coloured) tr(
                                "Без перекодирования; цвет, настроенный в файлах, не сохранится",
                                "No re-encoding; the colour set in the files is not kept",
                            ) else tr(
                                "Без перекодирования, качество оригинала; куски начинаются с ближайшего ключевого кадра",
                                "No re-encoding, the original quality; clips start on the nearest key frame",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (canCopy && coloured) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clickable { precise = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(precise, { precise = true })
                    Column {
                        Text(tr("Точно — с перекодированием", "Precise — re-encoded"))
                        Text(
                            tr(
                                "Каждый кусок ровно по отметкам, с цветом файлов, фото — свою длину; заметно дольше",
                                "Every clip exactly as marked, with the files' colour, photos for their length; takes much longer",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clickable { sound = !sound },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(sound, { sound = it })
                    Text(tr("Со звуком", "With sound"))
                }
                if (away > 0) {
                    Text(
                        tr("%s кусков без файла — подключите камеру", "%s clips without their file — connect the camera").format(away),
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton({ vm.exportProject(p, precise, sound); onDismiss() }, enabled = away == 0) {
                Text(tr("Сохранить", "Save"))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
