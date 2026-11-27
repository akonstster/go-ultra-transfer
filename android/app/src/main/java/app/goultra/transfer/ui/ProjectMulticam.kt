package app.goultra.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.MediaKind
import app.goultra.transfer.camera.Multicam
import app.goultra.transfer.camera.MulticamTake
import app.goultra.transfer.camera.Project
import app.goultra.transfer.camera.ProjectAudioClip
import app.goultra.transfer.camera.TrackAudio
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.withTimeoutOrNull

private fun cameraLabel(camera: Int) = tr("Камера %s", "Angle %s").format(camera)

internal data class MulticamHighlight(val start: Long, val end: Long, val cut: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MulticamSetup(vm: CameraController, project: Project, onDismiss: () -> Unit) {
    val selected = remember(project.id) {
        mutableStateMapOf<String, Int>().apply {
            project.multicam?.takes?.forEach { put(it.file, it.camera) }
        }
    }
    val videos = vm.listedFiles.filter { it.kind == MediaKind.VIDEO }.distinctBy { it.name }
    val audio = vm.projectAudioFiles.map { it.name }
    var reference by remember(project.id) {
        mutableStateOf(project.multicam?.referenceFile ?: project.audioClips.firstOrNull { it.source.name in audio }?.source?.name)
    }
    var choosingReference by remember { mutableStateOf(false) }
    val referenceOptions = selected.keys.sorted() + audio.filter { it !in selected }
    val master = reference.takeIf { it in referenceOptions }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(tr("Мультикам", "Multicam"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Выбери записи и назначь один номер файлам с одной камеры.",
                "Select recordings and give files from the same camera the same angle."))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(videos, key = { it.name }) { file ->
                    val angle = selected[file.name]
                    var chooseAngle by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(angle != null, onCheckedChange = { checked ->
                            if (checked) {
                                selected[file.name] = (selected.values.maxOrNull() ?: 0) + 1
                                if (reference == null) reference = file.name
                            } else {
                                selected.remove(file.name)
                                if (reference == file.name) reference = selected.keys.sorted().firstOrNull()
                            }
                        })
                        Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (angle != null) Box {
                            TextButton({ chooseAngle = true }) { Text(cameraLabel(angle)) }
                            val angles = (selected.values.toSet() + ((selected.values.maxOrNull() ?: 0) + 1)).sorted()
                            DropdownMenu(expanded = chooseAngle, onDismissRequest = { chooseAngle = false }) {
                                angles.forEach { number ->
                                    DropdownMenuItem(text = { Text(cameraLabel(number)) }, onClick = {
                                        selected[file.name] = number
                                        chooseAngle = false
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
                    Text(tr("Основной звук", "Reference sound") + " · " + master.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = choosingReference, onDismissRequest = { choosingReference = false }) {
                    referenceOptions.forEach { name ->
                        DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                            reference = name
                            choosingReference = false
                        })
                    }
                }
            }
            if (project.clips.isNotEmpty() || project.audioClips.isNotEmpty()) {
                Text(tr("Синхронизированные ракурсы добавляются в проект как один клип со своим звуком.",
                    "Synced angles are added as one clip with linked audio."),
                    style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(
                onClick = {
                    if (master != null) vm.buildMulticam(project, selected.toMap(), master)
                    onDismiss()
                },
                enabled = master != null && selected.values.toSet().size >= 2 && vm.multicamProgress == null,
            ) { Text(tr("Синхронизировать", "Synchronise")) }
        }
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
    var confirmParts by remember { mutableStateOf<MulticamTake?>(null) }
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
    val flagged = group.takes.count { it.needsReview || it.startMs == null }
    if (flagged > 0 && expanded) {
        Text(tr("Автосинхронизация не подтвердила %s записей целиком", "Auto sync could not verify %s full recordings").format(flagged),
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            group.takes.filter { it.proposedSpans.size >= 2 }.forEach { take ->
                TextButton({ confirmParts = take }) {
                    Text(tr("Совпавшие части · %s", "Matched parts · %s").format(cameraLabel(take.camera)))
                }
            }
        }
    }
    confirmParts?.let { take ->
        AlertDialog(
            onDismissRequest = { confirmParts = null },
            title = { Text(tr("Добавить совпавшие части?", "Add matching sections?")) },
            text = {
                Text(take.file + "\n\n" + tr(
                    "Найдено совпавших частей (%s). Можно добавить их отдельно или оставить целый файл с наиболее подтверждённым сдвигом.",
                    "Found matching sections (%s). Add them separately or keep the full file at the best-supported offset.",
                ).format(take.proposedSpans.size))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({
                        vm.resolveMulticamAlignment(take.id, keepWhole = false)
                        confirmParts = null
                        onResult()
                    }) { Text(tr("Добавить части", "Add sections")) }
                    TextButton({
                        vm.resolveMulticamAlignment(take.id, keepWhole = true)
                        confirmParts = null
                        onResult()
                    }) { Text(tr("Оставить целым файлом", "Keep the full file")) }
                    TextButton({ confirmParts = null }) { Text(tr("Не сейчас", "Not now")) }
                }
            },
        )
    }
    if (audition != null) {
        Text(tr("Звук ракурса: %s", "Angle audio: %s").format(audition), Modifier.padding(horizontal = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
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
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    val warningColor = MaterialTheme.colorScheme.error
    val shown by rememberUpdatedState(audition)
    val inspect by rememberUpdatedState(onInspect)
    val origin = master.startMs - master.fromMs
    fun shownStart(take: MulticamTake): Long {
        val start = take.previewStartMs
        return if ((take.needsReview || take.startMs == null) &&
            (start >= master.sourceEndMs || start + take.lengthMs <= master.fromMs)) master.fromMs else start
    }
    val files = remember(group.takes) { group.takes.map { it.file } }
    val peaks = remember(files, vm.musicVersion) { files.associateWith(vm::multicamPeaks) }
    Column(modifier) {
        group.cameras.forEachIndexed { index, camera ->
            val color = colors[index % colors.size]
            val takes = group.takes.filter { it.camera == camera }
            val placed = takes.filter { !it.needsReview && it.startMs != null }
            val review = takes - placed.toSet()
            val lanes = listOf(placed).filter { it.isNotEmpty() } + review.map(::listOf)
            lanes.forEach { lane ->
                val warning = lane.singleOrNull()?.takeIf { it.startMs == null || it.needsReview }
                var pending by remember(lane.map { it.id }) { mutableStateOf<Pair<String, Long>?>(null) }
                Box(Modifier.fillMaxWidth().height(50.dp).clipToBounds()) {
                    Canvas(Modifier.matchParentSize().pointerInput(lane, master.startMs, master.fromMs, master.sourceEndMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val g = geometry()
                            val at = g.msAt(down.position.x, scroll())
                            if (!master.contains(at)) return@awaitEachGesture
                            val hits = lane.filter { at - origin in shownStart(it) until shownStart(it) + it.lengthMs }
                            val take = hits.firstOrNull { it.id == shown } ?: hits.lastOrNull()
                                ?: return@awaitEachGesture
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
                            if (tap == true) inspect(take, at)
                            if (tap != null || take.file == group.referenceFile) return@awaitEachGesture
                            var dragging = false
                            val start = shownStart(take)
                            var offset = start
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                                    if (!change.pressed) {
                                        if (dragging) {
                                            vm.placeMulticamTake(take.id, offset)
                                            inspect(take, null)
                                        }
                                        change.consume()
                                        break
                                    }
                                    val dx = change.position.x - down.position.x
                                    if (!dragging && abs(dx) < 1f) continue
                                    if (!dragging) onDraggingChange(true)
                                    dragging = true
                                    change.consume()
                                    offset = (start + (dx / g.pxPerMs.coerceAtLeast(0.0001f)).roundToLong())
                                        .coerceIn(master.fromMs + 1L - take.lengthMs, master.sourceEndMs - 1L)
                                    pending = take.id to offset
                                }
                            } finally {
                                pending = null
                                if (dragging) onDraggingChange(false)
                            }
                        }
                    }) {
                        val g = geometry()
                        val at = scroll()
                        drawRect(bg)
                        val top = 17.dp.toPx()
                        for (take in lane) {
                            val start = origin + (pending?.takeIf { it.first == take.id }?.second ?: shownStart(take))
                            val x = g.rawX(maxOf(start, master.startMs), at).coerceIn(0f, size.width)
                            val end = g.rawX(minOf(start + take.lengthMs, master.endMs), at).coerceIn(0f, size.width)
                            if (end <= x) continue
                            val span = Size(end - x, size.height - top)
                            val ink = if (warning != null) warningColor else color
                            drawRect(ink.copy(alpha = 0.22f), Offset(x, top), span)
                            if (shown == take.id || pending?.first == take.id) {
                                drawRect(color, Offset(x, top), span, style = Stroke(2.dp.toPx()))
                            }
                            val samples = peaks[take.file] ?: continue
                            val from = (g.rawMsAt(x, at) - start).coerceIn(0L, take.lengthMs)
                            val to = (g.rawMsAt(end, at) - start).coerceIn(from, take.lengthMs)
                            if (to <= from) continue
                            val count = ((end - x) / 3f).toInt().coerceAtLeast(1)
                            val wave = TrackAudio.window(samples, take.sourceFromMs + from, take.sourceFromMs + to, count)
                            val middle = (top + size.height) / 2
                            for ((column, peak) in wave.withIndex()) {
                                val px = x + (column + 0.5f) * (end - x) / count
                                val h = peak * (size.height - top) / 2
                                drawLine(ink, Offset(px, middle - h), Offset(px, middle + h), 1.5f)
                            }
                        }
                        val x = g.rawX(clock(), at)
                        if (x in 0f..size.width) drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    Text(
                        cameraLabel(camera) + (warning?.let { " · ⚠ ${it.file}" }
                            ?: lane.firstOrNull { it.id == audition }?.let { " · ${it.file}" }.orEmpty()),
                        Modifier.padding(horizontal = 6.dp).align(Alignment.TopStart),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
