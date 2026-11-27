package app.goultra.transfer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.goultra.transfer.camera.Project

@Composable
internal fun ProjectAudioPicker(vm: CameraController, project: Project, onDismiss: () -> Unit) {
    val files = vm.projectAudioFiles
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var audition by remember { mutableStateOf<String?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    val keywords = remember(query) { query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val results = remember(files, keywords) { files.filter { file -> keywords.all { file.name.contains(it, ignoreCase = true) } } }
    val context = LocalContext.current
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                unreadable = p.playerError != null
                audition = p.currentMediaItem?.mediaId?.takeIf {
                    p.playWhenReady && p.playbackState != Player.STATE_ENDED && !unreadable
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(vm.inBackground) { if (vm.inBackground) player.pause() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            query = ""
            vm.addFiles(uris)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Выбрать трек", "Choose a track")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Поиск по названию", "Search by name")) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton({
                    player.pause()
                    picker.launch(arrayOf("audio/*"))
                }) { Text(tr("Добавить файлы", "Add files")) }
                if (unreadable) Text(
                    tr("Трек не читается", "The track could not be read"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (results.isEmpty() && keywords.isNotEmpty()) {
                    Text(tr("Ничего не найдено", "No results"), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(results, key = { it.uri }) { file ->
                        val playing = audition == file.uri
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(file.uri in selected, { checked ->
                                selected = if (checked) selected + file.uri else selected - file.uri
                            })
                            Row(
                                Modifier.weight(1f).clickable(role = Role.Button, enabled = file.localUri != null) {
                                    val uri = file.localUri ?: return@clickable
                                    if (playing) {
                                        player.pause()
                                    } else {
                                        if (player.currentMediaItem?.mediaId != file.uri) {
                                            player.setMediaItem(MediaItem.Builder().setMediaId(file.uri).setUri(uri).build())
                                        } else if (player.playbackState == Player.STATE_ENDED) {
                                            player.seekTo(0)
                                        }
                                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                        player.play()
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (playing) tr("Пауза", "Pause") else tr("Играть", "Play"),
                                    Modifier.padding(end = 8.dp),
                                )
                                Column {
                                    Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    file.durationSec?.let {
                                        Text(formatDuration(it), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val byUri = files.associateBy { it.uri }
                vm.addToProject(project, selected.mapNotNull(byUri::get))
                onDismiss()
            }, enabled = selected.isNotEmpty()) { Text(tr("В проект", "Add to project")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
