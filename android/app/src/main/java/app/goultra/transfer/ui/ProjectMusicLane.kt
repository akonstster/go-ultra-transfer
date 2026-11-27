package app.goultra.transfer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.MusicGrid
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectMusic
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.Multicam
import kotlin.math.roundToInt

/** Which sheet stands open under the project (none, the sound's, the tempo's, the montage's). */
private enum class Sheet { NONE, SOUND, TEMPO, MONTAGE, HISTORY }

/**
 * Project tools and audio analysis status. The timeline displays the tracks and edits their drop markers.
 */
@Composable
internal fun ProjectMusicLane(
    vm: CameraController, p: Project, clockMs: Long, choosingAudio: Boolean,
    onChooseAudio: (Boolean) -> Unit, onOpenDrops: () -> Unit, onOpenMulticam: () -> Unit,
) {
    val audio = vm.activeAudioClip(p)
    val music = audio?.source
    val tracks = p.audioClips.map { it.source }.distinctBy { it.uri }
    LaunchedEffect(tracks.map { it.uri }) { tracks.forEach(vm::analyseMusic) }
    val peaks = remember(music?.uri, vm.musicVersion) { music?.let { vm.musicPeaks(it.uri) } }
    val failed = music != null && music.uri in vm.musicFailed
    // The wave is written before the grid and drops are saved: the lane must not stop saying «analysing» at the wave.
    val analysing = music != null && music.uri in vm.musicAnalysing
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        // An explicit panel with a scrolling row: three equal-weight buttons squeezed the last one («Монтаж» was cut)
        // on a phone, and a toolbox reads better as one surface than as loose chips over the cards.
        Surface(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                    Text(
                        tr("Инструменты", "Tools"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (music != null) TrackState(peaks, failed, analysing)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tool(tr("Звук", "Sound"), Icons.Filled.VolumeUp) { sheet = Sheet.SOUND }
                    Tool(tr("Темп", "Tempo"), Icons.Filled.Speed) { sheet = Sheet.TEMPO }
                    if (audio != null) Tool(tr("Дропы", "Drops"), Icons.Filled.Diamond, onOpenDrops)
                    Tool(tr("Монтаж", "Montage"), Icons.Filled.ContentCut) { sheet = Sheet.MONTAGE }
                    Tool(tr("История", "History"), Icons.Filled.History) { sheet = Sheet.HISTORY }
                    Tool(tr("Мультикам", "Multicam"), Icons.Filled.Videocam, onOpenMulticam)
                }
            }
        }
    }
    when (sheet) {
        Sheet.SOUND -> SoundSheet(vm, p, audio, onPick = { onChooseAudio(true) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.TEMPO -> audio?.let { TempoSheet(vm, it.source, it.sourceAt(clockMs), onDismiss = { sheet = Sheet.NONE }) }
        Sheet.MONTAGE -> MontageSheet(vm, p, onDismiss = { sheet = Sheet.NONE })
        Sheet.HISTORY -> HistorySheet(vm, p.id, onDismiss = { sheet = Sheet.NONE })
        Sheet.NONE -> Unit
    }
    if (choosingAudio) {
        ProjectAudioPicker(vm, p, onDismiss = { onChooseAudio(false) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(vm: CameraController, projectId: String, onDismiss: () -> Unit) {
    val undo = vm.projectHistoryActions(projectId, redo = false)
    val redo = vm.projectHistoryActions(projectId, redo = true)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(tr("История", "History"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryButton(Modifier.weight(1f), tr("Отменить", "Undo"), undo.firstOrNull()) {
                    vm.restoreProjectEdit(redo = false)
                }
                HistoryButton(Modifier.weight(1f), tr("Повторить", "Redo"), redo.firstOrNull()) {
                    vm.restoreProjectEdit(redo = true)
                }
            }
            if (undo.isEmpty() && redo.isEmpty()) {
                Text(tr("История пока пуста", "No edits yet"))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (undo.isNotEmpty()) {
                        item { HistoryHeading(tr("Применено", "Applied")) }
                        itemsIndexed(undo) { index, action -> HistoryActionRow(action, index == 0) }
                    }
                    if (redo.isNotEmpty()) {
                        item { HistoryHeading(tr("Отменено", "Undone")) }
                        itemsIndexed(redo) { index, action -> HistoryActionRow(action, index == 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryButton(modifier: Modifier, label: String, next: Phrase?, onClick: () -> Unit) {
    Column(modifier) {
        FilledTonalButton(onClick, enabled = next != null) { Text(label) }
        if (next != null) {
            Text(next.text, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HistoryHeading(title: String) {
    Text(title, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun HistoryActionRow(action: Phrase, next: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (next) colors.secondaryContainer else colors.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(action.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** A toolbox button: a fixed, readable card. It never takes an equal share of the row, so its label can scroll. */
@Composable
private fun Tool(name: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.width(96.dp).height(62.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
/** All the sound there is: the track (picked, replaced, taken off) and the two levels under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundSheet(vm: CameraController, project: Project, audio: ProjectAudioClip?, onPick: () -> Unit, onDismiss: () -> Unit) {
    val music = audio?.source
    var deleting by remember { mutableStateOf<ProjectAudioClip?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(tr("Звук", "Sound"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    music?.name?.ifEmpty { tr("Трек", "The track") } ?: tr("Трека нет", "No track"),
                    Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onPick) {
                    Icon(Icons.Filled.Add, tr("Добавить файлы", "Add files"))
                }
                if (audio != null && (project.multicam == null || audio.id != Multicam.MASTER_ID)) {
                    IconButton({ deleting = audio }) { Icon(Icons.Filled.Close, tr("Убрать трек", "Remove the track")) }
                }
            }
            // The mix is the project's own: it stays adjustable while no track is picked, and a new track inherits it.
            MusicSound(vm, project)
            if (audio != null) {
                Level(tr("Выбранный клип", "Selected clip"), audio.volume, audio.id) { level ->
                    vm.updateAudioClip(audio.id, Phrase("Изменена громкость аудиоклипа", "Changed audio clip volume")) {
                        it.copy(volume = level)
                    }
                }
            }
        }
    }
    deleting?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Убрать кусок из проекта?", "Remove the clip from the project?")) },
            text = { Text(clip.source.name) },
            confirmButton = {
                TextButton({ vm.removeProjectMusic(clip.id); deleting = null }) { Text(tr("Убрать", "Remove")) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** All the rhythm there is: the grid by hand, the tap, the metronome that sounds it, and the drop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TempoSheet(vm: CameraController, music: ProjectMusic, sourceClockMs: Long, onDismiss: () -> Unit) {
    val bpm = remember(music.beats) { MusicGrid.bpm(music.beats) }
    val range = remember(music.beats) { MusicGrid.tempoRange(music.beats) }
    val moving = remember(music.beats) { MusicGrid.isVariable(music.beats) }
    val rigidBpm = remember(music.beats) { MusicGrid.rigidBpm(music.beats) }
    val currentBpm = rigidBpm ?: MusicGrid.bpmAt(music.beats, sourceClockMs)
    var fixedTempo by remember(music.uri) { mutableStateOf<Pair<Long, String>?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Темп", "Tempo"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                // A tenth of a beat a minute matters: 120.4 is not 120, and a grid a half BPM off walks away in a minute.
                Text(
                    if (currentBpm > 0.0) "%.1f BPM".format(currentBpm) else "—",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }
            Text(music.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (moving && range != null) {
                Text(
                    String.format(tr("переменный %.1f–%.1f", "variable %.1f–%.1f"), range.first, range.second),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GridRow(vm, music, bpm, moving) {
                fixedTempo = sourceClockMs to if (currentBpm > 0.0) "%.1f".format(currentBpm) else ""
            }
            // The analysis says how sure it is; a low certainty is shown where the grid is corrected, not buried in a log.
            if (music.gridVersion >= app.goultra.transfer.camera.BeatGrid.ANALYSIS_VERSION &&
                music.beats.isNotEmpty() && music.gridConfidence < 0.20
            ) {
                Text(
                    tr(
                        "Бит найден неуверенно — проверьте сетку в «Темпе»",
                        "The beat was found unsure — check the grid in Tempo",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(tr("Метроном", "Metronome"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(AppPrefs.metronome, { AppPrefs.chooseMetronome(it) })
            }
        }
    }
    fixedTempo?.let { (anchor, suggestion) ->
        var entered by remember { mutableStateOf(suggestion) }
        val value = entered.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
        AlertDialog(
            onDismissRequest = { fixedTempo = null },
            title = { Text(tr("Сделать темп постоянным", "Make tempo constant")) },
            text = {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    label = { Text("BPM") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton({
                    val wanted = value ?: return@TextButton
                    vm.editMusicGrid { beats, length -> MusicGrid.constantAt(beats, length, wanted, anchor) }
                    fixedTempo = null
                }, enabled = value != null) { Text(tr("Применить", "Apply")) }
            },
            dismissButton = { TextButton({ fixedTempo = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
/**
 * The grid by hand (P3, plan §1): a tap tempo against what the analysis found, a beat a minute slower or faster, the
 * pace halved or doubled, and the whole grid shifted a few ms. Two rows, the tempo over the shape, so nothing crowds.
 */
@Composable
private fun GridRow(vm: CameraController, music: ProjectMusic, bpm: Double, moving: Boolean, onFixedTempo: () -> Unit) {
    // A tempo that moves cannot be nudged as one number — stretching the whole grid about its first beat would only put
    // the error somewhere else. The shift stays (the grid may sit early or late as a whole), the rest is off.
    // Taps: a few quick ones give the beat; two seconds of silence start them over.
    var taps by remember(music.uri) { mutableStateOf(emptyList<Long>()) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                {
                    val now = System.currentTimeMillis()
                    val next = if (now - (taps.lastOrNull() ?: 0L) > 2_000L) listOf(now) else taps + now
                    taps = next
                    vm.editMusicGrid { b, len -> MusicGrid.fromTap(b.firstOrNull() ?: 0L, next, len) }
                },
                enabled = !moving,
            ) { Text(tr("Тап", "Tap")) }
            IconButton(
                { vm.editMusicGrid { b, len -> MusicGrid.stretched(b, bpm / (bpm - 0.1).coerceAtLeast(0.1), len) } },
                enabled = !moving && bpm > 0.2,
            ) { Icon(Icons.Filled.Remove, null) }
            IconButton(
                { vm.editMusicGrid { b, len -> MusicGrid.stretched(b, bpm / (bpm + 0.1), len) } },
                enabled = !moving && bpm > 0.0,
            ) { Icon(Icons.Filled.Add, null) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.editMusicGrid { b, len -> MusicGrid.stretched(b, 0.5, len) } }, enabled = !moving) { Text("×2") }
            TextButton({ vm.editMusicGrid { b, len -> MusicGrid.stretched(b, 2.0, len) } }, enabled = !moving) { Text("÷2") }
            IconButton({ vm.editMusicGrid { b, _ -> MusicGrid.shifted(b, -5L) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
            }
            IconButton({ vm.editMusicGrid { b, _ -> MusicGrid.shifted(b, 5L) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        if (music.lengthMs > 0L) {
            TextButton(onFixedTempo) {
                Text(tr("Сделать темп постоянным", "Make tempo constant"))
            }
        }
    }
}

/**
 * How the track stands: its wave and its grid are on the timeline now, under the picture — here only whether it is
 * being read, or not readable at all.
 */
@Composable
private fun TrackState(peaks: FloatArray?, failed: Boolean, analysing: Boolean) {
    if (!analysing && (peaks != null && peaks.isNotEmpty())) return
    val text = when {
        failed -> tr("Трек не читается", "The track could not be read")
        analysing || peaks == null || peaks.isEmpty() -> tr("Анализируем трек…", "Analysing the track…")
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (analysing) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two levels, one under the other: the music's, and the video's own sound under it (0 — music only, 100 — recorded).
 * Both belong to the project, not to the picked file: removing the track and picking another leaves them as they were.
 */
@Composable
private fun MusicSound(vm: CameraController, project: Project) {
    Level(tr("Музыка", "Music"), project.musicVolume, "music") { v -> vm.setProjectMusicVolume(v) }
    Level(tr("Звук роликов", "Clips' sound"), project.videoVolume, "video") { v -> vm.setProjectVideoVolume(v) }
    Level(tr("Клик", "Click"), project.clickVolume, "click") { v -> vm.setProjectClickVolume(v) }
}

/** One level: its name, a slider over the rest of the row, and its percent. Kept when the finger lets go. */
@Composable
private fun Level(name: String, value: Double, key: String, onSet: (Double) -> Unit) {
    var level by remember(key, value) { mutableStateOf(value.toFloat().coerceIn(0f, 1f)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(
            name,
            Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { onSet(level.toDouble()) },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "${(level * 100).roundToInt()} %",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
