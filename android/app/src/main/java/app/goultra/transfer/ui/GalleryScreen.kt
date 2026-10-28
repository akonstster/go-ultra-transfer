package app.goultra.transfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
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
                    if (selectionCount > 0 && vm.tab == Tab.FILES && vm.conn == ConnState.Connected && vm.selectedFiles.none { it.isOnPhone }) {
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
                    // Videos and photos from elsewhere on the phone, to view, cut and colour (with or without the camera).
                    if (vm.tab == Tab.FILES && selectionCount == 0) {
                        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addFiles(uris) }
                        IconButton({ pick.launch(arrayOf("video/*", "image/*")) }) {
                            Icon(Icons.Filled.LibraryAdd, tr("Добавить с телефона", "Add from the phone"))
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
                    // No camera: what is already on the phone, with the way to connect above it.
                    vm.listedFiles.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                        ConnectBar(vm.conn, onConnect)
                        FileGrid(vm)
                    }
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

/** Above the phone's files while the camera isn't connected: the link state and «Подключиться». */
@Composable
private fun ConnectBar(state: ConnState, onConnect: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (state) {
                        is ConnState.Working -> state.step
                        ConnState.Remote -> tr("Подключено только по Bluetooth", "Connected over Bluetooth only")
                        else -> tr("Камера не подключена", "Camera not connected")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (state is ConnState.Failed) state.message else tr("Показаны файлы на телефоне", "Showing the files on the phone"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is ConnState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is ConnState.Working) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(22.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onConnect) { Text(if (state == ConnState.Remote) tr("Подключиться по Wi-Fi", "Connect over Wi-Fi") else tr("Подключиться", "Connect")) }
            }
        }
    }
}

@Composable
private fun FileGrid(vm: CameraController) {
    val scope = rememberCoroutineScope()
    /** Everything opens in the in-app viewer: the phone's copy when there is one, otherwise from the camera. */
    fun open(file: CameraFile) {
        if (file.isOnPhone) {
            vm.view(file)
            return
        }
        // From the camera: the phone's copy when downloaded (a live photo stays the camera's — its cover choice).
        scope.launch { vm.view(vm.withPhoneCopy(file)) }
    }
    // With the camera there, one selection is either the card's files or the phone's (its first file decides): the
    // bottom bar then offers what fits — download / delete, or share / paste colour / take off the list.
    val selectionOnPhone = vm.selectedFiles.firstOrNull()?.isOnPhone
    fun selectable(file: CameraFile) = vm.showsPhone || selectionOnPhone == null || selectionOnPhone == file.isOnPhone
    vm.viewing?.let { FileViewer(vm, it) { vm.viewing = null } }
    val files = vm.visibleFiles
    val byDay = remember(files) { files.groupBy { it.created?.toLocalDate() } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { vm.touch() } // scrolling counts as activity
    }
    Column(Modifier.fillMaxSize()) {
        // Scrolls sideways when the chips don't fit (narrow screens, large font).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val listed = vm.listedFiles
            val photos = listed.count { it.kind == MediaKind.PHOTO }
            val videos = listed.size - photos
            val pick = { f: Filter -> vm.filter = f; vm.touch() }
            FilterChip(vm.filter == Filter.ALL, { pick(Filter.ALL) }, { Text(tr("Все ${listed.size}", "All ${listed.size}")) })
            FilterChip(vm.filter == Filter.PHOTO, { pick(Filter.PHOTO) }, { Text(tr("Фото $photos", "Photos $photos")) })
            FilterChip(vm.filter == Filter.VIDEO, { pick(Filter.VIDEO) }, { Text(tr("Видео $videos", "Videos $videos")) })
            // The rest only when there is something in them (or picked).
            @Composable
            fun Extra(f: Filter, count: Int, label: String) {
                if (count > 0 || vm.filter == f) FilterChip(vm.filter == f, { pick(f) }, { Text("$label $count") })
            }
            Extra(Filter.FAVORITE, listed.count { it.name in vm.favorites }, "♥")
            Extra(Filter.ADDED, listed.count { it.isAdded }, tr("Добавленные", "Added"))
            Extra(Filter.COLOURED, listed.count { it.kind == MediaKind.VIDEO && vm.lookVersion >= 0 && vm.hasLook(it) }, tr("Свой цвет", "Own colour"))
            Extra(Filter.SAVED, listed.count(vm::isSaved), tr("Сохранённые", "Saved"))
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
                        // On the phone list everything is downloaded: no mark needed.
                        downloaded = !file.isOnPhone && file.name in vm.downloaded,
                        favorite = file.name in vm.favorites,
                        coloured = file.kind == MediaKind.VIDEO && vm.lookVersion >= 0 && vm.hasLook(file),
                        phoneOnly = file.isOnPhone && !vm.showsPhone,
                        added = file.isAdded,
                        loadThumbnail = vm::thumbnail,
                        // Tap opens; long press starts selecting, then taps add / remove (like a gallery). On the
                        // phone list a selection is for sharing; with the camera it is for its card (download,
                        // delete), so files only on the phone just open.
                        onClick = { if (vm.selected.isEmpty() || !selectable(file)) open(file) else vm.toggle(file) },
                        onLongClick = { if (selectable(file)) vm.toggle(file) },
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
    favorite: Boolean,
    coloured: Boolean,
    phoneOnly: Boolean,
    added: Boolean,
    loadThumbnail: suspend (CameraFile) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Keyed by size too: a just-shot RAW gets its size a moment later, by then its paired .jpg (the preview) exists.
    // A just-taken shot may not be readable yet (the camera is still writing it): fresh files retry a few times.
    // And by the phone copy: a file saved again under the same name is a new gallery item.
    val thumb by produceState<ImageBitmap?>(null, file.uri, file.size, file.localUri) {
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
        if (favorite) {
            Icon(
                Icons.Filled.Favorite, tr("Избранное", "Favorite"),
                Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp),
                tint = Color(0xFFFF5252),
            )
        }
        if (coloured) {
            // A colour of its own set in the viewer (or pasted).
            Box(
                Modifier.align(Alignment.TopStart).padding(start = if (favorite) 27.dp else 5.dp, top = 5.dp).size(18.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Palette, tr("Свой цвет", "Own colour"), Modifier.size(12.dp), tint = MARK_COLOR) }
        }
        if (added) {
            // Added from elsewhere on the phone.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.FolderOpen, tr("Добавлен с телефона", "Added from the phone"), Modifier.size(12.dp), tint = Color.White) }
        } else if (phoneOnly) {
            // Only on the phone (a clip made here, or deleted from the card).
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PhoneAndroid, tr("Только на телефоне", "On the phone only"), Modifier.size(12.dp), tint = Color.White) }
        }
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

