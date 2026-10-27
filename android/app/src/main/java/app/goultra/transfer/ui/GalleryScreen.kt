package app.goultra.transfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import app.goultra.transfer.camera.Downloader
import kotlinx.coroutines.launch
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

private val DAY_FORMAT_RU = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale.forLanguageTag("ru"))
private val DAY_FORMAT_EN = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

private fun dayFormat() = if (AppPrefs.russian) DAY_FORMAT_RU else DAY_FORMAT_EN

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
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionCount > 0) tr("Выбрано: $selectionCount", "Selected: $selectionCount") else "GO Ultra") },
                navigationIcon = {
                    if (selectionCount > 0) {
                        IconButton(vm::clearSelection) { Icon(Icons.Filled.Close, tr("Снять выбор", "Clear selection")) }
                    }
                },
                actions = {
                    // Delete sits up here, far from «Скачать» at the bottom, and always asks first.
                    if (selectionCount > 0 && vm.tab == Tab.FILES && vm.conn == ConnState.Connected) {
                        var confirm by remember { mutableStateOf(false) }
                        IconButton({ confirm = true }, enabled = !vm.deleting && vm.download == null) {
                            Icon(Icons.Filled.Delete, tr("Удалить с камеры", "Delete from camera"))
                        }
                        if (confirm) {
                            AlertDialog(
                                onDismissRequest = { confirm = false },
                                icon = { Icon(Icons.Filled.Delete, null) },
                                title = { Text(tr("Удалить с камеры?", "Delete from the camera?")) },
                                text = {
                                    Text(
                                        if (selectionCount == 1) {
                                            tr(
                                                "Файл будет удалён с карты камеры — восстановить его будет нельзя. " +
                                                    "Уже скачанная копия в галерее телефона останется.",
                                                "The file will be deleted from the camera's card and can't be recovered. " +
                                                    "A copy already in the phone's gallery stays.",
                                            )
                                        } else {
                                            tr(
                                                "${countLabel(selectionCount)} будут удалены с карты камеры — восстановить их " +
                                                    "будет нельзя. Уже скачанные копии в галерее телефона останутся.",
                                                "${countLabel(selectionCount)} will be deleted from the camera's card and can't be " +
                                                    "recovered. Copies already in the phone's gallery stay.",
                                            )
                                        },
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        { confirm = false; vm.deleteSelected() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text(tr("Удалить", "Delete")) }
                                },
                                dismissButton = { TextButton({ confirm = false }) { Text(tr("Отмена", "Cancel")) } },
                            )
                        }
                    }
                    if (vm.conn == ConnState.Connected || (vm.tab == Tab.CAMERA && vm.conn == ConnState.Remote)) {
                        IconButton(vm::refresh, enabled = vm.download == null) { Icon(Icons.Filled.Refresh, tr("Обновить", "Refresh")) }
                    }
                    var menu by remember { mutableStateOf(false) }
                    var showSettings by remember { mutableStateOf(false) }
                    var showCamera by remember { mutableStateOf(false) }
                    if (showSettings) AppSettingsDialog { showSettings = false }
                    if (showCamera) CameraInfoDialog(vm) { showCamera = false }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, tr("Меню", "Menu")) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(tr("Камера", "Camera")) }, { menu = false; showCamera = true })
                        if (vm.conn != ConnState.Idle) {
                            DropdownMenuItem({ Text(tr("Отключиться", "Disconnect")) }, { menu = false; vm.disconnect() })
                        }
                        DropdownMenuItem({ Text(tr("Настройки", "Settings")) }, { menu = false; showSettings = true })
                        DropdownMenuItem({ Text(tr("Настроить камеру заново", "Set up the camera again")) }, { menu = false; vm.restartOnboarding() })
                        DropdownMenuItem({ Text(tr("Отправить журнал", "Send log")) }, { menu = false; onShareLog() })
                        // Developer tools only in debug builds (and in Russian only).
                        if (vm.isDebugBuild) DropdownMenuItem({ Text("Диагностика") }, { menu = false; onOpenDiagnostics() })
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
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                Tab(vm.tab == Tab.FILES, { vm.tab = Tab.FILES; vm.touch() }, text = { Text(tr("Файлы", "Files")) })
                Tab(vm.tab == Tab.CAMERA, { vm.tab = Tab.CAMERA; vm.touch() }, text = { Text(tr("Камера", "Camera")) })
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
    // Phone turned sideways on the camera tab: full-screen live view (the tilt sensor runs only here).
    var sideways = 0
    if (vm.tab == Tab.CAMERA && vm.conn == ConnState.Connected) {
        sideways = rememberSidewaysRotation()
        if (sideways != 0) LandscapeLive(vm, sideways, onRecord)
    }
    // Messages away from the controls (they are at the bottom): under the tabs, or mid-screen turned with the
    // picture when the phone is sideways.
    SnackbarHost(
        snackbar,
        Modifier.align(if (sideways != 0) Alignment.Center else Alignment.TopCenter)
            .padding(top = if (sideways != 0) 0.dp else 150.dp)
            .graphicsLayer { rotationZ = sideways.toFloat() },
    )
    }
}

