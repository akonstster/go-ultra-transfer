package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.goultra.transfer.camera.ProjectExport
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.Project

/** The files' pieces as they stand ([all]), and the same for the files edited on the phone ([edited]). */
private data class FilePieces(
    val all: Map<String, List<Highlight>>,
    val edited: Map<String, List<Highlight>>,
)

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

    var carrying by remember(p.id) { mutableStateOf(false) }
    var saving by remember(p.id) { mutableStateOf(false) }

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
            if (p.clips.isNotEmpty()) {
                // The progress itself, not the jobs: a job is still active while its last state is set, and the
                // button read that way stayed off for good after a failed save.
                IconButton({ saving = true }, enabled = vm.download == null && vm.export == null) {
                    Icon(Icons.Filled.Save, tr("Сохранить видео", "Save the video"))
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
                carrying = carrying,
                music = p.music,
            )
        }
        // The project's music: the picked track, its wave and the grid over it, its volume and ducking (P2 — the sound
        // of it in the preview and at save time follows).
        ProjectMusicLane(vm, p)
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
            ProjectStrip(vm, p, stale, playingNow, onPlay = { play = it }, onCarrying = { carrying = it })
        }
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