/** The copied colour (from the viewer's colour panel) onto all selected videos at once; shown while one is copied. */
@Composable
private fun PasteColour(vm: CameraController, selection: List<CameraFile>) {
    if (vm.copiedLook == null || selection.none { it.kind == MediaKind.VIDEO }) return
    IconButton(vm::pasteLookToSelected) {
        Icon(Icons.Filled.ContentPaste, tr("Вставить цвет в выбранные видео", "Paste the colour into the selected videos"), tint = MaterialTheme.colorScheme.primary)
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
    // Saving a video with its colour (from the viewer): its own bar, in percent.
    vm.export?.let { e ->
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Сохранение с цветом · ${e.name}", "Saving with colour · ${e.name}"), fontSize = 14.sp)
                        Text("${e.percent} %", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(vm::cancelExport) { Text(tr("Стоп", "Stop")) }
                }
                LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        return
    }
    if (progress == null && (vm.showsPhone || (selection.isNotEmpty() && selection.all { it.isOnPhone }))) {
        // The phone list: a selection can be shared.
        if (selection.isEmpty()) return
        val context = LocalContext.current
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("${selection.size} шт.", "${selection.size} items") + " · ${formatSize(selection.sumOf { it.size ?: 0L })}", Modifier.weight(1f))
                PasteColour(vm, selection)
                // Added files: off the list (they stay on the phone).
                if (selection.all { it.isAdded }) {
                    IconButton({ vm.removeAdded(selection) }) {
                        Icon(Icons.Filled.PlaylistRemove, tr("Убрать из списка", "Take off the list"), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Button({ share(context, selection); vm.clearSelection() }) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Поделиться", "Share"))
                }
            }
        }
        return
    }
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
                    PasteColour(vm, selection)
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
