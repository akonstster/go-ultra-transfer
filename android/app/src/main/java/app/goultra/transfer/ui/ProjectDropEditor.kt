package app.goultra.transfer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.AudioAnalysisMode
import kotlin.math.abs

internal data class DropSelection(val clipId: String, val sourceMs: Long)
internal val MainDropColor = Color(0xFFB74FFF)

internal fun ProjectAudioClip.visibleDrops(): List<Long> =
    source.drops.filter { it >= fromMs && it < sourceEndMs }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectDropEditor(
    clip: ProjectAudioClip,
    selected: Long?,
    cursor: Long?,
    onJump: (Long) -> Unit,
    onMove: (Long) -> Unit,
    onAdd: () -> Unit,
    onPrimary: () -> Unit,
    onDelete: () -> Unit,
    onDeleteAll: () -> Unit,
    analysing: Boolean,
    onAnalyse: (AudioAnalysisMode) -> Unit,
) {
    val drops = remember(clip) { clip.visibleDrops() }
    val anchor = selected ?: cursor
    val previous = drops.lastOrNull { anchor == null || it < anchor }
    val next = drops.firstOrNull { anchor == null || it > anchor }
    val beat = remember(clip, selected) {
        selected?.let { mark ->
            clip.source.beats.asSequence().filter { it >= clip.fromMs && it < clip.sourceEndMs }.minByOrNull { abs(it - mark) }
        }
    }
    val time = anchor?.let(clip::projectAt)
    var menu by remember(clip.id, selected) { mutableStateOf(false) }
    var confirmClear by remember(clip.id) { mutableStateOf(false) }
    var configureAnalysis by remember(clip.id) { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    var laidOut by remember { mutableStateOf(false) }
    LaunchedEffect(laidOut) {
        if (laidOut) bringIntoView.bringIntoView()
    }
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).onSizeChanged { laidOut = it.height > 0 },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ previous?.let(onJump) }, enabled = previous != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Предыдущий дроп", "Previous drop"))
        }
        Text(
            time?.let { formatDuration((it / 1000).toInt()) + "." + (it % 1000).toString().padStart(3, '0') } ?: "—",
            Modifier.weight(1f).padding(horizontal = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton({ next?.let(onJump) }, enabled = next != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Следующий дроп", "Next drop"))
        }
        TextButton(
            { selected?.let { onMove(it - 10L) } }, Modifier.width(48.dp),
            enabled = selected != null && selected > clip.fromMs,
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) { Text("−10 ms", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        TextButton(
            { selected?.let { onMove(it + 10L) } }, Modifier.width(48.dp),
            enabled = selected != null && selected < clip.sourceEndMs - 1L,
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) { Text("+10 ms", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, tr("Меню", "Menu")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Добавить дроп", "Add drop")) },
                    onClick = {
                        menu = false
                        onAdd()
                    },
                    enabled = cursor != null && cursor < clip.source.lengthMs && cursor !in drops,
                )
                DropdownMenuItem(
                    text = { Text(tr("Повторный анализ", "Reanalyse track")) },
                    onClick = {
                        menu = false
                        configureAnalysis = true
                    },
                    enabled = !analysing,
                )
                DropdownMenuItem(
                    text = { Text(tr("К биту", "Snap to beat")) },
                    onClick = {
                        menu = false
                        beat?.let(onMove)
                    },
                    enabled = beat != null && beat != selected && beat !in drops,
                )
                DropdownMenuItem(
                    text = { Text(tr("Главный", "Main drop")) },
                    onClick = {
                        menu = false
                        onPrimary()
                    },
                    enabled = selected != null && clip.source.activeDropMs != selected,
                )
                DropdownMenuItem(
                    text = { Text(tr("Удалить", "Delete")) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    enabled = selected != null,
                )
                DropdownMenuItem(
                    text = { Text(tr("Удалить все дропы", "Delete all drops")) },
                    onClick = {
                        menu = false
                        confirmClear = true
                    },
                    enabled = clip.source.drops.isNotEmpty(),
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Удалить все дропы?", "Delete all drops?")) },
            text = { Text(clip.source.name) },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    onDeleteAll()
                }) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }
    if (configureAnalysis) {
        AudioAnalysisDialog(clip, onDismiss = { configureAnalysis = false }, onApply = {
            configureAnalysis = false
            onAnalyse(it)
        })
    }
}

private fun AudioAnalysisMode.label(): String = when (this) {
    AudioAnalysisMode.AUTO -> tr("Авто", "Auto")
    AudioAnalysisMode.STRUCTURE -> tr("Структура", "Structure")
    AudioAnalysisMode.RHYTHM -> tr("Ритм", "Rhythm")
    AudioAnalysisMode.DYNAMICS -> tr("Динамика", "Dynamics")
    AudioAnalysisMode.SPEECH -> tr("Речь", "Speech")
}

private fun AudioAnalysisMode.description(): String = when (this) {
    AudioAnalysisMode.AUTO -> tr("Выбирает метод по пульсу, сменам звучания и паузам.", "Chooses a method from the pulse, changes in sound, and pauses.")
    AudioAnalysisMode.STRUCTURE -> tr("Ищет смену гармонии и аранжировки, в том числе в тихих или плотных миксах.", "Finds changes in harmony and arrangement, including in quiet or dense mixes.")
    AudioAnalysisMode.RHYTHM -> tr("Привязывает музыкальные акценты к ближайшим битам. Подходит для выраженного ритма.", "Aligns musical accents with nearby beats. Suits music with a clear pulse.")
    AudioAnalysisMode.DYNAMICS -> tr("Ищет подъёмы громкости, которые держатся дольше отдельного удара.", "Finds rises in loudness that last longer than a single hit.")
    AudioAnalysisMode.SPEECH -> tr("Ищет начала фраз после пауз. Подходит для речи и закадрового голоса.", "Finds phrase starts after pauses. Suits speech and voice-over.")
}

@Composable
private fun AudioAnalysisDialog(
    clip: ProjectAudioClip,
    onDismiss: () -> Unit,
    onApply: (AudioAnalysisMode) -> Unit,
) {
    var mode by remember(clip.id) { mutableStateOf(clip.source.analysisMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Повторный анализ", "Reanalyse track")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(clip.source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                clip.source.detectedMode?.let {
                    Text(tr("Последний анализ: %s", "Last analysis: %s").format(it.label()))
                }
                for (option in AudioAnalysisMode.entries) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = mode == option, role = Role.RadioButton, onClick = { mode = option })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == option, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Column {
                            Text(option.label())
                            Text(
                                option.description(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(tr("Новый анализ заменит все дропы этого файла.", "New analysis will replace every drop for this file."))
            }
        },
        confirmButton = {
            TextButton({ onApply(mode) }) { Text(tr("Анализировать", "Analyse")) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(tr("Отмена", "Cancel")) }
        },
    )
}
