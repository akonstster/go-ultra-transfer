package app.goultra.transfer.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.goultra.transfer.CameraService
import app.goultra.transfer.camera.CamSettings
import app.goultra.transfer.camera.CameraConf
import app.goultra.transfer.camera.CameraInfo
import app.goultra.transfer.camera.CameraFile
import app.goultra.transfer.camera.CameraHttpDataSource
import app.goultra.transfer.camera.Highlight
import app.goultra.transfer.camera.Highlights
import app.goultra.transfer.camera.ClipMaker
import app.goultra.transfer.camera.KeyFrames
import app.goultra.transfer.camera.FrameReader
import app.goultra.transfer.camera.Mp4Index
import app.goultra.transfer.camera.LocalTrailer
import app.goultra.transfer.camera.RemoteFrames
import app.goultra.transfer.camera.CameraTcp
import app.goultra.transfer.camera.CameraSession
import app.goultra.transfer.camera.CaptureStatus
import app.goultra.transfer.camera.PbWriter
import app.goultra.transfer.camera.Proto
import app.goultra.transfer.camera.SettingCodes
import app.goultra.transfer.camera.Downloader
import app.goultra.transfer.camera.LiveDecoder
import app.goultra.transfer.camera.LiveCover
import app.goultra.transfer.camera.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ConnState {
    data object Idle : ConnState
    data class Working(val step: String) : ConnState
    data object Connected : ConnState
    /** BLE control only (recording works, no files / live view): after «разбудить и записать». */
    data object Remote : ConnState
    data class Failed(val message: String) : ConnState
}

enum class Filter { ALL, PHOTO, VIDEO }

enum class Tab { FILES, CAMERA }

data class DownloadProgress(
    val name: String,
    val index: Int,
    val count: Int,
    val done: Long,
    val total: Long,
    val bytesPerSec: Long,
)

/**
 * App-wide camera state: lives as long as the process (kept alive by [CameraService] while connected),
 * so downloads continue when the screen is off or the app is in the background.
 */
class CameraController(private val app: Application) {
    companion object {
        private const val IDLE_VISIBLE_MS = 5 * 60_000L
        private const val BACKGROUND_GRACE_MS = 3 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("camera", 0)

    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", false))
        private set
    var setup by mutableStateOf<ConnState>(ConnState.Idle)
        private set
    var conn by mutableStateOf<ConnState>(ConnState.Idle)
        private set
    var files by mutableStateOf<List<CameraFile>>(emptyList())
        private set
    var filter by mutableStateOf(Filter.ALL)

    /** Downloaded files in the phone's gallery: the Files tab shows them while the camera isn't connected. */
    var phoneFiles by mutableStateOf<List<CameraFile>>(emptyList())
        private set

    /** The Files tab lists the phone's copies (no camera), not the camera's card. */
    val showsPhone: Boolean get() = conn != ConnState.Connected
    var downloaded by mutableStateOf<Set<String>>(emptySet())
        private set
    var download by mutableStateOf<DownloadProgress?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    /** Result of the last download batch. */
    var lastResult: String = ""
        private set

    /** Result of a finished batch not yet shown as a notification (taken by [CameraService]). */
    private var downloadResult: String? = null

    fun takeDownloadResult(): String? = downloadResult.also { downloadResult = null }

    /** Selected file names (map used as a snapshot-aware set). */
    val selected = mutableStateMapOf<String, Unit>()

    private val logLines = ArrayDeque<String>()
    private val logTime = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    /** Android/data/app.goultra.transfer/files/log.txt — readable with adb even where logcat is filtered (Huawei). */
    private val logFile = app.getExternalFilesDir(null)?.let { File(it, "log.txt") }
    private val session = CameraSession(app, ::log)

    /** The log file is written here, never on the caller's thread (main, BLE binder…). */
    private val logWriter = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Logcat + an in-memory tail (shared via «Отправить журнал») + a log file. */
    fun log(msg: String) {
        Log.i("GUT", msg)
        val line = "${synchronized(logTime) { logTime.format(java.util.Date()) }} $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 1000) logLines.removeFirst()
        }
        logFile?.let { f ->
            logWriter.execute {
                runCatching {
                    if (f.length() > 2_000_000) f.renameTo(File(f.path + ".1"))
                    f.appendText(line + "\n")
                }
            }
        }
    }

    fun logText(): String = buildString {
        appendLine("GO Ultra Transfer ${app.packageManager.getPackageInfo(app.packageName, 0).versionName}")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        synchronized(logLines) { logLines.forEach { appendLine(it) } }
    }

    private val memCache = LruCache<String, ImageBitmap>(300)
    private val thumbPermits = Semaphore(3)
    private val thumbDir = File(app.cacheDir, "thumbs").apply { mkdirs() }
    private var downloadJob: Job? = null
    /** The running connect() or runSetup() chain; both use the same BLE link, so only one at a time. */
    private var linkJob: Job? = null
    private var autoConnected = false
    /** The background timer dropped a live connection: restore it when the app comes back. */
    private var resumeOnForeground = false
    private var resumeControlOnly = false

    // Idle timeout: don't keep the camera awake and the phone on its Wi-Fi for nothing.
    private var lastActivity = SystemClock.elapsedRealtime()
    private var backgroundSince = 0L
    private var uiShown by mutableStateOf(false)

    val inBackground: Boolean get() = !uiShown

    val isLinked: Boolean get() = conn == ConnState.Connected || conn == ConnState.Remote

    var tab by mutableStateOf(Tab.FILES)

    /** The camera screen (live view, recording) is on screen: never disconnect on idle then. */
    private val keepConnected: Boolean get() = tab == Tab.CAMERA && uiShown

    /** Recording state, polled over BLE once a second while the camera tab is visible. */
    var capture by mutableStateOf<CaptureStatus?>(null)
        private set

    /**
     * Phone-clock moment (elapsedRealtime) the running recording started: the time on screen ticks from it every
     * second, the camera's whole seconds (polled every 1–2 s) only correct it.
     */
    private var recordStartedAt by mutableStateOf<Long?>(null)

