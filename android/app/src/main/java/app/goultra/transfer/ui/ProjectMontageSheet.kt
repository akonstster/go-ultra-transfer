package app.goultra.transfer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.BeatGrid
import app.goultra.transfer.camera.Dynamics
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.Scenario

/** The auto montage's own sheet: what kind of film it is, how it is paced, and one button that cuts the material. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MontageSheet(vm: CameraController, project: Project, onDismiss: () -> Unit) {
    val blocked = project.baseFile != null || project.covers.isNotEmpty()
    val empty = project.clips.isEmpty()
    val running = vm.montageRunning
    val music = project.music
    val gridReady = !music?.beats.isNullOrEmpty()
    val unsure = gridReady && music?.gridConfidence != null &&
        music.gridConfidence < 0.20 && music.gridVersion >= BeatGrid.ANALYSIS_VERSION
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentCut, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Монтаж", "Montage"), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Сценарий", "Scenario"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Scenario.entries.forEach { scenario ->
                    FilterChip(
                        selected = project.scenario == scenario,
                        onClick = { vm.setProjectStyle(scenario, project.dynamics) },
                        label = { Text(scenarioLabel(scenario)) },
                    )
                }
            }
            Text(
                tr("Динамика", "Dynamics"),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dynamics.entries.forEach { dynamics ->
                    FilterChip(
                        selected = project.dynamics == dynamics,
                        onClick = { vm.setProjectStyle(project.scenario, dynamics) },
                        label = { Text(dynamicsLabel(dynamics)) },
                    )
                }
            }
            Text(
                when {
                    blocked -> tr(
                        "Для проекта с основой АВТО будет отдельным шагом",
                        "For a project with a base file the auto montage is a separate step",
                    )
                    empty -> tr("Сначала добавьте файлы в проект", "Add files to the project first")
                    !gridReady && music != null -> tr(
                        "Сетка не найдена — нарежем ровными слотами",
                        "No grid was found — the pieces will be even",
                    )
                    music == null -> tr(
                        "Без музыки проект сохранит длину, а куски будут ровными",
                        "Without music the project keeps its length and the pieces are even",
                    )
                    unsure -> tr(
                        "Бит найден неуверенно — проверьте сетку в «Темпе»",
                        "The beat is unsure — check the grid in Tempo",
                    )
                    else -> tr(
                        "Длинные файлы будут нарезаны по музыке",
                        "The long files will be cut into musical pieces",
                    )
                },
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked || empty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (project.dynamics) {
                    Dynamics.EVEN -> tr("Ровные куски по 4 доли", "Even pieces of 4 beats")
                    Dynamics.RHYTHMIC -> tr("Чаще всего по 2 доли", "Mostly 2 beats")
                    Dynamics.DYNAMIC -> tr("Длиннее в начале, короче к дропу", "Longer at the start, shorter at the drop")
                    Dynamics.MOOD -> tr("Длинные планы, 4–8 долей", "Long shots, 4–8 beats")
                    Dynamics.SHUFFLE -> tr("Случайный порядок и длина", "Random order and length")
                },
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = vm::autoMontage,
                enabled = !running && !blocked && !empty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Читаем кадры…", "Reading frames…"))
                } else {
                    Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Собрать монтаж", "Build the montage"))
                }
            }
            if (project.previous != null && !running) {
                TextButton(vm::undoAutoMontage, Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(tr("Отменить авто", "Undo auto"))
                }
            }
            vm.montageNote?.let { note ->
                Text(
                    note.text,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The six scenarios as the phone's interface names them. */
@Composable
private fun scenarioLabel(scenario: Scenario): String = when (scenario) {
    Scenario.TRAVEL -> tr("Путешествие", "Travel")
    Scenario.MUSIC -> tr("Музыкальный клип", "Music video")
    Scenario.ACTION -> tr("Экшн", "Action")
    Scenario.VLOG -> tr("Влог", "Vlog")
    Scenario.SLIDESHOW -> tr("Слайдшоу", "Slideshow")
    Scenario.TIMELAPSE -> tr("Таймлапс", "Timelapse")
    Scenario.CUSTOM -> tr("Свой", "Custom")
}

/** The five dynamics ([Dynamics.SHUFFLE] is the plan's «шаффл»). */
@Composable
private fun dynamicsLabel(dynamics: Dynamics): String = when (dynamics) {
    Dynamics.EVEN -> tr("Ровный", "Even")
    Dynamics.RHYTHMIC -> tr("Ритмичный", "Rhythmic")
    Dynamics.DYNAMIC -> tr("Динамичный", "Dynamic")
    Dynamics.MOOD -> tr("Настроение", "Mood")
    Dynamics.SHUFFLE -> tr("Шаффл", "Shuffle")
}