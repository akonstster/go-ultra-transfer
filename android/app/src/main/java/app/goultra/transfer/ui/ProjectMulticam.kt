package app.goultra.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.Multicam
import app.goultra.transfer.camera.MulticamCutaway
import app.goultra.transfer.camera.MulticamTake
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.TrackAudio
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

private fun cameraLabel(camera: Int) = tr("Камера %s", "Angle %s").format(camera)

internal data class MulticamHighlight(val start: Long, val end: Long, val cut: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MulticamSetup(vm: CameraController, project: Project, onDismiss: () -> Unit) {
    val saved = remember(project.id) { vm.multicamPickerSelection(project) }
    val request = vm.multicamPickerRequest?.takeIf { it.projectId == project.id }
    val selected = remember(project.id, request) {
        mutableStateMapOf<String, Int>().apply {
            putAll(saved.cameras)
            var next = (values.maxOrNull() ?: 0) + 1
            for (name in request?.videos.orEmpty()) if (name !in this) put(name, next++)
        }
    }
    val videos = vm.multicamVideoFiles
    val audioFiles = vm.projectAudioFiles
    val audio = remember(audioFiles) { audioFiles.map { it.name } }
    val available = remember(videos) { videos.associateBy { it.name } }
    val configuredCutaways = remember(project.multicam) {
        project.multicam?.takes.orEmpty().filter { it.cutaway }.map { it.file }.toSet()
    }
    var query by remember(project.id) { mutableStateOf("") }
    var reference by remember(project.id, request) {
        mutableStateOf(request?.audio?.takeIf { it in audio } ?: saved.reference ?: selected.keys.sorted().firstOrNull())
    }
    var choosingReference by remember { mutableStateOf(false) }
    var choosingAngle by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val angleOptions = (selected.values.toSet() + ((selected.values.maxOrNull() ?: 0) + 1)).sorted()
    val referenceOptions = selected.keys.sorted() + audio.filter { it !in selected }
    val master = reference?.takeIf { it in referenceOptions }
    val chosen = selected.keys.toSet()
    val missing = remember(chosen, available) { chosen.filter { it !in available }.sorted() }
    val results = remember(videos, missing, query) {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        (missing + videos.map { it.name }).filter { name -> terms.all { name.contains(it, ignoreCase = true) } }
    }
    val listState = rememberLazyListState()
    fun close() {
        vm.closeMulticamPicker(project, selected.toMap(), reference)
        onDismiss()
    }
    fun toggle(name: String, checked: Boolean) {
        if (checked) {
            selected[name] = (selected.values.maxOrNull() ?: 0) + 1
            if (reference == null) reference = name
        } else {
            selected.remove(name)
            if (reference == name) reference = selected.keys.sorted().firstOrNull()
        }
    }
    fun synchronise(reset: Boolean) {
        val currentMaster = master ?: return
        vm.buildMulticam(project, selected.toMap(), currentMaster, reset = reset)
        close()
    }
    val ready = master != null && selected.values.toSet().size >= 2 && missing.isEmpty() && vm.multicamProgress == null
    ModalBottomSheet(onDismissRequest = ::close) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            Text(tr("Мультикам", "Multicam"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Выбери записи и назначь один номер файлам с одной камеры.",
                "Select recordings and give files from the same camera the same angle."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; choosingAngle = null },
                label = { Text(tr("Поиск по названию", "Search by name")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Text(tr("%s шт.", "%s items").format(chosen.size), style = MaterialTheme.typography.labelMedium)
            if (results.isEmpty()) Text(tr("Ничего не найдено", "No results"), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp), state = listState) {
                items(results, key = { it }) { name ->
                    val file = available[name]
                    val angle = selected[name]
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(angle != null, onCheckedChange = { toggle(name, it) })
                        Row(Modifier.weight(1f).clickable { toggle(name, angle == null) },
                            verticalAlignment = Alignment.CenterVertically) {
                            MulticamPickerThumbnail(vm, file, listState)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                val details = if (file == null) tr("Часть записей недоступна", "Some recordings are unavailable")
                                else buildList {
                                    file.created?.let { add(it.toLocalDate().toString() + " " + it.toLocalTime().toString().take(5)) }
                                    file.durationSec?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
                                    if (name in configuredCutaways) add(tr("Перебивка", "Cutaway"))
                                }.joinToString(" · ")
                                if (details.isNotEmpty()) Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (angle != null) Box {
                            TextButton({ choosingAngle = name }) { Text(cameraLabel(angle)) }
                            if (choosingAngle == name) DropdownMenu(expanded = true, onDismissRequest = { choosingAngle = null }) {
                                angleOptions.forEach { number ->
                                    DropdownMenuItem(text = { Text(cameraLabel(number)) }, onClick = {
                                        selected[name] = number
                                        choosingAngle = null
                                    })
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Box {
                TextButton({ choosingReference = true }, enabled = referenceOptions.isNotEmpty()) {
                    Text(tr("Основной звук", "Reference sound") + " · " + master.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (choosingReference) DropdownMenu(expanded = true, onDismissRequest = { choosingReference = false }) {
                    referenceOptions.forEach { name ->
                        DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                            reference = name
                            choosingReference = false
                        })
                    }
                }
            }
            FilledTonalButton({ synchronise(reset = false) }, enabled = ready) {
                Text(tr("Синхронизировать", "Synchronise"))
            }
            if (project.multicam != null) TextButton({ confirmReset = true }, enabled = ready) {
                Text(tr("Синхронизировать со сбросом", "Reset and synchronise"))
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Сбросить синхронизацию?", "Reset multicam sync?")) },
            text = { Text(tr(
                "Ракурсы пересчитаются заново. Раскладка мультикама, его склейки и отметки перебивок будут сброшены. Действие можно отменить в истории.",
                "Angles will be analysed again. Multicam placements, cuts and cutaway choices will reset. You can undo this in History.",
            )) },
            confirmButton = {
                TextButton({ confirmReset = false; synchronise(reset = true) }, enabled = ready) {
                    Text(tr("Синхронизировать со сбросом", "Reset and synchronise"))
                }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

@Composable
private fun MulticamPickerThumbnail(vm: CameraController, file: CameraFile?, listState: LazyListState) {
    val thumb by produceState<ImageBitmap?>(null, file, vm.conn, listState) {
        if (file == null) return@produceState
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling && value == null) {
                delay(100)
                value = vm.thumbnail(file)
            }
        }
    }
    Box(Modifier.size(54.dp, 36.dp).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val frame = thumb
        if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MulticamProgress(progress: CameraController.MulticamProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(tr("Синхронизация ракурсов · %s/%s", "Synchronising angles · %s/%s")
            .format(progress.done, progress.total), style = MaterialTheme.typography.bodySmall)
        if (progress.done == progress.total) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MulticamControls(
    vm: CameraController,
    group: Multicam,
    master: ProjectAudioClip,
    clock: Long,
    playing: Boolean,
    expanded: Boolean,
    audition: String?,
    selectedShotAt: Long?,
    onShotSelected: (Long) -> Unit,
    onHighlight: (MulticamHighlight?) -> Unit,
    onResult: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
) {
    val shots = remember(group, master.fromMs, master.sourceEndMs) {
        group.shots(master.fromMs, master.sourceEndMs)
    }
    val at = master.sourceAt(clock)
    val selectedTime = selectedShotAt?.takeIf(master::contains)?.let(master::sourceAt)
    val selectedShot = shots.firstOrNull { selectedTime != null && selectedTime in it.start until it.end }
    val cursorShot = shots.firstOrNull { at in it.start until it.end }
    val shot = selectedShot ?: cursorShot
    val alternate = cursorShot?.let { group.anglesFor(it.start, it.end).filterNot { angle -> angle == it.camera } }.orEmpty()
    val cut = cursorShot?.takeIf { at - it.start >= 100 && it.end - at >= 100 }?.let { current ->
        group.anglesFor(at, current.end).firstOrNull { it != current.camera }
    }
    val pairs = remember(shots) { shots.zipWithNext().filter { (left, right) ->
        left.camera != null && right.camera != null && left.camera != right.camera
    } }
    var boundary by remember { mutableStateOf<Long?>(null) }
    var movingCut by remember { mutableStateOf(false) }
    var angles by remember { mutableStateOf(false) }
    val pair = pairs.firstOrNull { it.second.start == boundary }
    fun edgeOf(current: Multicam.Shot): Long? =
        pairs.firstOrNull { it.second == current }?.second?.start
            ?: pairs.firstOrNull { it.first == current }?.second?.start
    LaunchedEffect(selectedShotAt) {
        if (movingCut) boundary = selectedShot?.let(::edgeOf)
    }
    val highlight = if (expanded) shot?.let {
        MulticamHighlight(master.projectAt(it.start), master.projectAt(it.end), pair?.second?.start?.let(master::projectAt))
    } else null
    LaunchedEffect(highlight) { onHighlight(highlight) }
    fun move(to: Long) {
        val (left, right) = pair ?: return
        if (group.roll(left, right, to) == null) return
        vm.editMulticam { it.roll(left, right, to) }
        boundary = to
        val selected = if (shot == left) left.start + (to - left.start) / 2 else to + (right.end - to) / 2
        onShotSelected(master.projectAt(selected))
        onSeek(master.projectAt(to))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton({
            onExpand()
            boundary = null
            movingCut = false
            if (!expanded && !master.contains(clock)) onSeek(master.startMs)
        }) {
            Text(if (expanded) tr("Готово", "Done") else tr("Править ракурсы", "Edit angles"))
        }
        if (expanded) {
            if (audition != null) TextButton(onResult) { Text(tr("Монтаж", "Montage")) }
            TextButton(onClick = {
                val current = cursorShot ?: return@TextButton
                val angle = cut ?: return@TextButton
                vm.editMulticam { it.replaceShot(at, current.end, angle) }
                onResult()
            }, enabled = !playing && cut != null) { Text(tr("Склейка", "Cut")) }
            TextButton(onClick = {
                movingCut = !movingCut
                boundary = if (movingCut) shot?.let(::edgeOf) else null
                boundary?.let {
                    onSeek(master.projectAt(it))
                    onResult()
                }
            }, enabled = movingCut || !playing && pairs.isNotEmpty() && master.contains(clock)) {
                Text(tr("Сдвиг склейки", "Move cut"))
            }
            Box {
                TextButton({ angles = true }, enabled = !playing && alternate.isNotEmpty()) {
                    Text(tr("Ракурс", "Angle"))
                }
                DropdownMenu(expanded = angles, onDismissRequest = { angles = false }) {
                    alternate.forEach { camera ->
                        DropdownMenuItem(text = { Text(cameraLabel(camera)) }, onClick = {
                            cursorShot?.let { current -> vm.editMulticam { it.replaceShot(current.start, current.end, camera) } }
                            onResult()
                            angles = false
                        })
                    }
                }
            }
        }
    }
    if (expanded && pair != null && !playing) {
        val fps = vm.projectFile(pair.second.file)?.fps?.takeIf { it > 0 } ?: 30
        val step = (1000.0 / fps).roundToLong()
        val current = pair.second.start
        val projectTime = master.projectAt(current)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration((projectTime / 1000L).toInt()) + "." +
                (projectTime % 1000L).toString().padStart(3, '0'), style = MaterialTheme.typography.labelMedium)
            TextButton({ move(current - step) }, enabled = group.roll(pair.first, pair.second, current - step) != null) { Text("−1") }
            TextButton({ move(current + step) }, enabled = group.roll(pair.first, pair.second, current + step) != null) { Text("+1") }
            TextButton({ move(at) }, enabled = at != current && group.roll(pair.first, pair.second, at) != null) {
                Text(tr("К курсору", "To playhead"))
            }
        }
    }
}

private fun syncSeconds(at: Long): String =
    String.format(Locale.ROOT, "%.3f", at / 1000.0).trimEnd('0').trimEnd('.')

private enum class ReviewAction { Sections, Whole, AtPlayhead, Cutaway }

@Composable
private fun multicamDurationSummary(placedMs: Long, fullMs: Long): String =
    tr("В монтаж попадёт %s из %s", "Will use %s of %s")
        .format(formatDuration((placedMs / 1000).toInt()), formatDuration((fullMs / 1000).toInt()))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MulticamReviewActions(vm: CameraController, group: Multicam, master: ProjectAudioClip,
                                  take: MulticamTake, clock: () -> Long, onClose: () -> Unit) {
    var manualStart by remember(take.id) { mutableStateOf(0L) }
    var confirm by remember(take.id) { mutableStateOf<ReviewAction?>(null) }
    var affected by remember(take.id) { mutableStateOf<List<Multicam.Shot>>(emptyList()) }
    val confirmed = remember(take) { take.confirmedSpans() }
    val conflicts = remember(take) {
        take.proposedSpans.any { span -> take.proposedSpans.any { it != span && it.overlaps(span) } }
    }
    val coveredMs = remember(confirmed) { confirmed.sumOf { it.toMs - it.fromMs } }
    fun save(action: ReviewAction) {
        when (action) {
            ReviewAction.Sections -> vm.resolveMulticamAlignment(take.id, keepWhole = false, spans = confirmed)
            ReviewAction.Whole -> vm.resolveMulticamAlignment(take.id, keepWhole = true)
            ReviewAction.AtPlayhead -> vm.placeMulticamTake(take.id, manualStart)
            ReviewAction.Cutaway -> vm.useMulticamCutaway(take.id)
        }
        onClose()
    }
    fun prepare(action: ReviewAction) {
        val next = when (action) {
            ReviewAction.Sections -> group.acceptPartialAlignment(take.id, confirmed)
            ReviewAction.Whole -> group.keepWholeTake(take.id)
            ReviewAction.AtPlayhead -> group.alignWholeTake(take.id, manualStart)
            ReviewAction.Cutaway -> group.keepCutaway(take.id)
        } ?: return
        val lost = group.lostCoverage(next, master.fromMs, master.sourceEndMs)
        if (lost.isEmpty()) save(action) else {
            affected = lost
            confirm = action
        }
    }
    take.driftMs?.let { drift -> Text(tr("Замечен дрейф звука (%s мс). Проверьте синхрон в начале и в конце.",
        "Audio drift detected (%s ms). Check sync at the start and end.").format(drift)) }
    if (conflicts && confirmed.isEmpty()) Text(tr("Проверьте позицию",
        "Check placement"),
        Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
    else if (confirmed.isNotEmpty()) Text(multicamDurationSummary(coveredMs, take.lengthMs),
        Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (confirmed.isNotEmpty()) TextButton({
            prepare(ReviewAction.Sections)
        }) {
            Text(tr("Добавить части", "Add sections") + " (${confirmed.size})")
        }
        if (take.startMs != null) TextButton({ prepare(ReviewAction.Whole) }) {
            Text(tr("Оставить целым файлом", "Keep the full file"))
        } else TextButton({
            manualStart = master.sourceAt(clock()).coerceIn(master.fromMs, master.sourceEndMs - 1)
            prepare(ReviewAction.AtPlayhead)
        }) {
            Text(tr("Поставить у курсора", "Place at playhead"))
        }
        if (take.lengthMs >= 500L && take.file != group.referenceFile) TextButton({ prepare(ReviewAction.Cutaway) }) {
            Text(tr("Использовать как перебивку", "Use as cutaway"))
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("Планы без прежнего ракурса (%s)", "Shots losing their angle (%s)").format(affected.size)) },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(affected.joinToString(" · ") {
                        "${syncSeconds(master.projectAt(it.start))}–${syncSeconds(master.projectAt(it.end))} s"
                    }, maxLines = 1)
                }
            },
            confirmButton = { TextButton({ save(action) }) { Text(tr("Применить", "Apply")) } },
            dismissButton = { TextButton({ confirm = null }) { Text(tr("Не сейчас", "Not now")) } },
        )
    }
}

@Composable
internal fun MulticamRows(
    vm: CameraController,
    group: Multicam,
    master: ProjectAudioClip,
    audition: String?,
    geometry: () -> TimelineGeometry,
    scroll: () -> Float,
    clock: () -> Long,
    onInspect: (MulticamTake, Long?) -> Unit,
    onResult: () -> Unit,
    onSeekResult: (Long) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    val reviewColor = MaterialTheme.colorScheme.onTertiaryContainer
    val shown by rememberUpdatedState(audition)
    val inspect by rememberUpdatedState(onInspect)
    val layout by rememberUpdatedState(geometry)
    val scrollNow by rememberUpdatedState(scroll)
    val result by rememberUpdatedState(onResult)
    val seekResult by rememberUpdatedState(onSeekResult)
    val draggingChange by rememberUpdatedState(onDraggingChange)
    var reviewId by remember(group.referenceFile) { mutableStateOf<String?>(null) }
    val selectedReview = group.takes.firstOrNull { it.id == reviewId && !it.cutaway && (it.needsReview || it.startMs == null) }
    val origin = master.startMs - master.fromMs
    val files = remember(group.takes) { group.takes.map { it.file }.distinct() }
    val peaks = remember(files, vm.musicVersion) { files.associateWith(vm::multicamPeaks) }

    Column(modifier) {
        group.cameras.forEachIndexed { index, camera ->
            val color = colors[index % colors.size]
            val takes = group.takes.filter { it.camera == camera }
            val cutaways = takes.filter { it.cutaway }
            val inserts = group.cutaways.filter { cut -> cutaways.any { it.file == cut.file } }
            val placed = takes.filter { !it.cutaway && !it.needsReview && it.startMs != null }
            val pendingReviews = takes.filter { !it.cutaway && (it.needsReview || it.startMs == null) }
            val review = selectedReview?.takeIf { it.camera == camera }
            val previewParts = remember(review) { review?.confirmedSpans().orEmpty() }
            val audible = placed.firstOrNull { it.id == audition }
            val label = review?.file ?: audible?.file
            var pendingDrag by remember(camera, placed) { mutableStateOf<Pair<String, Long>?>(null) }
            var draggingCutaway by remember(camera, inserts) { mutableStateOf<Pair<MulticamCutaway, Long>?>(null) }
            var selectedCutaway by remember(camera) { mutableStateOf<String?>(null) }
            fun toggle(take: MulticamTake) {
                if (reviewId == take.id) reviewId = null else {
                    reviewId = take.id
                    result()
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(cameraLabel(camera), style = MaterialTheme.typography.labelMedium)
                if (audible != null) Icon(Icons.AutoMirrored.Filled.VolumeUp, tr("Звук", "Sound"),
                    Modifier.padding(start = 4.dp).size(14.dp), tint = color)
                Text(label.orEmpty(), Modifier.weight(1f).padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pendingReviews.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        pendingReviews.forEach { take ->
                            IconButton({ toggle(take) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Warning, "${tr("Проверьте позицию", "Check placement")} · ${take.file}",
                                    modifier = Modifier.size(if (review?.id == take.id) 21.dp else 17.dp), tint = reviewColor)
                            }
                        }
                    }
                }
            }
            if (placed.isNotEmpty() || pendingReviews.isNotEmpty() || inserts.isNotEmpty()) Box(Modifier.fillMaxWidth().height(40.dp).clipToBounds()) {
                Canvas(Modifier.matchParentSize().pointerInput(placed, pendingReviews, inserts, master, review?.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = layout()
                        val scrollX = scrollNow()
                        val at = g.msAt(down.position.x, scrollX)
                        if (!master.contains(at)) return@awaitEachGesture
                        val badge = pendingReviews.firstOrNull { item ->
                            placed.firstOrNull { it.file == item.file }?.startMs?.let { begin ->
                                val x = g.rawX(origin + maxOf(master.fromMs, begin), scrollX) + 7.dp.toPx()
                                abs(x - down.position.x) <= 11.dp.toPx() && down.position.y <= 16.dp.toPx()
                            } == true
                        }
                        val hits = placed.filter { it.available(at - origin) }
                        val take = hits.firstOrNull { it.id == shown } ?: hits.lastOrNull()
                        val insert = inserts.firstOrNull { at - origin in it.atMs until it.endMs }
                        if (badge == null && take == null && insert == null) return@awaitEachGesture
                        val tap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (change.isConsumed || event.changes.count { it.pressed } > 1 ||
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) return@withTimeoutOrNull false
                                if (!change.pressed) {
                                    change.consume()
                                    return@withTimeoutOrNull true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                        if (tap == true) {
                            if (badge != null) toggle(badge) else if (insert != null) {
                                reviewId = null
                                result()
                                seekResult(at)
                            } else if (take != null) {
                                reviewId = null
                                inspect(take, at)
                            }
                        }
                        if (tap != null || badge != null ||
                            (insert == null && (take == null || take.file == group.referenceFile)) ||
                            (insert != null && insert.lengthMs > master.durationMs)) return@awaitEachGesture
                        val start = insert?.atMs ?: checkNotNull(take).previewStartMs
                        var offset = start
                        var dragging = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                                if (!change.pressed) {
                                    if (dragging) {
                                        if (insert != null) {
                                            vm.moveMulticamCutaway(insert, offset)
                                            result()
                                        } else if (take != null) {
                                            vm.placeMulticamTake(take.id, offset)
                                            inspect(take, null)
                                        }
                                    }
                                    change.consume()
                                    break
                                }
                                val dx = change.position.x - down.position.x
                                if (!dragging && abs(dx) < 1f) continue
                                if (!dragging) draggingChange(true)
                                dragging = true
                                change.consume()
                                val target = start + (dx / g.pxPerMs.coerceAtLeast(0.0001f)).roundToLong()
                                if (insert != null) {
                                    offset = target.coerceIn(master.fromMs, master.sourceEndMs - insert.lengthMs)
                                    draggingCutaway = insert to offset
                                } else if (take != null) {
                                    offset = target.coerceIn(master.fromMs + 1L - take.lengthMs, master.sourceEndMs - 1L)
                                    pendingDrag = take.id to offset
                                }
                            }
                        } finally {
                            pendingDrag = null
                            draggingCutaway = null
                            if (dragging) draggingChange(false)
                        }
                    }
                }) {
                    val g = layout()
                    val scrollX = scrollNow()
                    drawRect(bg)
                    for (take in placed) {
                        val start = origin + (pendingDrag?.takeIf { it.first == take.id }?.second ?: take.previewStartMs)
                        val x = g.rawX(maxOf(start, master.startMs), scrollX).coerceIn(0f, size.width)
                        val end = g.rawX(minOf(start + take.lengthMs, master.endMs), scrollX).coerceIn(0f, size.width)
                        if (end <= x) continue
                        val span = Size(end - x, size.height)
                        drawRect(color.copy(alpha = 0.22f), Offset(x, 0f), span)
                        if (shown == take.id || pendingDrag?.first == take.id) {
                            drawRect(color, Offset(x, 0f), span, style = Stroke(2.dp.toPx()))
                        }
                        val samples = peaks[take.file] ?: continue
                        val from = (g.rawMsAt(x, scrollX) - start).coerceIn(0L, take.lengthMs)
                        val to = (g.rawMsAt(end, scrollX) - start).coerceIn(from, take.lengthMs)
                        if (to <= from) continue
                        val count = ((end - x) / 3f).toInt().coerceAtLeast(1)
                        val wave = TrackAudio.window(samples, take.sourceFromMs + from, take.sourceFromMs + to, count)
                        for ((column, peak) in wave.withIndex()) {
                            val px = x + (column + 0.5f) * (end - x) / count
                            val middle = size.height / 2
                            val h = peak * middle
                            drawLine(color, Offset(px, middle - h), Offset(px, middle + h), 1.5f)
                        }
                    }
                    for (cut in inserts) {
                        val from = draggingCutaway?.takeIf { it.first == cut }?.second ?: cut.atMs
                        val left = g.rawX(origin + maxOf(master.fromMs, from), scrollX).coerceIn(0f, size.width)
                        val right = g.rawX(origin + minOf(master.sourceEndMs, from + cut.lengthMs), scrollX).coerceIn(0f, size.width)
                        if (right <= left) continue
                        val bar = Size(right - left, size.height - 12.dp.toPx())
                        drawRect(color.copy(alpha = if (cut.manual || draggingCutaway?.first == cut) 0.64f else 0.37f),
                            Offset(left, 6.dp.toPx()), bar)
                        drawRect(color, Offset(left, 6.dp.toPx()), bar, style = Stroke(1.dp.toPx()))
                    }
                    if (review != null) {
                        val dashed = Stroke(2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
                        for (span in previewParts) {
                            val from = maxOf(master.fromMs, span.timelineStart)
                            val until = minOf(master.sourceEndMs, span.timelineEnd)
                            val left = g.rawX(origin + from, scrollX).coerceIn(0f, size.width)
                            val right = g.rawX(origin + until, scrollX).coerceIn(0f, size.width)
                            if (right > left) drawRect(reviewColor, Offset(left, 2.dp.toPx()),
                                Size(right - left, size.height - 4.dp.toPx()), style = dashed)
                        }
                    }
                    for (item in pendingReviews) {
                        val begin = placed.firstOrNull { it.file == item.file }?.startMs ?: continue
                        val x = g.rawX(origin + maxOf(master.fromMs, begin), scrollX) + 7.dp.toPx()
                        if (x in 0f..size.width) drawCircle(reviewColor, 3.dp.toPx(), Offset(x, 7.dp.toPx()))
                    }
                    val x = g.rawX(clock(), scrollX)
                    if (x in 0f..size.width) drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            if (cutaways.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Перебивка", "Cutaway"), color = color, style = MaterialTheme.typography.labelMedium)
                cutaways.forEach { take ->
                    Box {
                        TextButton({ selectedCutaway = take.id }) { Text(take.file, maxLines = 1) }
                        if (selectedCutaway == take.id) DropdownMenu(expanded = true, onDismissRequest = { selectedCutaway = null }) {
                            DropdownMenuItem(
                                text = { Text(tr("Поставить у курсора", "Place at playhead")) },
                                enabled = master.contains(clock()),
                                onClick = {
                                    selectedCutaway = null
                                    vm.placeMulticamCutaway(take.id, clock())
                                    onResult()
                                },
                            )
                            DropdownMenuItem(text = { Text(tr("Вернуть в синхронизацию", "Return to sync")) }, onClick = {
                                selectedCutaway = null
                                vm.reopenMulticamCutaway(take.id)
                                onResult()
                            })
                        }
                    }
                }
            }
            if (review != null) MulticamReviewActions(vm, group, master, review, clock) {
                reviewId = null
                onResult()
            }
        }
    }
}