    /** Wall-clock start of the running recording (the notification's chronometer), stable between polls. */
    fun recordStartedWallClock(): Long {
        val started = recordStartedAt ?: return System.currentTimeMillis() - (capture?.seconds ?: 0) * 1000L
        return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - started)
    }

    /** Seconds recorded by [now] (elapsedRealtime). */
    fun recordSeconds(now: Long): Int {
        val at = if (paused) pausedAt else now // paused: the time stands
        val s = recordStartedAt?.let { ((at - it) / 1000).toInt().coerceAtLeast(0) } ?: capture?.seconds ?: 0
        if (capture?.recording != true) return s
        shownSeconds = maxOf(shownSeconds, s)
        return shownSeconds
    }

    /**
     * A TimeShift recording: true = normal speed, false = Auto (sped up), null = no TimeShift recording. Auto picks
     * its speed itself (5× … 15×+ by what happens in the frame) and doesn't report it, so the video length is
     * known only after the stop, from the file list ([reportTimeShift]).
     */
    var timeShiftNormal by mutableStateOf<Boolean?>(null)
        private set
    var speedBusy by mutableStateOf(false)
        private set
    /** A poll sent before a speed switch may answer with the old speed: polls this soon after it are ignored. */
    private var speedSwitchedAt = 0L
    /** A TimeShift recording just ended: the next file list tells its video length. */
    private var reportTimeShift = false

    /** When Wi-Fi last came up, and the last silent reconnect after an early drop (once per 3 min, no loops). */
    private var wifiUpAt = 0L
    private var earlyRetryAt = 0L

    /**
     * Economy recording: live view stopped and the camera's Wi-Fi left; the camera keeps its access point off until
     * the recording ends, Bluetooth alone follows and stops it. After the stop Wi-Fi is joined again.
     */
    var ecoRecording by mutableStateOf(false)
        private set
    var recordBusy by mutableStateOf(false)
        private set
    var liveError by mutableStateOf<String?>(null)
        private set
    /** Width / height of the live picture (16:9 normally, 4:3 in FreeFrame, 2.35:1…). */
    var liveAspect by mutableStateOf(16f / 9f)
        private set
    private var liveDecoder: LiveDecoder? = null

    /** Shooting mode, settings and battery levels read from the camera (camera tab). */
    var settings by mutableStateOf<CamSettings?>(null)
        private set
    /** The camera's capability description (/CONF), cached on disk: allowed values per mode. */
    var conf by mutableStateOf<CameraConf?>(null)
        private set
    var settingBusy by mutableStateOf<String?>(null)
        private set

    private var powerReadAt = 0L
    private val confFile = File(app.filesDir, "camera_conf.json")

    /** Bluetooth / Wi-Fi / VPN on the phone; the UI asks to fix what blocks the connection. */
    var radios by mutableStateOf(PhoneRadios.check(app))
        private set
    /** Connection the user asked for while Bluetooth or Wi-Fi was off: runs as soon as they are on. */
    private var afterRadios by mutableStateOf<(() -> Unit)?>(null)

    val isDownloading: Boolean get() = downloadJob?.isActive == true

    init {
        if (!prefs.getBoolean("migrated_to_dcim", false)) {
            scope.launch(Dispatchers.IO) {
                runCatching { Downloader.migrateLegacy(app) }
                    .onSuccess { log("migrated legacy gallery items: $it") }
                    .onFailure { log("legacy migration failed: ${it.message}") }
                prefs.edit().putBoolean("migrated_to_dcim", true).apply()
            }
        }
        scope.launch {
            while (true) {
                delay(10_000)
                disconnectIfIdle()
            }
        }
        scope.launch {
            // The batteries change slowly and the camera doesn't always say so: re-read once a minute while they are
            // on screen (the camera tab).
            while (true) {
                delay(60_000)
                if (tab == Tab.CAMERA && uiShown && isLinked && settingBusy == null) refreshSettings()
            }
        }
        scope.launch {
            // Connections are made from the visible app, so the foreground service may start here.
            snapshotFlow { isLinked }.collect { if (it) CameraService.start(app) }
        }
        scope.launch(Dispatchers.IO) {
            if (confFile.exists()) runCatching { CameraConf(confFile.readText()) }.getOrNull()?.let { conf = it }
        }
        scope.launch {
            // Without the camera the Files tab lists the phone's copies: re-read on start, after a disconnect and
            // when the app comes back (files may have been deleted in the gallery meanwhile).
            // A camera file left open in the viewer can't play any more: close it.
            snapshotFlow { showsPhone && uiShown }.collect {
                if (!it) return@collect
                if (viewing?.isOnPhone == false) viewing = null
                loadPhoneFiles()
            }
        }
        scope.launch {
            // Camera tab opened (or the link came up while it is open): read the current settings.
            snapshotFlow { Triple(tab, conn, uiShown) }.collect { (t, c, shown) ->
                if (t == Tab.CAMERA && shown && (c == ConnState.Connected || c == ConnState.Remote)) refreshSettings()
            }
        }
        scope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                tick++
                val linked = conn == ConnState.Connected || conn == ConnState.Remote
                if (linked && !recordBusy && !session.hasControl) onControlLost()
                if (!linked || recordBusy || !session.hasControl) continue
                // A running recording is followed on any tab, and in the background every 5 s: the notification
                // shows it and its end restarts the release timer.
                val recording = capture?.recording == true
                if (if (uiShown) tab == Tab.CAMERA || recording else recording && tick % 5 == 0) followCapture()
                // Battery / charging and settings changed on the camera itself: re-read every 30 s.
                if (uiShown && tab == Tab.CAMERA && tick % 30 == 0 && settingBusy == null) {
                    runCatching { session.readSettings() }.getOrNull()?.let(::showSettings)
                }
            }
        }
        PhoneRadios.watch(app) {
            scope.launch {
                radios = PhoneRadios.check(app)
                afterRadios?.let { retry ->
                    afterRadios = null
                    retry() // checks the radios again and waits further if something is still off
                }
            }
        }
        session.onLiveParams = { scope.launch { onLiveParamsChanged() } }
        // The mode changed on the camera (its menu, or by itself: Video → Pure Video in the dark): settings, the
        // photo-while-recording button etc. follow it. A change from the app itself is re-read anyway: harmless.
        session.onModeChanged = { scope.launch { if (settingBusy == null) refreshSettings() } }
        // Paused / resumed (our button or the camera's): the time on screen stops and goes on with the camera's.
        session.onCapturePhase = { phase -> scope.launch { if (capture?.recording == true) applyPause(phase == 1) } }
        // Pod put on / taken off, charging started… : the batteries on screen follow (a burst of these is read once).
        session.onPowerChanged = {
            scope.launch {
                val now = SystemClock.elapsedRealtime()
                if (settingBusy == null && now - powerReadAt > 3_000) {
                    powerReadAt = now
                    delay(500)
                    refreshSettings()
                }
            }
        }
        session.onLost = {
            scope.launch {
                if (conn == ConnState.Connected) {
                    pauseLive() // the screen's surface is kept: the stream comes back after a reconnect
                    // Drop the dead TCP link and the camera network request (else keepalives / 10 s waits go on).
                    session.closeWifi()
                    val now = SystemClock.elapsedRealtime()
                    if (now - wifiUpAt < 60_000 && now - earlyRetryAt > 180_000) {
                        // A woken camera restarts its access point once, ~30 s after the wake: the phone was on the
                        // first one. Reconnecting at once (~2 s) instead of showing an error.
                        log("Wi-Fi камеры пропал сразу после подключения (перезапуск точки доступа): подключаюсь заново")
                        earlyRetryAt = now
                        conn = if (session.hasControl) ConnState.Remote else ConnState.Idle
                        connect()
                        return@launch
                    }
                    // Wi-Fi gone but Bluetooth still there: recording control keeps working.
                    conn = if (session.hasControl) ConnState.Remote else ConnState.Failed(tr("Связь с камерой потеряна", "Lost the link to the camera"))
                }
            }
        }
    }

    /** Clip of each live photo (LIV_….jpg → LIV_….mp4): shown as one LIVE tile, downloaded together. */
    private val liveClips: Map<String, CameraFile>
        get() {
            val clips = files.filter { it.isLive && it.kind == MediaKind.VIDEO }.associateBy { it.stem }
            return files.filter { it.isLivePhoto }.mapNotNull { p -> clips[p.stem]?.let { p.name to it } }.toMap()
        }

    fun liveClipOf(file: CameraFile): CameraFile? = if (file.isLivePhoto) liveClips[file.name] else null

    /** A cover change (frame → photo, or back) is running. */
    var coverBusy by mutableStateOf<String?>(null)
        private set

    /** Shots whose cover is a frame of the clip instead of the camera's photo (to offer «back to the photo»). */
    private var replacedCovers by mutableStateOf(prefs.getStringSet("covers_replaced", emptySet())!!.toSet())

    fun coverReplaced(file: CameraFile): Boolean = file.stem in replacedCovers

    /** Bumped after every cover change: the viewer reloads the photo. */
    var coverVersion by mutableStateOf(0)
        private set

    private fun markCover(file: CameraFile, replaced: Boolean) {
        replacedCovers = if (replaced) replacedCovers + file.stem else replacedCovers - file.stem
        prefs.edit().putStringSet("covers_replaced", replacedCovers).apply()
        coverVersion++
    }

    /**
     * The live photo's cover becomes the clip's frame at [us]: in the gallery file the photo part is replaced by
     * that frame (EXIF kept, clip untouched). Not in the gallery yet, as a plain photo, or with the light copy of
     * the clip: it is first downloaded as a live photo with the original clip (the frame then is 3840×2880).
     */
    fun setLiveCover(file: CameraFile, us: Long) = changeCover(file, tr("Меняю обложку…", "Changing the cover…")) {
        val v = liveFileWithOriginalClip(file) ?: return@changeCover tr("Не удалось скачать живое фото", "Couldn't download the live photo")
        val frame = withContext(Dispatchers.IO) { LiveCover.frame(app, v, us) }
            ?: return@changeCover tr("Не удалось взять кадр из видео", "Couldn't take the frame from the clip")
        withContext(Dispatchers.IO) {
            val photo = LiveCover.jpeg(frame, LiveCover.photoPart(app, v))
            LiveCover.replacePhoto(app, v, photo, app.cacheDir)
            showThumb(file, frame)
            frame.recycle()
        }
        markCover(file, true)
        tr("Обложка заменена кадром из видео", "The cover is now the frame from the clip")
    }

    /** Back to the camera's own photo as the cover (downloaded again from the camera). */
    fun restoreLiveCover(file: CameraFile) = changeCover(file, tr("Возвращаю исходное фото…", "Restoring the original photo…")) {
        val v = withContext(Dispatchers.IO) { LiveCover.video(app, file.name) }
            ?: return@changeCover tr("Живого фото нет в галерее", "The live photo isn't in the gallery")
        val original = session.fetchFile(file.uri)
        withContext(Dispatchers.IO) { LiveCover.replacePhoto(app, v, original, app.cacheDir) }
        memCache.remove(file.name)
        File(thumbDir, file.name + ".jpg").delete()
        markCover(file, false)
        tr("Обложка — снова исходное фото", "The cover is the original photo again")
    }

    private fun changeCover(file: CameraFile, busy: String, work: suspend () -> String) {
        if (coverBusy != null || isDownloading || conn != ConnState.Connected) return
        touch()
        coverBusy = busy
        scope.launch {
            message = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("cover ${file.name}: ${e.message}")
                tr("Ошибка: ", "Error: ") + e.message
            } finally {
                coverBusy = null
            }
        }
    }

    /** The gallery live photo of [file], downloaded (again) if missing, plain, or holding only the light clip copy. */
    private suspend fun liveFileWithOriginalClip(file: CameraFile): LiveCover.Video? {
        val clip = liveClipOf(file) ?: return null
        val have = withContext(Dispatchers.IO) { LiveCover.video(app, file.name) }
        if (have != null && withContext(Dispatchers.IO) { LiveCover.width(app, have) } >= 2000) return have
        coverBusy = tr("Скачиваю живое фото…", "Downloading the live photo…")
        session.download(file, overwrite = true, liveVideo = clip) { done, total ->
            scope.launch { coverBusy = tr("Скачиваю живое фото… ", "Downloading the live photo… ") + (if (total > 0) "${done * 100 / total} %" else "") }
        }
        downloaded = downloaded + file.name
        coverBusy = tr("Меняю обложку…", "Changing the cover…")
        return withContext(Dispatchers.IO) { LiveCover.video(app, file.name) }
    }

    /** The list tile shows the new cover (the cached camera thumbnail would show the old photo). */
    private fun showThumb(file: CameraFile, frame: Bitmap) {
        val w = 480
        val small = Bitmap.createScaledBitmap(frame, w, (w.toFloat() * frame.height / frame.width).toInt(), true)
        saveAtomically(File(thumbDir, file.name + ".jpg"), small)
        memCache.put(file.name, small.asImageBitmap())
    }

    /** The file list without live-photo clips (they belong to their photo's tile); the phone's copies without the camera. */
    val listedFiles: List<CameraFile>
        get() {
            if (showsPhone) return phoneFiles
            val paired = liveClips.values.map { it.name }.toSet()
            return files.filter { it.name !in paired }
        }

    val visibleFiles: List<CameraFile>
        get() = when (filter) {
            Filter.ALL -> listedFiles
            Filter.PHOTO -> listedFiles.filter { it.kind == MediaKind.PHOTO }
            Filter.VIDEO -> listedFiles.filter { it.kind == MediaKind.VIDEO }
        }

    val selectedFiles: List<CameraFile> get() = files.filter { it.name in selected }

    // ---------- camera info (menu ⋮ → «Камера») ----------

    /** Model / firmware / serial, read when the «Camera» window opens; kept after a disconnect (they don't change). */
    var cameraInfo by mutableStateOf<CameraInfo?>(null)
        private set

    /** This phone's camera name («AB12CD»), remembered at setup or on the first connection. */
    val cameraName: String? get() = session.cameraName

    fun loadCameraInfo() {
        if (!session.hasControl) return
        refreshSettings() // battery, Pod, card
        scope.launch { runCatching { session.cameraInfo() }.getOrNull()?.let { cameraInfo = it } }
    }

    // ---------- first-run setup ----------

    /** Several cameras found in setup: their names, shown for the user to pick ([chooseCamera]). */
    var cameraChoices by mutableStateOf<List<String>?>(null)
        private set
    private var cameraChoice: CompletableDeferred<String?>? = null

    /** The user picked a camera (null = cancel). */
    fun chooseCamera(name: String?) {
        cameraChoice?.complete(name)
    }

    fun runSetup() {
        if (setup is ConnState.Working) return
        if (!radiosReady(needWifi = false, retry = ::runSetup)) return
        disconnect()
        linkJob = scope.launch {
            setup = ConnState.Working(tr("Подготовка…", "Preparing…"))
            val error = try {
                session.setup(
                    step = { step -> scope.launch { setup = ConnState.Working(step) } },
                    choose = { names ->
                        val choice = CompletableDeferred<String?>()
                        cameraChoice = choice
                        cameraChoices = names
                        try {
                            choice.await()
                        } finally {
                            cameraChoices = null
                            cameraChoice = null
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("setup failed: ${e.message}")
                tr("Ошибка: ", "Error: ") + e.message
            }
            setup = if (error == null) ConnState.Connected else ConnState.Failed(error)
        }
    }

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarded", true).apply()
        onboarded = true
        setup = ConnState.Idle
        autoConnected = true
        connect()
    }

    fun restartOnboarding() {
        disconnect()
        prefs.edit().putBoolean("onboarded", false).apply()
        onboarded = false
        setup = ConnState.Idle
    }

    // ---------- connection ----------

    /** Connects once per process start, when the app is opened. */
    fun autoConnect() {
        if (autoConnected) return
        autoConnected = true
        connect()
    }

    /** Full connection (files, live view). From [ConnState.Remote] it adds Wi-Fi to the existing BLE link. */
    fun connect() {
        if (conn is ConnState.Working || conn == ConnState.Connected || setup is ConnState.Working) return
        if (ecoRecording && capture?.recording == true) {
            message = tr("Wi-Fi камеры вернётся после остановки записи", "The camera's Wi-Fi comes back after the recording stops")
            return
        }
        if (!radiosReady(needWifi = true, retry = ::connect)) return
        linkJob = scope.launch {
            conn = ConnState.Working(tr("Подключение…", "Connecting…"))
            val error = try {
                session.connect { step -> scope.launch { conn = ConnState.Working(step) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("connect failed: ${e.message}")
                tr("Ошибка: ", "Error: ") + e.message
            }
            if (error != null) {
                // Only Wi-Fi failed during a recording: keep Bluetooth, the recording stays under control (the camera
                // keeps its access point off mid-recording). Otherwise the error stays on screen (VPN hint…).
                val recording = session.hasControl && runCatching { session.captureStatus() }.getOrNull()?.recording == true
                if (recording) {
                    session.closeWifi()
                    conn = ConnState.Remote
                    message = error
                    followCapture()
                } else {
                    session.disconnect()
                    conn = ConnState.Failed(error)
                }
                return@launch
            }
            conn = ConnState.Connected
            wifiUpAt = SystemClock.elapsedRealtime()
            touch()
            resumeLive()
            loadFiles()
            loadConf()
        }
    }

    fun refresh() {
        touch()
        // On the camera tab: re-read settings (they may have been changed on the camera itself).
        if (tab == Tab.CAMERA) {
            refreshSettings()
            debugSnapshot()
        } else if (conn == ConnState.Connected) {
            scope.launch { loadFiles() }
        } else {
            loadPhoneFiles()
        }
    }

    private fun loadPhoneFiles() {
        scope.launch {
            phoneFiles = withContext(Dispatchers.IO) {
                runCatching { Downloader.phoneFiles(app) }.onFailure { log("файлы на телефоне: ${it.message}") }.getOrNull()
            } ?: return@launch
        }
    }

    /** Settings whose allowed values depend on others (CONF depend_on) and that the app can rewrite. */
    private val DEPENDENT_PARAMS = listOf(
        "flowstate_level", "fov_type", "horizon_lock", "hdr_switch", "i_log_switch", "filter_mode",
        "damage_zoom", "undamage_zoom", "metering_mode", "sharpness_level",
        "self_portrait_switch_2", // portrait mode only up to 30 fps — switched off before 4K60 & co.
    )

    private val debuggable = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    /** Previous snapshot per set of function modes read (a wide scan is compared only with a wide one). */
    private val lastSnapshots = mutableMapOf<List<Int>, Map<String, String>>()
    private var snapshotJob: Job? = null

    val isDebugBuild: Boolean get() = debuggable

    /** Debug: raw GET_CURRENT_CAPTURE_STATUS (15) reply every second for [seconds] — to map recording fields. */
    fun debugCaptureStatus(seconds: Int) {
        if (!debuggable || !session.hasControl) return
        scope.launch {
            var last = ""
            repeat(seconds) {
                val raw = session.rawCommand(Proto.CMD_GET_CURRENT_CAPTURE_STATUS)
                if (raw != last) log("DEBUG статус записи: $raw")
                last = raw
                delay(1_000)
            }
            log("DEBUG статус записи: конец")
        }
    }

    /** Debug: one allowed command ([CameraSession.rawCommand]) with a hex body, reply logged. */
    fun debugCommand(code: Int, bodyHex: String) {
        if (!debuggable || !session.hasControl) return
        val body = bodyHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        scope.launch {
            val reply = runCatching { session.rawCommand(code, body) }.getOrElse { "ошибка: ${it.message}" }
            log("DEBUG команда $code [$bodyHex] → $reply")
        }
    }

    /** Debug: copies one camera file (read-only HTTP GET) into the app's files folder, for analysis on the PC. */
    fun debugFetch(uri: String) {
        if (!debuggable || conn != ConnState.Connected || !uri.startsWith("/DCIM/")) return
        scope.launch {
            runCatching {
                val bytes = session.fetchFile(uri)
                withContext(Dispatchers.IO) { File(app.getExternalFilesDir(null), uri.substringAfterLast('/')).writeBytes(bytes) }
                log("DEBUG скопирован $uri: ${bytes.size} Б")
            }.onFailure { log("DEBUG копирование $uri: ${it.message}") }
        }
    }

    /** Debug: the newest video's metadata, field by field (where are the highlight marks?). */
    fun debugVideoInfo(nameContains: String? = null) {
        if (!debuggable || conn != ConnState.Connected) return
        scope.launch { log("DEBUG видео: " + runCatching { session.debugNewestVideoInfo(nameContains) }.getOrElse { "ошибка: ${it.message}" }) }
    }

    /** Debug: unfiltered BLE scan into the log (works without a connection). */
    fun debugScan() {
        if (debuggable) scope.launch { runCatching { session.scanAll() }.onFailure { log("скан: ${it.message}") } }
    }

    /** Debug: GET_OPTIONS for exactly [types], one request each, raw reply logged. */
    fun debugOptions(types: List<Int>) {
        if (!debuggable || !session.hasControl) return
        scope.launch { for (t in types) log("DEBUG opt $t → ${session.rawOptions(listOf(t))}") }
    }

    var selfTestRunning by mutableStateOf(false)
        private set

    /** What the self-test goes through (CONF names), also the order the restore writes back (resolution first). */
    private val SELF_TEST_PARAMS = listOf(
        "record_resolution", "photo_resolution", "fov_type", "flowstate_level", "horizon_lock", "dark_eis_enable",
        "hdr_switch", "i_log_switch", "filter_mode", "sharpness_level", "self_portrait_switch_2",
        // EV before the exposure mode: it does nothing in full manual, which the exposure loop ends with.
        "exposure_bias", "exposure_program", "exposure_iso", "exposure_shutter_speed", "white_balance", "metering_mode",
        "raw_capture_type", "lapse_time", "burst_capture_params", "cache_capture_enable", "record_duration",
        "export_type", "photography_self_timer", "live_photo_switch", "damage_zoom", "undamage_zoom",
    )

    /**
     * Debug builds: every mode, every value CONF allows — written exactly like the settings screen does it, read back,
     * mismatches logged («САМОПРОВЕРКА ОШИБКА»). The user's values and mode are restored at the end.
     */
    fun selfTest() {
        val c = conf
        if (!debuggable || selfTestRunning || c == null || !session.hasControl || settingBusy != null) return
        selfTestRunning = true
        settingBusy = "selftest"
        scope.launch {
            val problems = mutableListOf<String>()
            var checks = 0
            val startMode = settings?.mode ?: runCatching { session.readSettings() }.getOrNull()?.mode
            try {
                for ((mode, m) in SettingCodes.modes) {
                    message = "Самопроверка: ${mode.lowercase()}…"
                    if (!switchMode(m)) {
                        problems += "$mode: режим не включился"
                        continue
                    }
                    val original = session.readSettings() ?: continue
                    if (original.mode != mode) {
                        problems += "$mode: после переключения камера в ${original.mode}"
                        continue
                    }
                    log("самопроверка $mode: исходные ${original.values}")
                    for (param in SELF_TEST_PARAMS) {
                        if (!c.supports(mode, param) || !shown(mode, param)) continue
                        var cur = session.readSettings() ?: break
                        val options = optionsFor(c, mode, param, cur.values).filter { offered(param, it) && encodable(param, it) }
                        for (value in options) {
                            touch() // the idle timer must not drop the link mid-test
                            if (locked(param, cur.values)) break
                            if (sameValue(param, currentValue(param, cur.values), value)) continue
                            // An earlier value of the same setting may have changed what is allowed now.
                            if (value !in optionsFor(c, mode, param, cur.values)) continue
                            checks++
                            val ok = writeSetting(param, value, cur)
                            delay(250)
                            cur = session.readSettings() ?: break
                            var read = currentValue(param, cur.values)
                            if (ok == true && !sameValue(param, read, value)) {
                                delay(1_000) // slow to apply (portrait…) is not the same as ignored
                                cur = session.readSettings() ?: break
                                read = currentValue(param, cur.values)
                                if (sameValue(param, read, value)) log("самопроверка: $mode $param=$value применилось с задержкой")
                            }
                            if (ok != true || !sameValue(param, read, value)) {
                                problems += "$mode $param=$value: ответ $ok, прочитано $read"
                                log("САМОПРОВЕРКА ОШИБКА: ${problems.last()}")
                            }
                        }
                    }
                    restoreSettings(mode, original, problems)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "сбой: ${e.message}"
                log("самопроверка: ${e.message}")
            } finally {
                runCatching {
                    startMode?.let { SettingCodes.modes[it] }?.let { switchMode(it) }
                    session.readSettings()?.let(::showSettings)
                }
                log("самопроверка: проверок $checks, ошибок ${problems.size}" + problems.joinToString("") { "\n  $it" })
                message = "Самопроверка: проверок $checks, ошибок ${problems.size} (подробности в журнале)"
                settingBusy = null
                selfTestRunning = false
            }
        }
    }

    private suspend fun switchMode(m: SettingCodes.Mode): Boolean {
        val ok = if (m.photoSub != null) {
            session.writeOption(SettingCodes.OPT_PHOTO_SUB_MODE, m.photoSub.toLong())
        } else {
            session.writeOption(SettingCodes.OPT_VIDEO_SUB_MODE, m.videoSub!!.toLong())
        }
        delay(600)
        return ok
    }

    /** Writes back what the self-test changed in [mode] (two passes: some values need others restored first). */
    private suspend fun restoreSettings(mode: String, original: CamSettings, problems: MutableList<String>) {
        repeat(2) {
            for (param in SELF_TEST_PARAMS) {
                // Locked values (e.g. the shutter the camera remembers in auto) must not be written back.
                if (locked(param, original.values)) continue
                val want = currentValue(param, original.values) ?: continue
                val cur = session.readSettings() ?: return
                if (sameValue(param, currentValue(param, cur.values), want)) continue
                writeSetting(param, want, cur)
                delay(250)
            }
        }
        val after = session.readSettings() ?: return
        val left = SELF_TEST_PARAMS.filter { p ->
            !locked(p, original.values) &&
                currentValue(p, original.values)?.let { !sameValue(p, currentValue(p, after.values), it) } == true
        }
        if (left.isNotEmpty()) {
            problems += "$mode: не восстановлено " +
                left.joinToString { "$it=${currentValue(it, after.values)} (было ${currentValue(it, original.values)})" }
        }
    }

    /** Debug builds, mapping settings: logs every camera field that changed since the previous ⟳ (read-only). */
    fun debugSnapshot(functionModes: List<Int>? = null) {
        if (!debuggable || snapshotJob?.isActive == true || !session.hasControl) return
        touch()
        snapshotJob = scope.launch {
            message = "Снимок настроек…"
            // Video 7 / Pure 27 (verified); photo candidates by the FunctionMode enum: single 6, burst 5, interval 3, starlapse 18.
            // Real modes (others echo VIDEO_NORMAL): timelapse 2, interval 3, burst 5, photo 6, timeshift 12,
            // FreeFrame 16, loop 17, starlapse 18, slow motion 21, Pure 27; the narrow scan reads more field numbers.
            val fms = functionModes ?: listOf(7, 27, 6, 5, 3, 18, 2, 12, 16, 17, 21)
            val snap = runCatching { session.snapshot(fms, if (functionModes == null) 300 else 100) }.onFailure { log("снимок: ${it.message}") }.getOrNull()
                ?: return@launch
            // Static noise (calibration strings, card space, temperature) is left out of the diff.
            val noisy = setOf("o.9", "o.19", "o.20.2", "o.158.2", "o.77") + snap.keys.filter { it.startsWith("p") && it.endsWith(".41") }
            val prev = lastSnapshots[fms]?.filterKeys { it !in noisy }
            if (prev == null) {
                log("снимок (${snap.size}): $snap")
            } else {
                val diff = (prev.keys + snap.keys).filter { it !in noisy && prev[it] != snap[it] }.sorted()
                    .joinToString("; ") { "$it: ${prev[it]} → ${snap[it]}" }
                log("снимок, изменилось: ${diff.ifEmpty { "ничего" }}")
            }
            lastSnapshots[fms] = snap
            message = "Снимок готов"
        }
    }

    private suspend fun loadFiles() {
        val list = try {
            session.files()?.also { refreshDownloaded() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("file list failed: ${e.message}")
            null
        }
        if (list == null) {
            message = tr("Не удалось получить список файлов", "Couldn't get the file list")
            return
        }
        files = list
        if (reportTimeShift) {
            reportTimeShift = false
            // The new TimeShift file: its real length (sped-up parts are as short as Auto chose to make them).
            list.filter { it.kind == MediaKind.VIDEO }.maxByOrNull { it.name }?.durationSec?.let {
                message = tr("TimeShift: видео ", "TimeShift: video ") + formatDuration(it)
            }
        }
        selected.keys.retainAll(list.map { it.name }.toSet())
        fillMissingSizes(list)
    }

    /** The listing has no size for some files (RAW .dng): ask the HTTP server, one by one, and patch the list. */
    private fun fillMissingSizes(list: List<CameraFile>) {
        val missing = list.filter { (it.size ?: 0L) <= 0L }
        if (missing.isEmpty()) return
        scope.launch {
            val sizes = mutableMapOf<String, Long>()
            for (f in missing) {
                if (conn != ConnState.Connected) return@launch
                // The Wi-Fi link may vanish between the check and the request (link getter throws then).
                runCatching { session.fileSize(f) }.getOrNull()?.let { sizes[f.uri] = it }
            }
            if (sizes.isNotEmpty()) files = files.map { f -> sizes[f.uri]?.let { f.copy(size = it) } ?: f }
            log("размеры по HEAD: ${sizes.size} из ${missing.size}")
        }
    }

    private suspend fun refreshDownloaded() {
        downloaded = withContext(Dispatchers.IO) { Downloader.downloadedNames(app) }
    }

    /**
     * Bluetooth (and Wi-Fi for [needWifi]) must be on. Otherwise the UI shows what to switch on
     * and [retry] runs once it is — no need to press «Подключиться» again.
     */
    private fun radiosReady(needWifi: Boolean, retry: () -> Unit): Boolean {
        radios = PhoneRadios.check(app)
        val ok = radios.bluetooth && (radios.wifi || !needWifi)
        afterRadios = if (ok) null else retry
        if (!ok) log("ждём: Bluetooth ${radios.bluetooth}, Wi-Fi ${radios.wifi}")
        return ok
    }

    /** The UI shows the Bluetooth / Wi-Fi prompt only while a connection waits for it. */
    val waitingForRadios: Boolean get() = afterRadios != null && (!radios.bluetooth || !radios.wifi)

    fun disconnect() {
        if (isLinked) log("отключение от камеры (Wi-Fi и Bluetooth)")
        ecoRecording = false
        afterRadios = null
        linkJob?.cancel()
        linkJob = null
        downloadJob?.cancel()
        detachLive()
        session.disconnect()
        files = emptyList()
        selected.clear()
        capture = null
        recordStartedAt = null
        timeShiftNormal = null
        reportTimeShift = false
        settings = null // battery, card, mode: re-read on the next connection, never shown stale
        conn = ConnState.Idle
        if (setup is ConnState.Working) setup = ConnState.Idle
    }

    // ---------- recording ----------

    /** Wakes the camera over BLE if needed (no Wi-Fi) and starts recording; ~4 s from a powered-off camera. */
    fun startRecording() = recordAction(start = true)

    fun stopRecording() = recordAction(start = false)

    fun toggleRecording() = if (capture?.recording == true) stopRecording() else startRecording()

    /** Home-screen shortcut «Начать запись»: no auto-connect over Wi-Fi, straight to recording. */
    fun onRecordShortcut() {
        autoConnected = true
        tab = Tab.CAMERA
        startRecording()
    }

    /** New capture status; the end of a recording counts as activity, so the release timer starts from there. */
    private fun updateCapture(status: CaptureStatus) {
        if (capture?.recording == true && !status.recording) {
            touch()
            if (ecoRecording) {
                // The camera brings its access point back after the stop (~20 s): join it again.
                ecoRecording = false
                if (conn == ConnState.Remote) scope.launch { connect() }
            }
            // The new file's length needs the file list (Wi-Fi); stopped on the camera, the list is loaded here
            // (a stop from the app loads it itself).
            if (capture?.state == CaptureStatus.TIMESHIFT && conn == ConnState.Connected) {
                reportTimeShift = true
                if (!recordBusy) scope.launch { loadFiles() }
            }
        }
        capture = status
        val now = SystemClock.elapsedRealtime()
        if (!status.recording) {
            recordStartedAt = null
            shownSeconds = 0
            paused = false
        } else if (recordStartedAt == null) {
            // A recording seen for the first time (started now, or joined — maybe paused): the camera's time.
            shownSeconds = 0
            recordStartedAt = now - status.seconds * 1000L - 500
            paused = status.paused
            if (paused) pausedAt = now
        } else {
            applyPause(status.paused)
            if (!paused) {
                // The camera's seconds are whole (true time is s…s+1): re-anchor only when the phone clock drifts out.
                val shown = (now - recordStartedAt!!) / 1000.0
                if (shown < status.seconds - 0.2 || shown > status.seconds + 1.5) {
                    recordStartedAt = now - status.seconds * 1000L - 500
                }
            }
        }
        if (status.state != CaptureStatus.TIMESHIFT) timeShiftNormal = null
    }

    /**
     * Bluetooth dropped (camera out of range, battery empty…): the recording state can't be followed any more, so it
     * is dropped — a stale «recording» would keep the notification, the locks and the link forever.
     */
    private fun onControlLost() {
        if (conn == ConnState.Remote) {
            log("Bluetooth-связь с камерой потеряна")
            disconnect()
            conn = ConnState.Failed(tr("Связь с камерой потеряна", "Lost the link to the camera"))
        } else if (capture != null) {
            capture = null
            recordStartedAt = null
            timeShiftNormal = null
        }
    }

    /** Capture status and, in a TimeShift recording, its speed (switchable on the camera too). */
    private suspend fun followCapture() {
        val status = runCatching { session.captureStatus() }.getOrNull() ?: return
        updateCapture(status)
        if (status.state != CaptureStatus.TIMESHIFT) return
        val normal = runCatching { session.timeShiftNormal() }.getOrNull() ?: return
        if (capture?.state == CaptureStatus.TIMESHIFT && SystemClock.elapsedRealtime() - speedSwitchedAt >= 2_000) {
            timeShiftNormal = normal
        }
    }

    /** Economy recording (after the user's confirmation): see [ecoRecording]. */
    fun startEcoRecording() {
        if (capture?.recording != true || conn != ConnState.Connected) return
        touch()
        log("экономия: трансляция и Wi-Fi камеры отключены до конца записи, остаётся Bluetooth")
        ecoRecording = true
        pauseLive()
        session.closeWifi()
        conn = ConnState.Remote
    }

    /** A photo during a video recording is allowed now (CONF `shoot_on_video`: Video, Loop, FreeFrame, Toddler Titan,
     * not at every resolution / field of view). */
    val canShootOnVideo: Boolean
        get() {
            if (capture?.recording != true) return false
            val s = settings ?: return false
            val mode = s.mode ?: return false
            // Not at zoom ×2 (the camera refuses; CONF doesn't say so).
            if (s.values["undamage_zoom"] == "true" || s.values["damage_zoom"] == "true") return false
            return conf?.allowed(mode, "shoot_on_video", s.values)?.contains("true") == true
        }

    var photoBusy by mutableStateOf(false)
        private set

    /** The running recording is paused (tracked here: set by the app's own toggle, cleared when it ends). */
    var paused by mutableStateOf(false)
        private set

    /** Pause is offered in the modes whose CONF lists `pause_in_video` (Video, Pure, Toddler Titan, slow motion…). */
    val canPause: Boolean
        get() = capture?.recording == true && settings?.mode?.let { conf?.supports(it, "pause_in_video") } == true

    /** When the pause began (phone clock): the timer stands still meanwhile. */
    private var pausedAt = 0L

    /** The highest time shown in this recording: corrections never make it step back. Reset per recording. */
    private var shownSeconds = 0

    /** Paused ↔ running: the timer stands still while paused and goes on from the same second. */
    private fun applyPause(now: Boolean) {
        if (now == paused) return
        paused = now
        if (now) {
            pausedAt = SystemClock.elapsedRealtime()
        } else {
            recordStartedAt = recordStartedAt?.let { it + (SystemClock.elapsedRealtime() - pausedAt) }
        }
    }

    /** The state itself comes from the camera's 8208 ([CameraSession.onCapturePhase]). */
    fun togglePause() = recordingAction(if (paused) tr("Запись продолжена", "Recording resumed") else tr("Пауза", "Paused")) {
        session.togglePause()
    }

    /** Marks during any video recording (Pure Video included). */
    val canMark: Boolean get() = capture?.recording == true

    fun markHighlight() = recordingAction(tr("Отметка поставлена", "Highlight marked")) {
        session.markHighlight(recordSeconds(SystemClock.elapsedRealtime()) * 1000L)
    }

    private fun recordingAction(done: String, action: suspend () -> Boolean) {
        if (photoBusy || !session.hasControl) return
        touch()
        photoBusy = true
        scope.launch {
            try {
                message = if (action()) done else tr("Камера не выполнила команду", "The camera didn't do it")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("recording action: ${e.message}")
                message = tr("Ошибка: ", "Error: ") + e.message
            } finally {
                photoBusy = false
            }
        }
    }

    /** TAKE_PICTURE while the camera records: the recording goes on, a photo file appears next to the video. */
    fun takePhotoWhileRecording() {
        if (photoBusy || !session.hasControl) return
        touch()
        photoBusy = true
        scope.launch {
            try {
                message = if (session.takePicture()) tr("Фото снято", "Photo taken") else tr("Камера не сделала снимок", "The camera didn't take the photo")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("photo while recording: ${e.message}")
                message = tr("Ошибка: ", "Error: ") + e.message
            } finally {
                photoBusy = false
            }
        }
    }

    /** TimeShift during a recording: Auto (sped up) ↔ normal speed, like the camera's own switch. */
    fun toggleTimeShiftSpeed() {
        val current = timeShiftNormal ?: return
        if (speedBusy) return
        touch()
        speedBusy = true
        scope.launch {
            try {
                if (session.setTimeShiftNormal(!current)) {
                    speedSwitchedAt = SystemClock.elapsedRealtime()
                    timeShiftNormal = !current
                } else {
                    message = tr("Камера не переключила скорость", "The camera didn't switch the speed")
                }
            } finally {
                speedBusy = false
            }
        }
    }

    /** Self-timer seconds left before a photo (the camera applies its timer only to its own button). */
    var countdown by mutableStateOf<Int?>(null)
        private set
    private var recordJob: Job? = null

    private fun recordAction(start: Boolean) {
        // The shutter during a countdown cancels it.
        if (countdown != null) {
            recordJob?.cancel()
            message = tr("Таймер отменён", "Timer cancelled")
            return
        }
        if (recordBusy || conn is ConnState.Working || setup is ConnState.Working) return
        if (!session.hasControl && !radiosReady(needWifi = false) { recordAction(start) }) return
        touch()
        recordBusy = true
        recordJob = scope.launch {
            try {
                if (!session.hasControl) {
                    conn = ConnState.Working(tr("Подключение…", "Connecting…"))
                    val error = session.connectControl { step -> scope.launch { conn = ConnState.Working(step) } }
                    if (error != null) {
                        session.disconnect()
                        conn = ConnState.Failed(error)
                        return@launch
                    }
                    conn = ConnState.Remote
                }
                // Photo / burst: the shutter takes a picture; other modes (incl. interval, starlapse) record.
                if (start && settings == null) session.readSettings()?.let(::showSettings)
                val picture = start && settings?.mode?.let { SettingCodes.modes[it] }?.takesPicture == true
                // TAKE_PICTURE fires at once even with the camera's self-timer set: count down here instead.
                val timer = if (picture) settings?.values?.get("photography_self_timer")?.toIntOrNull() ?: 0 else 0
                for (left in timer downTo 1) {
                    countdown = left
                    delay(1_000)
                }
                countdown = null
                val ok = when {
                    picture -> session.takePicture()
                    start -> session.startRecording()
                    else -> session.stopRecording()
                }
                if (!ok) {
                    message = when {
                        picture -> tr("Камера не сделала снимок", "The camera didn't take the photo")
                        start -> tr("Камера не начала запись", "The camera didn't start recording")
                        else -> tr("Камера не остановила запись", "The camera didn't stop recording")
                    }
                } else if (picture) {
                    message = tr("Снято", "Taken")
                }
                session.captureStatus()?.let(::updateCapture)
                // The new file shows up in the list.
                if ((picture || !start) && ok && conn == ConnState.Connected) loadFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("record failed: ${e.message}")
                message = tr("Ошибка: ", "Error: ") + e.message
            } finally {
                countdown = null
                recordBusy = false
            }
        }
    }

    // ---------- shooting settings ----------

    /** Downloads /CONF over Wi-Fi when there is no cached copy yet (it only changes with firmware updates). */
    private suspend fun loadConf() {
        if (conf != null) return
        val text = try {
            session.fetchConf()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("CONF не загружен: ${e.message}")
            return
        }
        val parsed = runCatching { CameraConf(text) }.onFailure { log("CONF: ${it.message}") }.getOrNull() ?: return
        withContext(Dispatchers.IO) { runCatching { confFile.writeText(text) } }
        conf = parsed
        log("CONF: режимов ${parsed.modes.size}")
    }

    private fun showSettings(s: CamSettings) {
        if (s != settings) log("настройки: ${s.mode} ${s.values} батарея ${s.batteryPercent}/${s.podBatteryPercent}")
        settings = s
        restartLiveIfShapeChanged(s)
    }

    private var liveRestartAt = 0L

    /**
     * The camera keeps the stream's frame size until the stream is restarted: after switching to FreeFrame (4:3) or
     * 2.35:1 the picture would stay 16:9. When the recording's shape and the stream's differ, restart the stream.
     */
    private fun restartLiveIfShapeChanged(s: CamSettings) {
        if (liveDecoder == null || conn != ConnState.Connected || !liveSizeKnown) return
        val res = (if (s.mode?.let { SettingCodes.modes[it]?.photo } == true) s.values["photo_resolution"] else s.values["record_resolution"])
            ?.split('_')?.mapNotNull { it.toIntOrNull() } ?: return
        if (res.size < 2 || res[1] == 0) return
        val want = res[0].toFloat() / res[1]
        if (kotlin.math.abs(want - liveAspect) / want < 0.04f) return
        val now = SystemClock.elapsedRealtime()
        if (now - liveRestartAt < 5_000) return // the stream may simply not support that shape: don't loop
        liveRestartAt = now
        val surface = liveSurfaces.lastOrNull() ?: return
        log("трансляция: перезапуск под кадр %.2f (сейчас %.2f)".format(want, liveAspect))
        restartLive(surface)
    }

    fun refreshSettings() {
        if (!session.hasControl) return
        scope.launch {
            if (session.hasWifi) loadConf()
            runCatching { session.readSettings() }
                .onFailure { log("settings read failed: ${it.message}") }
                .getOrNull()
                ?.let(::showSettings)
        }
    }

    /** Switches the shooting mode: video_sub_mode (SET_OPTIONS 41) or photo_sub_mode (40). */
    fun selectMode(mode: String) {
        val m = SettingCodes.modes[mode] ?: return
        applyChange("mode") {
            if (m.photoSub != null) {
                session.writeOption(SettingCodes.OPT_PHOTO_SUB_MODE, m.photoSub.toLong())
            } else {
                session.writeOption(SettingCodes.OPT_VIDEO_SUB_MODE, m.videoSub!!.toLong())
            }
        }
    }

    /**
     * Writes one setting of the current mode ([param] is the CONF name, [value] a CONF value),
     * then re-reads everything: the camera adjusts dependent values itself (e.g. no horizon lock without FlowState).
     */
    fun setSetting(param: String, value: String) {
        val current = settings ?: return
        applyChange(param, param to value) {
            writeSetting(param, value, current) ?: true.also { message = tr("Это значение приложение пока не умеет выставлять", "The app can't set this value yet") }
        }
    }

    /**
     * One setting of the current mode, exactly as the settings screen does it (the self-test uses it too).
     * Returns whether the camera answered OK, or null when the app can't encode this value.
     */
    private suspend fun writeSetting(param: String, value: String, current: CamSettings): Boolean? {
        val mode = current.mode ?: return null
        val m = SettingCodes.modes[mode] ?: return null
        val fm = m.functionMode
        fun pb(build: PbWriter.() -> PbWriter) = PbWriter().build().toByteArray()
        return when {
            // The camera's own menu sets both option 134 and PhotographyOptions 42.
            param == "dark_eis_enable" -> {
                val on = if (value == "true") 1L else 0L
                session.writeOption(SettingCodes.OPT_BLUR_REDUCTION, on, SettingCodes.OPT_BLUR_REDUCTION_TYPE) &&
                    session.writePhotoOptions(fm, mapOf(SettingCodes.DARK_EIS to pb { varint(SettingCodes.DARK_EIS, on) }))
            }
            param == "lapse_time" -> {
                val tm = m.timelapseMode ?: return null
                val ms = value.toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it > 0 } ?: return null
                session.writeLapse(tm, ms)
            }
            else -> {
                val fields = encode(param, value, current) ?: return null
                // Dependents first, in their own SET: the camera checks a new value against the settings it has *now*.
                val dependents = dependentFixes(mode, current, param, value)
                (dependents.isEmpty() || session.writePhotoOptions(fm, dependents)) && session.writePhotoOptions(fm, fields)
            }
        }
    }
    /**
     * Settings the camera menu itself adjusts when a choice makes them impossible (4K60 → HDR off, Ultrawide →
     * MegaView). A write with an impossible combination is silently ignored by the camera, so the app sends these
     * first: the first value CONF allows (off for switches). Two passes for chains (FOV → horizon lock).
     */
    private fun dependentFixes(mode: String, current: CamSettings, param: String, value: String): Map<Int, ByteArray> {
        val c = conf ?: return emptyMap()
        val values = current.values.toMutableMap().apply { put(param, value) }
        val out = mutableMapOf<Int, ByteArray>()
        if (values["hdr_switch"] == "true" && !c.hdrPossible(mode, values["record_resolution"])) {
            encode("hdr_switch", "false", current)?.let { out.putAll(it); values["hdr_switch"] = "false" }
            log("hdr_switch: true → false (нет HDR при ${values["record_resolution"]})")
        }
        repeat(2) {
            for (dep in DEPENDENT_PARAMS) {
                if (dep == param || !c.supports(mode, dep)) continue
                val now = values[dep] ?: continue
                val allowed = c.allowed(mode, dep, values)
                if (allowed.isEmpty() || now in allowed) continue
                val pick = if ("false" in allowed) "false" else allowed.firstOrNull { encode(dep, it, current) != null } ?: continue
                encode(dep, pick, current)?.let {
                    out.putAll(it)
                    values[dep] = pick
                    log("$dep: $now → $pick (несовместимо с $param=$value)")
                }
            }
        }
        return out
    }

    /** PhotographyOptions fields for one CONF value; null when the app doesn't know how to encode it. */
    private fun encode(param: String, value: String, current: CamSettings): Map<Int, ByteArray>? {
        fun pb(build: PbWriter.() -> PbWriter) = PbWriter().build().toByteArray()
        fun seconds(vararg fields: Int) = fields.associateWith { f -> pb { varint(f, value.toLong()) } }
        /** Both exposure blocks (20 still, 21 video) with the program derived from ISO / shutter like the camera does. */
        fun exposure(iso: Long, shutter: Double): Map<Int, ByteArray> {
            val program = SettingCodes.exposurePrograms.getValue(SettingCodes.programFor(iso, shutter)).toLong()
            val body = PbWriter().varint(1, program).varint(2, iso).double(3, shutter).toByteArray()
            return listOf(SettingCodes.STILL_EXPOSURE, SettingCodes.VIDEO_EXPOSURE).associateWith { f -> pb { bytes(f, body) } }
        }
        val iso = current.values["exposure_iso"]?.toLongOrNull() ?: 0L
        val shutter = current.values["exposure_shutter_speed"]?.let(SettingCodes::shutterSeconds) ?: 0.0
        fun burst(params: String, cache: Boolean): Map<Int, ByteArray>? {
            val (shots, secs) = params.split('/').map { it.toLongOrNull() ?: return null }
            val cached = if (cache) SettingCodes.burstCacheFrames[params] ?: return null else 0
            return with(SettingCodes) {
                mapOf(
                    BURST_COUNT to pb { varint(BURST_COUNT, shots) },
                    BURST_SECONDS to pb { varint(BURST_SECONDS, secs) },
                    BURST_CACHE_FRAMES to pb { varint(BURST_CACHE_FRAMES, cached.toLong()) },
                    BURST_CACHE to pb { varint(BURST_CACHE, if (cache) 1 else 0) },
                )
            }
        }
        fun bool(field: Int) = mapOf(field to pb { varint(field, if (value == "true") 1 else 0) })
        fun code(field: Int, map: Map<String, Int>): Map<Int, ByteArray>? =
            map[value]?.let { c -> mapOf(field to pb { varint(field, c.toLong()) }) }
        return with(SettingCodes) {
            when (param) {
                "record_resolution" -> code(RESOLUTION, resolutions)
                "fov_type" -> code(FOV, fov)
                "flowstate_level" -> code(FLOWSTATE, flowstate)
                "sharpness_level" -> code(SHARPNESS, sharpness)
                "filter_mode" -> code(FILTER, filter)
                "metering_mode" -> code(METERING, metering)
                "white_balance" -> mapOf(WHITE_BALANCE to pb { varint(WHITE_BALANCE, value.toLong()) })
                "exposure_bias" -> mapOf(EXPOSURE_BIAS to pb { varint(EXPOSURE_BIAS, zigzag(evToTenths(value))) })
                "horizon_lock" -> bool(HORIZON_LOCK)
                "hdr_switch" -> bool(HDR)
                "i_log_switch" -> bool(I_LOG)
                "damage_zoom", "undamage_zoom" -> {
                    val on = value == "true"
                    mapOf(
                        DAMAGE_ZOOM to pb { varint(DAMAGE_ZOOM, if (on) 1 else 0) },
                        ZOOM_SCALE to pb { double(ZOOM_SCALE, if (on) 1.0 else 0.0) },
                    )
                }
                // Manual starts from the current values, or ISO 400 + 1/100 when they are auto.
                "exposure_program" -> if (value == "FULL_AUTO") exposure(0, 0.0)
                    else exposure(iso.takeIf { it > 0 } ?: 400, shutter.takeIf { it > 0 } ?: 0.01)
                // «AUTO_3200»: auto ISO with that maximum (field 24), as the camera menu offers it.
                "exposure_iso" -> if (value.startsWith("AUTO_")) {
                    val limit = value.removePrefix("AUTO_").toLongOrNull() ?: 0
                    exposure(0, shutter) + (ISO_TOP_LIMIT to pb { varint(ISO_TOP_LIMIT, limit) })
                } else {
                    exposure(value.toLongOrNull() ?: 0, shutter)
                }
                "exposure_shutter_speed" -> exposure(iso, shutterSeconds(value))
                "video_iso_top_limit" -> seconds(ISO_TOP_LIMIT)
                "self_portrait_switch_2" -> portrait[value]?.let { (brighten, smooth) ->
                    val body = PbWriter().float(1, if (brighten) 1f else 0f).float(2, if (smooth) 1f else 0f).toByteArray()
                    mapOf(BEAUTY to pb { bytes(BEAUTY, body) })
                }
                "photo_resolution" -> code(PHOTO_RESOLUTION, photoResolutions)
                "raw_capture_type" -> code(RAW_CAPTURE, rawCapture)
                "export_type" -> code(EXPORT_TYPE, exportType)
                "photography_self_timer" -> seconds(SELF_TIMER)
                "record_duration" -> seconds(DURATION, DURATION_2)
                "live_photo_switch" -> mapOf(LIVE_PHOTO to pb { bytes(LIVE_PHOTO, PbWriter().varint(2, if (value == "true") 2 else 1).toByteArray()) })
                "burst_capture_params" -> burst(value, current.values["cache_capture_enable"] == "true")
                "cache_capture_enable" -> current.values["burst_capture_params"]?.let { burst(it, value == "true") }
                else -> null
            }
        }
    }

    /** Writes, then reads back; [expect] (param → value): some (live photo, portrait) show up only ~1–2 s later. */
    private fun applyChange(what: String, expect: Pair<String, String>? = null, write: suspend () -> Boolean) {
        if (settingBusy != null || !session.hasControl) return
        touch()
        settingBusy = what
        scope.launch {
            try {
                val ok = write()
                if (!ok) message = tr("Камера не приняла настройку", "The camera didn't accept the setting")
                delay(300)
                var read = session.readSettings()?.also(::showSettings)
                var tries = 0
                while (ok && expect != null && read != null && read.values[expect.first] != expect.second && ++tries <= 4) {
                    delay(700)
                    read = session.readSettings()?.also(::showSettings)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("setting $what failed: ${e.message}")
                message = tr("Ошибка: ", "Error: ") + e.message
            } finally {
                settingBusy = null
            }
        }
    }

    // ---------- live view ----------

    /** Preview surfaces on screen, latest last (portrait box, full-screen landscape view). */
    private val liveSurfaces = mutableListOf<android.view.Surface>()

    /**
     * A preview surface appeared (camera tab, full connection): show the stream there. With the stream already
     * running (another view is up) the decoder just moves to this surface — no restart, no black pause.
     */
    fun attachLive(surface: android.view.Surface) {
        liveSurfaces.remove(surface)
        liveSurfaces.add(surface)
        liveDecoder?.let { it.setSurface(surface); return }
        if (conn != ConnState.Connected) return
        // Debug builds keep the first 5 MB of the stream (live.h265, length-prefixed packets) for analysis.
        val debuggable = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val dump = if (debuggable) app.getExternalFilesDir(null)?.let { File(it, "live.h265") } else null
        val decoder = newDecoder(surface, dump)
        liveDecoder = decoder
        liveError = null
        liveStartedAt = SystemClock.elapsedRealtime()
        scope.launch {
            if (!session.startLive(decoder::onPacket)) liveError = tr("Камера не начала трансляцию", "The camera didn't start the live view")
        }
    }

    /** The decoder reported this stream's real size (until then [liveAspect] may be the previous stream's). */
    private var liveSizeKnown = false

    private fun newDecoder(surface: android.view.Surface, dump: File? = null): LiveDecoder {
        liveSizeKnown = false
        return LiveDecoder(surface, ::log, dump) { w, h ->
            scope.launch {
                if (h > 0) liveAspect = w.toFloat() / h
                liveSizeKnown = true
                settings?.let(::restartLiveIfShapeChanged)
            }
        }
    }

    /**
     * The camera restarted its encoder (8234 UPDATE_LIVE_STREAM_PARAMS) after a setting change (live photo, photo
     * mode…). Frames keep coming, but the decoder set up from the first keyframe stays frozen: restart the stream.
     * The 8234 that follows our own StartLiveStream is ignored.
     */
    private fun onLiveParamsChanged() {
        val now = SystemClock.elapsedRealtime()
        if (liveDecoder == null || conn != ConnState.Connected || now - liveStartedAt < 5_000) return
        // During a recording 8234 comes with a zoom (the crop window changes, twice): the official app just keeps
        // the stream; a stop/start then would land in the middle of the camera's work.
        if (capture?.recording == true) return
        val surface = liveSurfaces.lastOrNull() ?: return
        log("камера перезапустила поток (8234): перезапускаю трансляцию")
        restartLive(surface)
    }

    /** When the app last sent StartLiveStream (its own 8234 comes ~1–2 s later). */
    private var liveStartedAt = 0L

    /** Stop, then start again — strictly in this order (two parallel requests may arrive swapped). */
    private fun restartLive(surface: android.view.Surface) {
        val old = liveDecoder ?: return
        val decoder = newDecoder(surface)
        liveDecoder = decoder
        liveStartedAt = SystemClock.elapsedRealtime()
        scope.launch {
            runCatching { session.stopLive() }
            withContext(Dispatchers.IO) { old.release() }
            if (liveDecoder !== decoder || conn != ConnState.Connected) return@launch
            if (!session.startLive(decoder::onPacket)) liveError = tr("Камера не начала трансляцию", "The camera didn't start the live view")
        }
    }

    /** A preview surface is going away: move the picture to the remaining one, or stop the stream. */
    fun detachLive(surface: android.view.Surface) {
        val wasCurrent = liveSurfaces.lastOrNull() == surface
        liveSurfaces.remove(surface)
        val next = liveSurfaces.lastOrNull()
        if (next == null) detachLive() else if (wasCurrent) liveDecoder?.setSurface(next)
    }

    fun detachLive() {
        liveSurfaces.clear()
        pauseLive()
    }

    /** Stops the stream but keeps the screen's surface: [resumeLive] restarts it there. */
    private fun pauseLive() {
        val decoder = liveDecoder ?: return
        liveDecoder = null
        scope.launch { runCatching { session.stopLive() } }
        scope.launch(Dispatchers.IO) { decoder.release() }
    }

    /** Back from the background with the preview still on screen: restart its stream. */
    private fun resumeLive() {
        if (liveDecoder == null && conn == ConnState.Connected) liveSurfaces.lastOrNull()?.let(::attachLive)
    }

    /** Any user action (tap, scroll, refresh…) postpones the idle disconnect. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    /**
     * App went to the background (or was closed with Back): the connection is kept for [BACKGROUND_GRACE_MS]
     * — e.g. to look at a downloaded file in the gallery — then released. [CameraService] (foreground service)
     * keeps the process unfrozen meanwhile, otherwise the timer may never fire and the open BLE link / Wi-Fi
     * keep the camera awake all night. Without a link the camera falls asleep by itself ~2.5 min later.
     */
    fun onBackground() {
        uiShown = false
        backgroundSince = SystemClock.elapsedRealtime()
    }

    /** Seconds left before the background disconnect, or null when the app is visible / nothing to release. */
    fun backgroundSecondsLeft(): Int? {
        if (uiShown || !isLinked || isDownloading || capture?.recording == true) return null
        val since = maxOf(backgroundSince, lastActivity)
        return ((BACKGROUND_GRACE_MS - (SystemClock.elapsedRealtime() - since)) / 1000).toInt().coerceAtLeast(0)
    }

    /** Back in the foreground: reconnect if the background timer dropped the connection. */
    fun onForeground() {
        uiShown = true
        radios = PhoneRadios.check(app)
        touch()
        resumeLive()
        // Files deleted in the gallery meanwhile lose their «downloaded» tick.
        scope.launch { refreshDownloaded() }
        if (resumeOnForeground && onboarded) {
            resumeOnForeground = false
            if (resumeControlOnly) reconnectControl() else connect()
        }
    }

    /** BLE control link only (after «разбудить и записать»): restores [ConnState.Remote]. */
    private fun reconnectControl() {
        if (conn is ConnState.Working) return
        if (!radiosReady(needWifi = false, retry = ::reconnectControl)) return
        linkJob = scope.launch {
            conn = ConnState.Working(tr("Подключение…", "Connecting…"))
            val error = try {
                session.connectControl { step -> scope.launch { conn = ConnState.Working(step) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("reconnect failed: ${e.message}")
                tr("Ошибка: ", "Error: ") + e.message
            }
            if (error != null) {
                session.disconnect()
                conn = ConnState.Failed(error)
            } else {
                conn = ConnState.Remote
                // The old state is kept: a recording that ended meanwhile shows up as an end (release timer…).
                followCapture()
            }
        }
    }

    private fun disconnectIfIdle() {
        if (!isLinked || isDownloading || keepConnected || recordBusy || (viewing != null && uiShown)) return
        if (capture?.recording == true) {
            // Recording: stay connected (the notification's Stop needs it), but in the background stop the live
            // view — the camera's biggest extra drain. Wi-Fi is kept: mid-recording the camera turns its access
            // point off once the phone leaves, and OPEN_CAMERA_WIFI doesn't bring it back until the stop.
            if (!uiShown && liveDecoder != null && SystemClock.elapsedRealtime() - backgroundSince > 30_000) {
                log("запись идёт, приложение в фоне: трансляция остановлена, связь остаётся")
                pauseLive()
            }
            return
        }
        if (!uiShown) {
            // In the background: grace period from leaving the app or from the end of the last download.
            if (backgroundSecondsLeft() != 0) return
            log("приложение в фоне ${BACKGROUND_GRACE_MS / 60_000} мин: отключаюсь от камеры")
            resumeOnForeground = true
            resumeControlOnly = conn == ConnState.Remote
            disconnect()
            return
        }
        if (SystemClock.elapsedRealtime() - lastActivity < IDLE_VISIBLE_MS) return
        log("idle for ${IDLE_VISIBLE_MS / 1000} s: disconnecting")
        disconnect()
        conn = ConnState.Failed(
            tr(
                "Камера отключена, чтобы не разряжаться без дела.\nНажмите «Подключиться».",
                "Disconnected to save the camera's battery.\nTap «Connect».",
            ),
        )
    }

    // ---------- selection ----------

    fun toggle(file: CameraFile) {
        touch()
        if (selected.remove(file.name) == null) selected[file.name] = Unit
    }

    /** Selects all visible files that are not in the gallery yet. */
    fun selectNew() {
        touch()
        visibleFiles.filter { it.name !in downloaded }.forEach { selected[it.name] = Unit }
    }

    fun clearSelection() = selected.clear()

    // ---------- viewing ----------

    /** File shown in the in-app viewer: still on the camera (the link is kept alive while it is open) or a phone copy. */
    var viewing by mutableStateOf<CameraFile?>(null)

    /** Gallery item of a downloaded file, to open it in the phone's own player / gallery. */
    suspend fun galleryUri(file: CameraFile): android.net.Uri? =
        if (file.localUri != null) android.net.Uri.parse(file.localUri) else if (file.name !in downloaded) null else withContext(Dispatchers.IO) { Downloader.galleryUri(app, file.name) }

    /**
     * A downloaded video opens from the phone even with the camera there: full quality, no Wi-Fi traffic. The item
     * keeps the camera's uri, so its marks still come from the camera (they may have been edited there since).
     */
    suspend fun withPhoneCopy(file: CameraFile): CameraFile {
        if (file.isOnPhone || file.kind != MediaKind.VIDEO || file.name !in downloaded) return file
        val uri = withContext(Dispatchers.IO) { Downloader.galleryUri(app, file.name) } ?: return file
        return file.copy(localUri = uri.toString())
    }

    fun view(file: CameraFile) {
        touch()
        if (file.isOnPhone || conn == ConnState.Connected) viewing = file else message = tr("Чтобы посмотреть файл с камеры, подключитесь", "Connect to view a file from the camera")
    }

    /** Player data source for the in-app viewer, or null without the camera Wi-Fi. */
    fun previewSource(): androidx.media3.datasource.DataSource.Factory? =
        session.wifiLink?.let { CameraHttpDataSource.Factory(it) }

    fun previewUrl(file: CameraFile): String = "http://${CameraTcp.HOST}${session.previewUri(file)}"

    /** Film strip frames: one per piece (its middle), small; null where a frame couldn't be read. */
    suspend fun pieceFrames(file: CameraFile, pieces: List<Highlight>): List<ImageBitmap?> {
        val times = pieces.map { (it.startMs + (it.endMs ?: it.startMs)) / 2 }
        file.localUri?.let { uri ->
            return withContext(Dispatchers.IO) {
                RemoteFrames.local(app, android.net.Uri.parse(uri), times, 320).map { it?.asImageBitmap() }
            }
        }
        if (conn != ConnState.Connected || pieces.isEmpty()) return pieces.map { null }
        return runCatching { session.frames(file, times, 320) }.getOrElse { times.map { null } }.map { it?.asImageBitmap() }
    }

    /** The video's highlight marks for the viewer; empty when there are none or they couldn't be read. */
    suspend fun highlights(file: CameraFile): List<Highlight> {
        // A camera file with a phone copy: the camera's record first (marks edited there after the download).
        val onCard = !file.uri.startsWith(Downloader.PHONE_PREFIX) && conn == ConnState.Connected
        val fromCamera = if (onCard) runCatching { session.highlights(file) }.getOrNull() else null
        // Otherwise a downloaded copy keeps the camera's trailer: the same record, read from the end of the file.
        val marks = fromCamera ?: file.localUri?.let { local ->
            withContext(Dispatchers.IO) {
                runCatching {
                    LocalTrailer.read(app, android.net.Uri.parse(local))?.let { t ->
                        t.highlight?.let { Highlights.parse(it, t.firstFrameUs, file.durationSec?.let { s -> (s + 1) * 1000L }) }
                    }
                }.onFailure { log("отметки ${file.name} с телефона: ${it.message}") }.getOrNull()
            }
        }
        return marks.orEmpty()
            .also { if (debuggable) log("отметки ${file.name}: ${it.joinToString { h -> "${h.startMs}" + (h.endMs?.let { e -> "–$e" } ?: "") }}") }
    }

    /** Photo for the viewer, decoded to about screen size (a 50 MP JPEG must not be decoded in full). */
    suspend fun previewPhoto(file: CameraFile, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        // A live photo with a frame as its cover: show that (the gallery file), not the camera's photo.
        val bytes = (if (coverReplaced(file)) LiveCover.video(app, file.name)?.let { LiveCover.photoPart(app, it) } else null)
            ?: runCatching { session.fetchFile(session.previewUri(file)) }
                .onFailure { log("просмотр ${file.name}: ${it.message}") }.getOrNull() ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }

    /** «Скачать» from the viewer: just this file. */
    fun downloadOne(file: CameraFile) {
        if (isDownloading) return
        selected.clear()
        selected[file.name] = Unit
        downloadSelected(overwrite = false)
    }

    var deleting by mutableStateOf(false)
        private set

    /** Deletes the selected files from the camera card (the UI asks for confirmation first). */
    fun deleteSelected() {
        val files = selectedFiles
        if (files.isEmpty() || deleting || conn != ConnState.Connected || isDownloading) return
        touch()
        deleting = true
        scope.launch {
            try {
                // A live photo's tile stands for its clip too (and the clip's .lrv, added by deleteFiles).
                val failed = session.deleteFiles(files + files.mapNotNull(::liveClipOf))
                message = when {
                    failed == null -> tr("Камера не удалила файлы", "The camera didn't delete the files")
                    failed.isEmpty() -> tr("Удалено с камеры: ", "Deleted from the camera: ") + files.size
                    else -> tr("Не удалось удалить: ", "Couldn't delete: ") + failed.joinToString { it.substringAfterLast('/') }
                }
                selected.clear()
                loadFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("delete failed: ${e.message}")
                message = tr("Ошибка: ", "Error: ") + e.message
            } finally {
                deleting = false
            }
        }
    }

    // ---------- thumbnails ----------

    suspend fun thumbnail(file: CameraFile): ImageBitmap? {
        memCache.get(file.name)?.let { return it }
        val cached = File(thumbDir, file.name + ".jpg")
        val bitmap = withContext(Dispatchers.IO) {
            cached.takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.path) ?: null.also { f.delete() } // corrupt cache entry: refetch
            } ?: if (file.localUri != null) {
                // A phone copy whose camera thumbnail was never cached: the gallery's own (cached by MediaStore).
                runCatching { app.contentResolver.loadThumbnail(android.net.Uri.parse(file.localUri), android.util.Size(320, 320), null) }
                    .onFailure { log("миниатюра ${file.name} с телефона: ${it.message}") }
                    .getOrNull()
            } else if (conn == ConnState.Connected) {
                thumbPermits.withPermit {
                    runCatching { session.thumbnail(file) }
                        .onFailure { log("thumbnail ${file.name}: ${it.message}") }
                        .getOrNull()
                        ?.also { bmp -> saveAtomically(cached, bmp) }
                }
            } else {
                null
            }
        } ?: return null
        return bitmap.asImageBitmap().also { memCache.put(file.name, it) }
    }

    /** Temp file + rename: a killed process never leaves a truncated thumbnail behind. */
    private fun saveAtomically(target: File, bmp: Bitmap) {
        val tmp = File(target.path + ".tmp")
        runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(target)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    // ---------- downloads ----------

    /** Selected files that are already in the gallery (the UI asks: skip or overwrite). */
    val selectedAlreadyDownloaded: Int get() = selectedFiles.count { it.name in downloaded }

    fun downloadSelected(overwrite: Boolean) {
        if (isDownloading) return
        // A live photo goes with its clip as one gallery file.
        val queue = selectedFiles.filter { overwrite || it.name !in downloaded }.sortedBy { it.created }
        if (queue.isEmpty()) {
            selected.clear()
            message = tr("Все выбранные файлы уже в галерее", "All selected files are already in the gallery")
            return
        }
        downloadJob = scope.launch {
            var ok = 0
            var failed = 0
            try {
                for ((i, file) in queue.withIndex()) {
                    if (conn != ConnState.Connected) break
                    val startedAt = System.currentTimeMillis()
                    var startedFrom = -1L
                    download = DownloadProgress(file.name, i + 1, queue.size, 0, file.size ?: 0, 0)
                    try {
                        session.download(file, overwrite, liveVideo = liveClipOf(file)) { done, total ->
                            if (startedFrom < 0) startedFrom = done
                            val sec = (System.currentTimeMillis() - startedAt) / 1000.0
                            val speed = if (sec > 0.5) ((done - startedFrom) / sec).toLong() else 0L
                            scope.launch { download = DownloadProgress(file.name, i + 1, queue.size, done, total, speed) }
                        }
                        ok++
                        downloaded = downloaded + file.name
                        selected.remove(file.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("download ${file.name} failed: ${e.message}")
                        failed++
                    }
                }
                lastResult = tr("Скачано: $ok", "Downloaded: $ok") + (if (failed > 0) tr(", ошибок: $failed", ", errors: $failed") else "") +
                    (if (ok + failed < queue.size) tr(", прервано", ", interrupted") else "")
            } catch (e: CancellationException) {
                lastResult = tr(
                    "Остановлено, скачано: $ok (недокачанное продолжится в следующий раз)",
                    "Stopped, downloaded: $ok (unfinished files resume next time)",
                )
                throw e
            } finally {
                download = null
                message = lastResult
                downloadResult = lastResult
                touch() // the idle / background countdown starts after the download
            }
        }
        CameraService.start(app)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    // ---------- pieces: edits and clips ----------

    /** Pieces edited in the film strip, per file name (the camera's file and its phone copy share them). */
    private val piecePrefs = app.getSharedPreferences("pieces", 0)

    /** The pieces as edited on the phone; null = never edited (the camera's marks apply). */
    fun editedPieces(file: CameraFile): List<Highlight>? = piecePrefs.getString(file.name, null)?.let(Highlights::decode)

    /** [pieces] null: back to the camera's marks. */
    fun savePieces(file: CameraFile, pieces: List<Highlight>?) {
        piecePrefs.edit().apply { if (pieces == null) remove(file.name) else putString(file.name, Highlights.encode(pieces)) }.apply()
    }

    fun clipName(file: CameraFile): String = file.stem + "_HL.mp4"

    // ---------- the editor's timeline: frames along the whole video ----------

    private val timelineLock = kotlinx.coroutines.sync.Mutex()
    private var timelineReader: Pair<String, FrameReader>? = null
    private val timelineFrames = LruCache<String, ImageBitmap>(600)

    /** A small frame of [file] near [ms] (its key frame), from the phone's copy or the camera's light copy. */
    suspend fun timelineFrame(file: CameraFile, ms: Long): ImageBitmap? {
        val key = "${file.name}@$ms"
        timelineFrames.get(key)?.let { return it }
        return timelineLock.withLock {
            withContext(Dispatchers.IO) {
                timelineFrames.get(key) ?: run {
                    val id = file.localUri ?: file.uri
                    val reader = timelineReader?.takeIf { it.first == id }?.second
                        ?: openFrameReader(file)?.also { r -> timelineReader?.second?.close(); timelineReader = id to r }
                        ?: return@withContext null
                    reader.frame(ms, 192)?.asImageBitmap()?.also { timelineFrames.put(key, it) }
                }
            }
        }
    }

    private suspend fun openFrameReader(file: CameraFile): FrameReader? = runCatching {
        file.localUri?.let { RemoteFrames.localReader(app, android.net.Uri.parse(it)) }
            ?: if (conn == ConnState.Connected) session.frameReader(file) else null
    }.onFailure { log("лента ${file.name}: ${it.message}") }.getOrNull()

    /** The viewer closed: let the video go. */
    fun closeTimeline() {
        scope.launch {
            timelineLock.withLock {
                timelineReader?.second?.close()
                timelineReader = null
            }
        }
    }

    private val keyFrameCache = mutableMapOf<String, KeyFrames>()

    /**
     * Key frames of the full-quality video (the one clips are cut from — not the camera's light copy): from the
     * phone's copy, or the index of the camera's file over Range reads. Null when they couldn't be read.
     */
    suspend fun keyFrames(file: CameraFile): KeyFrames? {
        keyFrameCache[file.name]?.let { return it }
        val keys = withContext(Dispatchers.IO) {
            runCatching {
                val local = file.localUri
                if (local != null) {
                    app.contentResolver.openFileDescriptor(android.net.Uri.parse(local), "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                            Mp4Index.keyFrames(ch.size()) { at, n ->
                                val buf = java.nio.ByteBuffer.allocate(n)
                                while (buf.hasRemaining() && ch.read(buf, at + buf.position()) > 0) Unit
                                buf.array()
                            }
                        }
                    }
                } else if (conn == ConnState.Connected) {
                    val src = session.indexSource(file)
                    Mp4Index.keyFrames(src.getSize()) { at, n -> src.bytes(at, n) }.also { log("индекс ${file.name}: прочитано ${src.fetched / 1024} КБ") }
                } else {
                    null
                }
            }.onFailure { log("ключевые кадры ${file.name}: ${it.message}") }.getOrNull()
        } ?: return null
        keyFrameCache[file.name] = keys
        return keys
    }

    /**
     * One clip of [pieces] into the gallery ([ClipMaker]): from the phone's copy when there is one, otherwise from the
     * camera, reading only the file's index and the pieces. Runs as a download (progress below, «Стоп» stops it).
     */
    fun saveClip(file: CameraFile, marked: List<Highlight>) {
        if (isDownloading || marked.isEmpty()) return
        val local = file.localUri
        if (local == null && conn != ConnState.Connected) {
            message = tr("Чтобы сделать клип с камеры, подключитесь", "Connect to make a clip from the camera")
            return
        }
        val name = clipName(file)
        // Pieces moved together are one piece in the clip (no seam); starts on the nearest key frame, as the editor
        // sets them (camera marks never edited aren't there yet).
        val joined = Highlights.joinTouching(marked)
        val keys = keyFrameCache[file.name]
        var pieces = keys?.let { k -> joined.fold(emptyList<Highlight>()) { done, p -> done + k.snapStart(p, done) } } ?: joined
        val wantedUs = pieces.sumOf { (it.endMs ?: it.startMs) - it.startMs } * 1000
        // Progress in bytes, as for downloads: the share of the file the pieces make up.
        val estimate = file.size?.let { size -> file.durationSec?.takeIf { it > 0 }?.let { size * wantedUs / (it * 1_000_000L) } } ?: 0L
        downloadJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var reportedAt = 0L
            download = DownloadProgress(name, 1, 1, 0, estimate, 0)
            try {
                var cut = emptyList<Highlight>()
                // Without the key frames yet (never edited): read them first, then snap as above.
                if (keys == null) {
                    keyFrames(file)?.let { k -> pieces = joined.fold(emptyList()) { done, p -> done + k.snapStart(p, done) } }
                }
                withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    val camera = if (local == null) session.clipSource(file) else null
                    val open: (android.media.MediaExtractor) -> Unit = if (camera != null) {
                        { ex -> ex.setDataSource(camera) }
                    } else {
                        val uri = android.net.Uri.parse(local!!)
                        ({ ex -> ex.setDataSource(app, uri, null) })
                    }
                    Downloader.saveClip(app, name, file.created?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()) { fd ->
                        cut = ClipMaker.join(open, pieces, fd) { us ->
                            if (job?.isActive == false) throw CancellationException("остановлено")
                            val now = System.currentTimeMillis()
                            if (now - reportedAt >= 250) {
                                reportedAt = now
                                val done = camera?.fetched ?: if (wantedUs > 0) estimate * us / wantedUs else 0L
                                val sec = (now - startedAt) / 1000.0
                                val speed = if (camera != null && sec > 0.5) (done / sec).toLong() else 0L
                                scope.launch { download = DownloadProgress(name, 1, 1, done, maxOf(estimate, done), speed) }
                            }
                        }
                    }
                }
                val seconds = cut.sumOf { it.endMs!! - it.startMs } / 1000.0
                lastResult = tr("Клип сохранён: $name · ", "Clip saved: $name · ") + "%.1f ".format(seconds) + tr("с", "s")
                // Asked for vs cut (the starts move back to key frames: shows how far apart they are on this camera).
                log(
                    "клип $name " + (if (local == null) "(с камеры)" else "(с телефона)") +
                        ": отмечено " + marked.joinToString { "${it.startMs}–${it.endMs}" } +
                        "; вырезано " + cut.joinToString { "${it.startMs}–${it.endMs}" } + "; итого %.1f с".format(seconds) +
                        "; ключевые кадры " + keyFrames(file)?.ms.orEmpty()
                            .filter { k -> pieces.any { k >= it.startMs - 5_000 && k <= it.endMs!! } }.joinToString(),
                )
                loadPhoneFiles()
            } catch (e: CancellationException) {
                lastResult = tr("Клип не сохранён: остановлено", "Clip not saved: stopped")
                throw e
            } catch (e: Exception) {
                log("клип $name: ${e.message}")
                lastResult = tr("Не удалось сделать клип", "Couldn't make the clip") + (e.message?.let { ": $it" } ?: "")
            } finally {
                download = null
                message = lastResult
                touch()
            }
        }
        if (local == null) CameraService.start(app)
    }

}
