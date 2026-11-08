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

/** Which sheet stands open under the project (none, the sound's, the tempo's). */
private enum class Sheet { NONE, SOUND, TEMPO }

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
            TrackState(peaks, failed)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool(tr("Звук", "Sound"), Icons.Filled.VolumeUp) { sheet = Sheet.SOUND }
            Tool(tr("Темп", "Tempo"), Icons.Filled.Speed) { sheet = Sheet.TEMPO }
        }
    }
    when (sheet) {
        Sheet.SOUND -> SoundSheet(vm, music, onPick = { pick.launch(arrayOf("audio/*")) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.TEMPO -> music?.let { TempoSheet(vm, it, clockMs, onDismiss = { sheet = Sheet.NONE }) }
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
private fun SoundSheet(vm: CameraController, music: ProjectMusic?, onPick: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
            if (music != null) MusicSound(vm, music)
        }
    }
}

/** All the rhythm there is: the grid by hand, the tap, the metronome that sounds it, and the drop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TempoSheet(vm: CameraController, music: ProjectMusic, clockMs: Long, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(tr("Темп", "Tempo"), style = MaterialTheme.typography.titleMedium)
            GridRow(vm, music, clockMs)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(tr("Метроном", "Metronome"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(AppPrefs.metronome, { AppPrefs.chooseMetronome(it) })
            }
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
 * The grid by hand (P3, plan §1): a beat a minute slower or faster, the pace halved or doubled, the whole grid shifted a
 * few ms, a tap tempo against what the analysis found, and the drop put where the clock's line stands.
 */
@Composable
private fun GridRow(vm: CameraController, music: ProjectMusic, clockMs: Long) {
    val bpm = MusicGrid.bpm(music.beats)
    // Taps: a few quick ones give the beat; two seconds of silence start them over.
    var taps by remember(music.uri) { mutableStateOf(emptyList<Long>()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton({
            val now = System.currentTimeMillis()
            val next = if (now - (taps.lastOrNull() ?: 0L) > 2_000L) listOf(now) else taps + now
            taps = next
            vm.editMusicGrid { b, len -> MusicGrid.fromTap(b.firstOrNull() ?: 0L, next, len) }
        }) { Text(tr("Тап", "Tap")) }
        IconButton(
            { vm.editMusicGrid { b, len -> MusicGrid.stretched(b, bpm / (bpm - 1.0).coerceAtLeast(1.0), len) } },
            enabled = bpm > 2.0,
        ) { Icon(Icons.Filled.Remove, null) }
        Text(if (bpm > 0.0) "${bpm.roundToInt()} BPM" else "—", style = MaterialTheme.typography.bodySmall)
        IconButton(
            { vm.editMusicGrid { b, len -> MusicGrid.stretched(b, bpm / (bpm + 1.0), len) } },
            enabled = bpm > 0.0,
        ) { Icon(Icons.Filled.Add, null) }
        TextButton({ vm.editMusicGrid { b, len -> MusicGrid.stretched(b, 0.5, len) } }) { Text("×2") }
        TextButton({ vm.editMusicGrid { b, len -> MusicGrid.stretched(b, 2.0, len) } }) { Text("÷2") }
        IconButton({ vm.editMusicGrid { b, _ -> MusicGrid.shifted(b, -5L) } }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
        }
        IconButton({ vm.editMusicGrid { b, _ -> MusicGrid.shifted(b, 5L) } }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        TextButton({ vm.updateProjectMusic { m -> m.copy(dropMs = clockMs + m.offsetMs) } }) {
            Text(tr("Дроп", "Drop") + (music.dropMs?.let { " " + formatDuration((it / 1000).toInt()) } ?: ""))
        }
    }
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

/** Two levels, one under the other: the music's, and the video's own sound under it (0 — music only, 100 — recorded). */
@Composable
private fun MusicSound(vm: CameraController, music: ProjectMusic) {
    Level(tr("Музыка", "Music"), music.volume, music.uri) { v -> vm.updateProjectMusic { m -> m.copy(volume = v) } }
    Level(tr("Звук роликов", "Clips' sound"), music.videoVolume, music.uri) { v -> vm.updateProjectMusic { m -> m.copy(videoVolume = v) } }
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