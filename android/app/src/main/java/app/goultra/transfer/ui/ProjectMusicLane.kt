package app.goultra.transfer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectMusic
import kotlin.math.roundToInt

/**
 * The project's music along the strip (see `memory-bank/projects-plan.md`, P2): the picked track, its wave with the beat
 * grid over it, the music's level and the video's own sound under it. Picking and the analysis are
 * the controller's (`setProjectMusic`/`analyseMusic`); this shows what it holds and passes the changes on. The sound
 * itself is not here yet: the preview's player and the save's mix come next.
 */
@Composable
internal fun ProjectMusicLane(vm: CameraController, p: Project) {
    // SAF: the app keeps a lasting link to the track (no copy) — the same way files are added to the list.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setProjectMusic(uri)
    }
    val music = p.music
    // A track put in elsewhere (or picked a moment ago) is analysed here: the lane draws the wave once it is done.
    LaunchedEffect(music?.uri) { music?.let { vm.analyseMusic(it) } }
    val peaks = remember(music?.uri, vm.musicVersion) { music?.let { vm.musicPeaks(it.uri) } }
    val failed = music != null && music.uri in vm.musicFailed
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LibraryMusic,
                null,
                Modifier.padding(end = 6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("Музыка", "Music"), style = MaterialTheme.typography.bodyMedium)
            if (music == null) {
                TextButton({ pick.launch(arrayOf("audio/*")) }) { Text(tr("Выбрать трек", "Choose a track")) }
            } else {
                Text(
                    music.name.ifEmpty { tr("Трек", "The track") },
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton({ pick.launch(arrayOf("audio/*")) }) { Text(tr("Заменить трек", "Replace the track")) }
                IconButton(vm::removeProjectMusic) { Icon(Icons.Filled.Close, tr("Убрать трек", "Remove the track")) }
            }
        }
        if (music != null) {
            TrackState(peaks, failed)
            MusicSound(vm, music)
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

/**
 * Two levels, one under the other: the music's, and the video's own sound under it (0 — music only, 100 — as
 * recorded); then the tempo found (a check on the grid).
 */
@Composable
private fun MusicSound(vm: CameraController, music: ProjectMusic) {
    Level(tr("Музыка", "Music"), music.volume, music.uri) { v -> vm.updateProjectMusic { m -> m.copy(volume = v) } }
    Level(tr("Звук роликов", "Clips' sound"), music.videoVolume, music.uri) { v -> vm.updateProjectMusic { m -> m.copy(videoVolume = v) } }
    if (music.beats.size >= 2) {
        val period = (music.beats.last() - music.beats.first()).toDouble() / (music.beats.size - 1)
        if (period > 0.0) {
            Text(
                tr("Бит: %s BPM", "Beat: %s BPM").format((60_000.0 / period).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
            )
        }
    }
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
            // A drag is not a write per pixel.
            onValueChangeFinished = { onSet(level.toDouble()) },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Text(
            "${(level * 100).roundToInt()} %",
            Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
