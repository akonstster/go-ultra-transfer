package app.goultra.transfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.MediaKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale.forLanguageTag("ru"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    controller: CameraController,
    onConnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onShareLog: () -> Unit,
    onRecord: () -> Unit,
) {
    val vm = controller
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    val selectionCount = vm.selected.size
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionCount > 0) "Выбрано: $selectionCount" else "GO Ultra") },
                navigationIcon = {
                    if (selectionCount > 0) {
                        IconButton(vm::clearSelection) { Icon(Icons.Filled.Close, "Снять выбор") }
                    }
                },
                actions = {
                    // Delete sits up here, far from «Скачать» at the bottom, and always asks first.
                    if (selectionCount > 0 && vm.tab == Tab.FILES && vm.conn == ConnState.Connected) {
                        var confirm by remember { mutableStateOf(false) }
                        IconButton({ confirm = true }, enabled = !vm.deleting && vm.download == null) {
                            Icon(Icons.Filled.Delete, "Удалить с камеры")
                        }
                        if (confirm) {
                            AlertDialog(
                                onDismissRequest = { confirm = false },
                                icon = { Icon(Icons.Filled.Delete, null) },
                                title = { Text("Удалить с камеры?") },
                                text = {
                                    Text(
                                        (if (selectionCount == 1) "Файл будет удалён с карты камеры — восстановить его"
                                        else "${countLabel(selectionCount)} будут удалены с карты камеры — восстановить их") +
                                            " будет нельзя. Уже скачанные копии в галерее телефона останутся.",
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        { confirm = false; vm.deleteSelected() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text("Удалить") }
                                },
                                dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
                            )
                        }
                    }
                    if (vm.conn == ConnState.Connected || (vm.tab == Tab.CAMERA && vm.conn == ConnState.Remote)) {
                        IconButton(vm::refresh, enabled = vm.download == null) { Icon(Icons.Filled.Refresh, "Обновить") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                    DropdownMenu(menu, { menu = false }) {
                        if (vm.conn != ConnState.Idle) {
                            DropdownMenuItem({ Text("Отключиться") }, { menu = false; vm.disconnect() })
                        }
                        DropdownMenuItem({ Text("Настроить камеру заново") }, { menu = false; vm.restartOnboarding() })
                        DropdownMenuItem({ Text("Отправить журнал") }, { menu = false; onShareLog() })
                        DropdownMenuItem({ Text("Диагностика") }, { menu = false; onOpenDiagnostics() })
                        if (vm.isDebugBuild && vm.isLinked) {
                            DropdownMenuItem(
                                { Text(if (vm.selfTestRunning) "Самопроверка идёт…" else "Самопроверка настроек") },
                                { menu = false; vm.selfTest() },
                                enabled = !vm.selfTestRunning,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { if (vm.tab == Tab.FILES) BottomPanel(vm) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                Tab(vm.tab == Tab.FILES, { vm.tab = Tab.FILES; vm.touch() }, text = { Text("Файлы") })
                Tab(vm.tab == Tab.CAMERA, { vm.tab = Tab.CAMERA; vm.touch() }, text = { Text("Камера") })
            }
            PhoneRadiosBanner(vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.tab == Tab.CAMERA -> CameraTab(vm, onConnect, onRecord)
                    vm.conn == ConnState.Connected -> FileGrid(vm)
                    else -> ConnectPanel(vm.conn, onConnect, onRecord)
                }
            }
        }
    }
}

@Composable
private fun ConnectPanel(state: ConnState, onConnect: () -> Unit, onRecord: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is ConnState.Working -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(state.step, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            else -> {
                Text(
                    if (state == ConnState.Remote) "Подключено только по Bluetooth" else "Камера не подключена",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                if (state is ConnState.Failed) {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "Включать камеру не нужно — приложение разбудит её по Bluetooth. " +
                        "При самом первом подключении включите её кнопкой.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(24.dp))
                Button(onConnect) { Text(if (state == ConnState.Remote) "Подключиться по Wi-Fi" else "Подключиться") }
                Spacer(Modifier.height(8.dp))
                TextButton(onRecord) { Text("● Разбудить и начать запись") }
            }
        }
    }
}

@Composable
private fun FileGrid(vm: CameraController) {
    val files = vm.visibleFiles
    val byDay = remember(files) { files.groupBy { it.created?.toLocalDate() } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { vm.touch() } // scrolling counts as activity
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val photos = vm.files.count { it.kind == MediaKind.PHOTO }
            val videos = vm.files.size - photos
            val pick = { f: Filter -> vm.filter = f; vm.touch() }
            FilterChip(vm.filter == Filter.ALL, { pick(Filter.ALL) }, { Text("Все ${vm.files.size}") })
            FilterChip(vm.filter == Filter.PHOTO, { pick(Filter.PHOTO) }, { Text("Фото $photos") })
            FilterChip(vm.filter == Filter.VIDEO, { pick(Filter.VIDEO) }, { Text("Видео $videos") })
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            byDay.forEach { (day, dayFiles) ->
                item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                    DayHeader(day, dayFiles.size)
                }
                items(dayFiles, key = { it.uri }) { file ->
                    FileTile(
                        file = file,
                        selected = file.name in vm.selected,
                        downloaded = file.name in vm.downloaded,
                        loadThumbnail = vm::thumbnail,
                        onClick = { vm.toggle(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate?, count: Int) {
    Text(
        (day?.format(DAY_FORMAT) ?: "Без даты") + " · $count",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun FileTile(
    file: CameraFile,
    selected: Boolean,
    downloaded: Boolean,
    loadThumbnail: suspend (CameraFile) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    // Keyed by size too: a just-shot RAW gets its size a moment later, by then its paired .jpg (the preview) exists.
    val thumb by produceState<ImageBitmap?>(null, file.uri, file.size) { value = loadThumbnail(file) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        thumb?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(
                file.name.substringBefore('_'),
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        val label = when {
            file.kind == MediaKind.VIDEO -> "▶ " + (file.durationSec?.let(::formatDuration) ?: "")
            file.name.endsWith(".dng", ignoreCase = true) -> "RAW"
            file.isLive -> "LIVE"
            else -> null
        }
        label?.let { Badge(it, Modifier.align(Alignment.BottomStart)) }
        if (downloaded) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp)
                    .background(Color(0xFF2E7D32), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "Скачано", Modifier.size(13.dp), tint = Color.White) }
        }
        if (selected) {
            Box(Modifier.matchParentSizeOverlay())
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "Выбрано", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

private fun Modifier.matchParentSizeOverlay(): Modifier = fillMaxSize().background(Color(0x33000000))

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        modifier
            .padding(4.dp)
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun BottomPanel(vm: CameraController) {
    val progress = vm.download
    val selection = vm.selectedFiles
    if (progress == null && (vm.conn != ConnState.Connected)) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                progress != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Скачивание ${progress.index} из ${progress.count} · ${progress.name}", fontSize = 14.sp)
                            Text(
                                progress.details(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(vm::cancelDownload) { Text("Стоп") }
                    }
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                selection.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    var askAlready by remember { mutableStateOf(0) }
                    Text(
                        "${selection.size} шт. · ${formatSize(selection.sumOf { it.size ?: 0L })}",
                        Modifier.weight(1f),
                    )
                    Button({
                        val already = vm.selectedAlreadyDownloaded
                        if (already > 0) askAlready = already else vm.downloadSelected(overwrite = false)
                    }) { Text("Скачать") }
                    if (askAlready > 0) AlreadyDownloadedDialog(vm, askAlready) { askAlready = 0 }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val newCount = vm.visibleFiles.count { it.name !in vm.downloaded }
                    Text(
                        if (newCount > 0) "Не скачано: $newCount" else "Всё скачано",
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (newCount > 0) TextButton(vm::selectNew) { Text("Выбрать новые") }
                }
            }
        }
    }
}

/** Some selected files are already in the gallery: skip them or overwrite (same file, no duplicate). */
@Composable
private fun AlreadyDownloadedDialog(vm: CameraController, count: Int, onDismiss: () -> Unit) {
    val total = vm.selected.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Уже в галерее: $count из $total") },
        text = { Text("Пропустить их или скачать заново поверх имеющихся? Дубликатов не будет.") },
        confirmButton = {
            TextButton({ onDismiss(); vm.downloadSelected(overwrite = false) }) { Text("Пропустить") }
        },
        dismissButton = {
            TextButton({ onDismiss(); vm.downloadSelected(overwrite = true) }) { Text("Перезаписать") }
        },
    )
}
