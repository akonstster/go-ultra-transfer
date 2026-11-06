package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.Project

/**
 * The «Проекты» tab: the montage projects of this phone. A project is a small list of references to the camera's files
 * (see `memory-bank/projects-plan.md`), so this list is all there is to keep — the media stays where it was shot.
 * The project screen itself (pieces, music, the beat grid, export) arrives in P1.
 */
@Composable
fun ProjectsTab(vm: CameraController) {
    LaunchedEffect(Unit) { vm.reloadProjects() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (vm.projects.isEmpty()) {
            Text(
                tr("Проектов пока нет", "No projects yet"),
                Modifier.align(Alignment.Center).padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.projects, key = { it.id }) { p ->
                    ProjectCard(p, onRename = { renaming = p }, onDelete = { deleting = p })
                }
            }
        }
        FloatingActionButton({ creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, tr("Новый проект", "New project"))
        }
    }

    // Creating and renaming share one dialog: only the title and the button differ.
    val editing = renaming
    if (creating || editing != null) {
        NameDialog(
            title = if (editing == null) tr("Новый проект", "New project") else tr("Переименовать", "Rename"),
            confirm = if (editing == null) tr("Создать", "Create") else tr("Готово", "Done"),
            initial = editing?.name.orEmpty(),
            onDismiss = { creating = false; renaming = null },
            onDone = { name ->
                if (editing == null) vm.createProject(name) else vm.renameProject(editing, name)
                creating = false
                renaming = null
            },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(tr("Удалить проект?", "Delete the project?")) },
            text = {
                Text(
                    tr(
                        "Проект — это только список ссылок: сами файлы остаются на камере и на телефоне, ничего не удаляется.",
                        "A project is only a list of references: the files themselves stay on the camera and on the phone — nothing is deleted.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    { vm.deleteProject(p); deleting = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** One project in the list: its name, what it holds today, and the two things that don't need the project screen. */
@Composable
private fun ProjectCard(p: Project, onRename: () -> Unit, onDelete: () -> Unit) {
    val seconds = (p.clips.sumOf { it.durationMs } / 1000).toInt()
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("%s кусков", "%s clips").format(p.clips.size) + " · " + formatDuration(seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onRename) { Icon(Icons.Filled.Edit, tr("Переименовать", "Rename")) }
            IconButton(onDelete) { Icon(Icons.Filled.Delete, tr("Удалить проект?", "Delete the project?")) }
        }
    }
}

/** The name of a project, for creating and for renaming; an empty name keeps the buttons off. */
@Composable
internal fun NameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(tr("Название проекта", "Project name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) onDone(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}