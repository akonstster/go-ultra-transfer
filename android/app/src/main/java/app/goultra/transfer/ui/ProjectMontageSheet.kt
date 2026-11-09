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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val analysing = music != null && music.uri in vm.musicAnalysing
    val musicFailed = music != null && music.uri in vm.musicFailed
    // «Peaks are in» is not «the analysis is done»: the length arrives with the grid, after the wave file.
    val musicPending = music != null && (analysing || music.lengthMs <= 0L)
    val musicTooShort = music != null && music.lengthMs > 0L && music.lengthMs <= music.offsetMs
    val targetMs = music?.let { (it.lengthMs - it.offsetMs).coerceAtLeast(0L) } ?: 0L
    var confirmBuild by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
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
                scenarioHint(project.scenario),
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    musicPending -> if (musicFailed) {
                        tr("Не удалось определить длительность трека", "The track's length could not be read")
                    } else {
                        tr("Анализируем трек — монтаж начнётся после", "Analysing the track — the montage waits")
                    }
                    musicTooShort -> tr(
                        "В выбранной части трека нет длительности",
                        "The chosen part of the track has no length",
                    )
                    !gridReady && music != null -> tr(
                        "Сетка не найдена — нарежем ровными слотами",
                        "No grid was found — the pieces will be even",
                    )
                    music == null -> tr(
                        "Без музыки нарезка переставит и укоротит куски, но не изменит их содержимое",
                        "Without music the montage regroups and trims the pieces but does not change what they show",
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
                color = if (blocked || empty || musicPending || musicTooShort) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (music != null && targetMs > 0L && !musicPending && !musicTooShort) {
                Text(
                    tr(
                        "Длительность ролика: по музыке — %s",
                        "Film length: the music — %s",
                    ).format(formatDuration((targetMs / 1000L).toInt())),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                dynamicsHint(project.dynamics),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { confirmBuild = true },
                enabled = !running && !blocked && !empty && !musicPending && !musicTooShort,
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
                TextButton({ confirmUndo = true }, Modifier.fillMaxWidth().padding(top = 2.dp)) {
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
    if (confirmBuild) {
        AlertDialog(
            onDismissRequest = { confirmBuild = false },
            title = {
                Text(
                    if (project.previous != null) {
                        tr("Пересобрать монтаж?", "Rebuild the montage?")
                    } else {
                        tr("Собрать монтаж?", "Build the montage?")
                    },
                )
            },
            text = {
                Text(
                    if (project.previous != null) {
                        tr(
                            "Текущий автомонтаж будет заменён, а исходные куски останутся в «Отменить авто».",
                            "The current auto montage will be replaced; the original pieces stay under \"Undo auto\".",
                        )
                    } else {
                        tr(
                            "Текущая последовательность будет заменена. Исходные куски можно вернуть через «Отменить авто».",
                            "The current sequence will be replaced. The original pieces can be restored with \"Undo auto\".",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton({
                    confirmBuild = false
                    vm.autoMontage()
                }) { Text(tr("Собрать", "Build")) }
            },
            dismissButton = {
                TextButton({ confirmBuild = false }) { Text(tr("Не сейчас", "Not now")) }
            },
        )
    }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text(tr("Отменить автомонтаж?", "Undo the auto montage?")) },
            text = {
                Text(
                    tr(
                        "Вернуть куски, которые были до автомонтажа?",
                        "Restore the pieces that were there before the auto montage?",
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    confirmUndo = false
                    vm.undoAutoMontage()
                }) { Text(tr("Вернуть", "Restore")) }
            },
            dismissButton = {
                TextButton({ confirmUndo = false }) { Text(tr("Оставить", "Keep it")) }
            },
        )
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

/** What each scenario does to the cutting, in the words of this build rather than a promise about the footage. */
@Composable
private fun scenarioHint(scenario: Scenario): String = when (scenario) {
    Scenario.TRAVEL -> tr(
        "Средние и длинные планы: спокойная смена мест без частых склеек",
        "Medium and long shots: calm changes of place without rapid cuts",
    )
    Scenario.MUSIC -> tr(
        "Ритм трека и его громкие места ведут нарезку и выбор кусков",
        "The track's rhythm and loud passages lead the cutting and the choice of pieces",
    )
    Scenario.ACTION -> tr(
        "Короткие серии в энергичных местах и передышки между ними",
        "Short bursts where the track is energetic, with breathing room between them",
    )
    Scenario.VLOG -> tr(
        "Длинные фрагменты и редкие склейки: видно, что происходит",
        "Longer fragments and rarer cuts: you can see what is happening",
    )
    Scenario.SLIDESHOW -> tr(
        "Фотографии и кадры держатся дольше, чтобы их успели рассмотреть",
        "Photos and shots stay longer so they can be looked at",
    )
    Scenario.TIMELAPSE -> tr(
        "Подвижная нарезка с короткими планами, но без сплошного мелькания",
        "A lively cut with short shots, but not a solid flicker",
    )
    Scenario.CUSTOM -> tr(
        "Сбалансированный вариант без сильного уклона в короткие или длинные планы",
        "A balanced cut without a strong bias towards short or long shots",
    )
}

/** The per-value explanation under the dynamics chips. */
@Composable
private fun dynamicsHint(dynamics: Dynamics): String = when (dynamics) {
    Dynamics.EVEN -> tr(
        "Спокойный шаг из 8–12 долей с небольшим разнообразием",
        "A steady 8–12-beat step with a little variety",
    )
    Dynamics.RHYTHMIC -> tr(
        "Чаще 4–8 долей; две доли только как редкий акцент",
        "Mostly 4–8 beats; two beats only as an occasional accent",
    )
    Dynamics.DYNAMIC -> tr(
        "Длиннее в спокойных местах, короче перед дропом и длинная передышка после",
        "Longer in quiet places, shorter before a drop, then a long breath after it",
    )
    Dynamics.MOOD -> tr(
        "Длинные планы по 8–16 долей: важнее настроение, чем частый ритм",
        "Long 8–16-beat shots: mood matters more than a quick rhythm",
    )
    Dynamics.SHUFFLE -> tr(
        "Меняет порядок и длину от запуска к запуску, сохраняя передышки",
        "Changes order and length from run to run while keeping breathing room",
    )
}