@Composable
private fun ConnectPanel(state: ConnState, onConnect: () -> Unit, onRecord: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Which camera this is about; its LED pulses while connecting.
        CameraArt(Modifier.fillMaxWidth(0.62f).widthIn(max = 320.dp), withPod = true, blinking = state is ConnState.Working)
        Spacer(Modifier.height(28.dp))
        when (state) {
            is ConnState.Working -> {
                LinearProgressIndicator(Modifier.widthIn(max = 200.dp).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(state.step, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            else -> {
                Text(
                    if (state == ConnState.Remote) tr("Подключено только по Bluetooth", "Connected over Bluetooth only") else tr("Камера не подключена", "Camera not connected"),
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
                    tr(
                        "Включать камеру не нужно — приложение разбудит её по Bluetooth. " +
                            "При самом первом подключении включите её кнопкой.",
                        "No need to switch the camera on — the app wakes it over Bluetooth. " +
                            "For the very first connection, switch it on with its button.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(24.dp))
                Button(onConnect) { Text(if (state == ConnState.Remote) tr("Подключиться по Wi-Fi", "Connect over Wi-Fi") else tr("Подключиться", "Connect")) }
                Spacer(Modifier.height(8.dp))
                TextButton(onRecord) { Text(tr("● Разбудить и начать запись", "● Wake up and start recording")) }
            }
        }
    }
}

@Composable
private fun FileGrid(vm: CameraController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Downloaded: the phone's own player / gallery; still on the camera (or no app for it): the in-app viewer. */
    fun open(file: CameraFile) {
        // A live photo opens in our viewer while the camera is there: its clip plays and the cover is chosen there.
        if (vm.liveClipOf(file) != null && vm.conn == ConnState.Connected) {
            vm.view(file)
            return
        }
        scope.launch {
            val uri = vm.galleryUri(file)
            val opened = uri != null && runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloader.mimeType(file.name))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.isSuccess
            if (!opened) vm.view(file)
        }
    }
    vm.viewing?.let { FileViewer(vm, it) { vm.viewing = null } }
    val files = vm.visibleFiles
    val byDay = remember(files) { files.groupBy { it.created?.toLocalDate() } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { vm.touch() } // scrolling counts as activity
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val listed = vm.listedFiles
            val photos = listed.count { it.kind == MediaKind.PHOTO }
            val videos = listed.size - photos
            val pick = { f: Filter -> vm.filter = f; vm.touch() }
            FilterChip(vm.filter == Filter.ALL, { pick(Filter.ALL) }, { Text(tr("Все ${listed.size}", "All ${listed.size}")) })
            FilterChip(vm.filter == Filter.PHOTO, { pick(Filter.PHOTO) }, { Text(tr("Фото $photos", "Photos $photos")) })
            FilterChip(vm.filter == Filter.VIDEO, { pick(Filter.VIDEO) }, { Text(tr("Видео $videos", "Videos $videos")) })
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
                        // Tap opens; long press starts selecting, then taps add / remove (like a gallery).
                        onClick = { if (vm.selected.isEmpty()) open(file) else vm.toggle(file) },
                        onLongClick = { vm.toggle(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate?, count: Int) {
    Text(
        (day?.format(dayFormat()) ?: tr("Без даты", "No date")) + " · $count",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    file: CameraFile,
    selected: Boolean,
    downloaded: Boolean,
    loadThumbnail: suspend (CameraFile) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Keyed by size too: a just-shot RAW gets its size a moment later, by then its paired .jpg (the preview) exists.
    // A just-taken shot may not be readable yet (the camera is still writing it): fresh files retry a few times.
    val thumb by produceState<ImageBitmap?>(null, file.uri, file.size) {
        val fresh = file.created?.isAfter(java.time.LocalDateTime.now().minusMinutes(10)) == true
        var attempt = 0
        while (true) {
            value = loadThumbnail(file)
            if (value != null || !fresh || ++attempt > 3) break
            kotlinx.coroutines.delay(2_000L * attempt)
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
            ) { Icon(Icons.Filled.Check, tr("Скачано", "Downloaded"), Modifier.size(13.dp), tint = Color.White) }
        }
        if (selected) {
            Box(Modifier.matchParentSizeOverlay())
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, tr("Выбрано", "Selected"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
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
                            Text(tr("Скачивание ${progress.index} из ${progress.count} · ${progress.name}", "Downloading ${progress.index} of ${progress.count} · ${progress.name}"), fontSize = 14.sp)
                            Text(
                                progress.details(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(vm::cancelDownload) { Text(tr("Стоп", "Stop")) }
                    }
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                selection.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    var askAlready by remember { mutableStateOf(0) }
                    Text(
                        tr("${selection.size} шт.", "${selection.size} items") + " · ${formatSize(selection.sumOf { it.size ?: 0L })}",
                        Modifier.weight(1f),
                    )
                    Button({
                        val already = vm.selectedAlreadyDownloaded
                        if (already > 0) askAlready = already else vm.downloadSelected(overwrite = false)
                    }) { Text(tr("Скачать", "Download")) }
                    if (askAlready > 0) AlreadyDownloadedDialog(vm, askAlready) { askAlready = 0 }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val newCount = vm.visibleFiles.count { it.name !in vm.downloaded }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (newCount > 0) tr("Не скачано: $newCount", "Not downloaded: $newCount") else tr("Всё скачано", "All downloaded"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            tr("Нажмите — открыть, удерживайте — выбрать", "Tap to open, hold to select"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (newCount > 0) TextButton(vm::selectNew) { Text(tr("Выбрать новые", "Select new")) }
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
        title = { Text(tr("Уже в галерее: $count из $total", "Already in the gallery: $count of $total")) },
        text = { Text(tr("Пропустить их или скачать заново поверх имеющихся? Дубликатов не будет.", "Skip them or download again over the existing ones? No duplicates will be made.")) },
        confirmButton = {
            TextButton({ onDismiss(); vm.downloadSelected(overwrite = false) }) { Text(tr("Пропустить", "Skip")) }
        },
        dismissButton = {
            TextButton({ onDismiss(); vm.downloadSelected(overwrite = true) }) { Text(tr("Перезаписать", "Overwrite")) }
        },
    )
}
