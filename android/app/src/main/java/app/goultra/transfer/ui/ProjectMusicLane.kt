package app.goultra.transfer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.MusicGrid
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectMusic
import kotlin.math.roundToInt

/** Which sheet stands open under the project (none, the sound's, the tempo's, the montage's). */
private enum class Sheet { NONE, SOUND, TEMPO, MONTAGE }

/**
 * The project's music (see `memory-bank/projects-plan.md`, P2/P3): the track it holds, and a toolbox under it. All the
 * sound lives in the «Sound» sheet — the track itself and the two levels; all the rhythm in «Tempo» — the grid by hand,
 * the tap, the metronome and the drop. The cards and the timeline keep the room they need, and nothing is heard or
 * changed by accident.
 */
@Composable
internal fun ProjectMusicLane(vm: CameraController, p: Project, clockMs: Long = 0L) {
    // SAF: the app keeps a lasting link to the track (no copy) — the same way files are added to the list.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setProjectMusic(uri)
    }
    val music = p.music
    // A track put in elsewhere (or picked a moment ago) is analysed here: the timeline draws its grid once it is done.
    LaunchedEffect(music?.uri) { music?.let { vm.analyseMusic(it) } }
    val peaks = remember(music?.uri, vm.musicVersion) { music?.let { vm.musicPeaks(it.uri) } }
    val failed = music != null && music.uri in vm.musicFailed
    var sheet by remember { mutableStateOf(Sheet.NONE) }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LibraryMusic,
                null,
                Modifier.padding(end = 6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                music?.name?.ifEmpty { tr("Трек", "The track") } ?: tr("Музыки нет", "No music"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only a track has a state to report: without one there is nothing being analysed.
            if (music != null) TrackState(peaks, failed)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool(tr("Звук", "Sound"), Icons.Filled.VolumeUp) { sheet = Sheet.SOUND }
            Tool(tr("Темп", "Tempo"), Icons.Filled.Speed) { sheet = Sheet.TEMPO }
            Tool(tr("Монтаж", "Montage"), Icons.Filled.ContentCut) { sheet = Sheet.MONTAGE }
        }
    }
    when (sheet) {
        Sheet.SOUND -> SoundSheet(vm, p, onPick = { pick.launch(arrayOf("audio/*")) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.TEMPO -> music?.let { TempoSheet(vm, it, clockMs, onDismiss = { sheet = Sheet.NONE }) }
        Sheet.MONTAGE -> MontageSheet(vm, p, onDismiss = { sheet = Sheet.NONE })
        Sheet.NONE -> Unit
    }
}

/** A toolbox button: its icon and its name, the two of them the whole of it. */
@Composable
private fun Tool(name: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}
/** All the sound there is: the track (picked, replaced, taken off) and the two levels under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundSheet(vm: CameraController, project: Project, onPick: () -> Unit, onDismiss: () -> Unit) {
    val music = project.music
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
                    Text(if (music == null) tr("Выбрать трек", "Choose a track") else tr("Заменить трек", "Replace the track"))
                }
                if (music != null) {
                    IconButton(vm::removeProjectMusic) { Icon(Icons.Filled.Close, tr("Убрать трек", "Remove the track")) }
                }
            }
            // The mix is the project's own: it stays adjustable while no track is picked, and a new track inherits it.
            MusicSound(vm, project)
        }
    }
}

/** All the rhythm there is: the grid by hand, the tap, the metronome that sounds it, and the drop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TempoSheet(vm: CameraController, music: ProjectMusic, clockMs: Long, onDismiss: () -> Unit) {
    val bpm = MusicGrid.bpm(music.beats)
    // A tempo that moves is not one number: it is a range, and it must be said so — 118.6 BPM would be a lie about a
    // track that goes from 100 to 130, and a nudge of a tenth would move it nowhere true.
    val range = MusicGrid.tempoRange(music.beats)
    val moving = MusicGrid.isVariable(music.beats)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Темп", "Tempo"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                // A tenth of a beat a minute matters: 120.4 is not 120, and a grid a half BPM off walks away in a minute.
                Text(
                    when {
                        bpm <= 0.0 -> "—"
                        range != null && moving ->
                            String.format(tr("переменный %.1f–%.1f", "variable %.1f–%.1f"), range.first, range.second)
                        else -> "%.1f BPM".format(bpm)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }
            GridRow(vm, music)
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
            ClickNudge(AppPrefs.metronome)
            DropControls(vm, music, clockMs)
            Text(
                tr(
                    "Дроп — место, где энергия трека скачет: сюда автомонтаж поставит акцент",
                    "The drop is where the track's energy leaps: the montage will put an accent there",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
/**
 * All the drops the analysis found, one active at a time: ⇠/⇢ choose which the montage will accent; the button with the
 * clock puts a drop where the film stands, for a track the analysis could not read (that hand mark is never replaced).
 */
@Composable
private fun DropControls(vm: CameraController, music: ProjectMusic, clockMs: Long) {
    val drops = music.drops
    val active = music.activeDropMs
    val at = drops.indexOf(active).takeIf { it >= 0 } ?: 0
    if (drops.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Дроп", "Drop"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(enabled = at > 0, onClick = { vm.activateProjectDrop(drops[at - 1]) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
            }
            Text(
                "${at + 1} / ${drops.size} · " + formatDuration(((active ?: drops[at]) / 1000).toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(enabled = at < drops.lastIndex, onClick = { vm.activateProjectDrop(drops[at + 1]) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
    val here = (clockMs + music.offsetMs).coerceAtLeast(0L)
    FilledTonalButton(
        onClick = { vm.putProjectDropAt(here) },
        modifier = Modifier.fillMaxWidth().padding(top = if (drops.isEmpty()) 8.dp else 0.dp),
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr("Дроп", "Drop") + " · " + formatDuration((here / 1000).toInt()))
    }
}
/**
 * The grid by hand (P3, plan §1): a tap tempo against what the analysis found, a beat a minute slower or faster, the
 * pace halved or doubled, and the whole grid shifted a few ms. Two rows, the tempo over the shape, so nothing crowds.
 */
@Composable
private fun GridRow(vm: CameraController, music: ProjectMusic) {
    val bpm = MusicGrid.bpm(music.beats)
    // A tempo that moves cannot be nudged as one number — stretching the whole grid about its first beat would only put
    // the error somewhere else. The shift stays (the grid may sit early or late as a whole), the rest is off.
    val moving = MusicGrid.isVariable(music.beats)
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
        if (moving) {
            Text(
                tr(
                    "Темп меняется по ходу трека — можно только сдвинуть всю сетку",
                    "The tempo changes along the track — only the whole grid can be shifted",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The click a little earlier or later than the beat: a click goes through the phone's mixer, and no phone tells how long
 * that takes — so the ear has the last tenths. «⇠» moves it earlier, «⇢» later, and the number keeps the sign (+ later,
 * − earlier). The hardware latency stays compensated on its own, so the whole ±[AppPrefs.CLICK_NUDGE_LIMIT] really moves
 * the sound. Kept between launches, so it is set once.
 */
@Composable
private fun ClickNudge(enabled: Boolean) {
    val nudge = AppPrefs.clickNudge
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(tr("Клик", "Click"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(
            { AppPrefs.chooseClickNudge(nudge - AppPrefs.CLICK_NUDGE_STEP) },
            enabled = enabled && nudge > -AppPrefs.CLICK_NUDGE_LIMIT,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
        Text((if (nudge > 0) "+$nudge" else "$nudge") + " ms", style = MaterialTheme.typography.bodySmall)
        IconButton(
            { AppPrefs.chooseClickNudge(nudge + AppPrefs.CLICK_NUDGE_STEP) },
            enabled = enabled && nudge < AppPrefs.CLICK_NUDGE_LIMIT,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
    }
    Text(
        tr(
            "«⇠» — раньше, «⇢» — позже. Клик проходит через микшер телефона.",
            "\"⇠\" — earlier, \"⇢\" — later. The click goes through the phone's mixer.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * How the track stands: its wave and its grid are on the timeline now, under the picture — here only whether it is
 * being read, or not readable at all.
 */
@Composable
private fun TrackState(peaks: FloatArray?, failed: Boolean) {
    if (peaks != null && peaks.isNotEmpty()) return
    Text(
        if (failed) tr("Трек не читается", "The track could not be read")
        else tr("Анализируем трек…", "Analysing the track…"),
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Two levels, one under the other: the music's, and the video's own sound under it (0 — music only, 100 — recorded).
 * Both belong to the project, not to the picked file: removing the track and picking another leaves them as they were.
 */
@Composable
private fun MusicSound(vm: CameraController, project: Project) {
    Level(tr("Музыка", "Music"), project.musicVolume, "music") { v -> vm.setProjectMusicVolume(v) }
    Level(tr("Звук роликов", "Clips' sound"), project.videoVolume, "video") { v -> vm.setProjectVideoVolume(v) }
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