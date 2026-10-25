package app.goultra.transfer.camera

import app.goultra.transfer.ui.tr
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.random.Random

/**
 * Whole connection chain in one place:
 * BLE scan → GATT (+bond) → wake if asleep → authorization → Wi-Fi credentials → camera Wi-Fi → TCP 6666.
 * Files are listed over TCP and downloaded over HTTP through the camera network.
 */
class CameraSession(private val context: Context, private val log: (String) -> Unit) {
    companion object {
        /** Android shows the BLE pairing request as a notification that expires in ~30 s. */
        /** This phone's id for CHECK_AUTHORIZATION / wake, generated once («gut-» + 12 hex). Shared with diagnostics. */
        fun authId(context: Context): String {
            val prefs = context.getSharedPreferences("camera", 0)
            return prefs.getString("auth_id", null)
                ?: ("gut-" + Random.nextBytes(6).joinToString("") { "%02x".format(it) })
                    .also { prefs.edit().putString("auth_id", it).apply() }
        }

        val PAIRING_HINT
            get() = tr(
                "Откройте шторку уведомлений и нажмите\n«Запрос на сопряжение» от камеры (30 с)",
                "Open the notification shade and tap\nthe camera's «Pairing request» (30 s)",
            )

        /** A camera woken over BLE keeps its screen off, so the request is invisible until the user turns it on. */
        val AUTH_HINT
            get() = tr(
                "Камера просит разрешить этот телефон.\n" +
                    "Включите экран камеры (нажмите кнопку на ней) и подтвердите подключение — ждём 60 с",
                "The camera asks to allow this phone.\n" +
                    "Wake the camera's screen (press its button) and confirm the connection — waiting 60 s",
            )
    }

    /** Called when the Wi-Fi or TCP link drops on its own (not after [disconnect]). */
    var onLost: (() -> Unit)? = null

    private val ble = CameraBle(context, log)
    private val wifi = CameraWifi(context, log) { linkLost() }
    private val tcpLock = Mutex()
    @Volatile private var tcp: CameraTcp? = null
    @Volatile private var closing = false

    /** Authorization id the camera remembers for this install (also used to wake it). */
    val authId: String by lazy { authId(context) }

    private val link: CameraLink get() = wifi.link ?: error(tr("нет подключения к Wi-Fi камеры", "no Wi-Fi link to the camera"))

    /**
     * First-run setup with the camera switched on: BLE bond (lets us wake it later) + authorization of this phone.
     * Returns null on success, otherwise a message for the user.
     */
    suspend fun setup(step: (String) -> Unit): String? {
        disconnect()
        closing = false
        step(tr("Поиск камеры…", "Looking for the camera…"))
        val found = ble.scan() ?: return tr("Камера не найдена. Включите её кнопкой и положите рядом с телефоном.", "Camera not found. Switch it on with its button and put it next to the phone.")
        step(tr("Подключение по Bluetooth…", "Connecting over Bluetooth…"))
        if (!ble.connect(found.device) { step(PAIRING_HINT) }) return tr("Не удалось подключиться по Bluetooth", "Couldn't connect over Bluetooth")
        if (!ble.isBonded) {
            ble.disconnect()
            return tr("Сопряжение не подтверждено. Нажмите «Повторить» и сразу откройте шторку уведомлений.", "Pairing wasn't confirmed. Tap «Try again» and open the notification shade right away.")
        }
        if (!ble.waitSync(2_500) && !ble.wake(authId)) return tr("Камера не отвечает", "The camera doesn't respond")
        step(tr("Авторизация…", "Authorizing…"))
        val ok = ble.authorize(authId) { step(AUTH_HINT) }
        ble.disconnect()
        return if (ok) null else tr("Камера не подтвердила подключение", "The camera didn't confirm the connection")
    }

    @Volatile private var authorized = false

    /** BLE control channel is up: recording commands work without Wi-Fi. */
    val hasControl: Boolean get() = authorized && ble.isReady

    /**
     * BLE only — find, wake, authorize. Enough to start/stop recording, takes a few seconds, no Wi-Fi.
     * Returns null on success, otherwise a message for the user. [step] reports progress.
     */
    suspend fun connectControl(step: (String) -> Unit): String? {
        if (hasControl) return null
        // Only the BLE side: a running Wi-Fi link (download, live view) survives a BLE reconnect.
        authorized = false
        ble.disconnect()
        step(tr("Поиск камеры…", "Looking for the camera…"))
        val found = ble.scan() ?: return tr("Камера не найдена. Она рядом и заряжена?", "Camera not found. Is it nearby and charged?")
        step(tr("Подключение по Bluetooth…", "Connecting over Bluetooth…"))
        if (!ble.connect(found.device) { step(PAIRING_HINT) }) {
            return tr(
                "Не удалось подключиться по Bluetooth. Если камера выключена, включите её кнопкой " +
                    "или пройдите настройку заново (меню ⋮).",
                "Couldn't connect over Bluetooth. If the camera is off, switch it on with its button " +
                    "or set it up again (menu ⋮).",
            )
        }
        if (!ble.waitSync(2_500)) {
            step(tr("Пробуждение камеры…", "Waking the camera…"))
            if (!ble.wake(authId)) return tr("Камера не проснулась", "The camera didn't wake up")
        }
        step(tr("Авторизация…", "Authorizing…"))
        if (!ble.authorize(authId) { step(AUTH_HINT) }) {
            return tr("Камера не подтвердила подключение", "The camera didn't confirm the connection")
        }
        authorized = true
        return null
    }

    /** Full connection: BLE control + camera Wi-Fi + TCP 6666 (files, live view). Keeps an existing BLE link. */
    suspend fun connect(step: (String) -> Unit): String? {
        connectControl(step)?.let { return it }
        closeWifi()
        closing = false
        val creds = ble.wifiCredentials() ?: return tr("Не удалось получить пароль Wi-Fi камеры", "Couldn't get the camera's Wi-Fi password")
        step(tr("Подключение к Wi-Fi камеры…\nподтвердите системный запрос, если появится", "Connecting to the camera's Wi-Fi…\nconfirm the system prompt if it appears"))
        val net = wifi.connect(creds) ?: return tr("Не удалось подключиться к Wi-Fi камеры", "Couldn't connect to the camera's Wi-Fi")
        step(tr("Связь с камерой…", "Talking to the camera…"))
        val t = CameraTcp(net, log) { linkLost() }
        val opened = try {
            t.open()
        } catch (e: java.io.IOException) {
            t.close()
            // A VPN app keeps other apps' traffic out of non-VPN networks: binding fails with EPERM.
            return if (e.message?.contains("EPERM") == true) {
                tr("Нет доступа к сети камеры. Если включён VPN — отключите его и нажмите «Подключиться».", "No access to the camera's network. If a VPN is on, turn it off and tap «Connect».")
            } else {
                tr("Камера не отвечает по Wi-Fi: ", "The camera doesn't respond over Wi-Fi: ") + e.message
            }
        }
        if (!opened) {
            t.close()
            return tr("Камера не отвечает по Wi-Fi", "The camera doesn't respond over Wi-Fi")
        }
        tcp = t
        return null
    }

    private fun linkLost() {
        if (!closing) onLost?.invoke()
    }

    /** The camera Wi-Fi link while connected (the in-app player streams through it). */
    val wifiLink: CameraLink? get() = wifi.link

    /**
     * What to play for a quick look: the low-res LRV copy the camera keeps next to a video (a few MB/min instead of
     * hundreds), else the file itself. Photos and RAW: the full-size JPEG of the shot.
     */
    fun previewUri(file: CameraFile): String {
        val dir = file.uri.substringBeforeLast('/')
        val base = file.name.substringBeforeLast('.')
        return when {
            file.kind == MediaKind.VIDEO -> {
                val rest = base.substringAfter('_')
                listOf("$dir/LRV_$rest.lrv", "$dir/$base.lrv").firstOrNull { it in allUris } ?: file.uri
            }
            file.name.endsWith(".dng", ignoreCase = true) -> "$dir/$base.jpg"
            else -> file.uri
        }
    }

    /** Whole file from the camera (preview of a photo, a few MB). */
    suspend fun fetchFile(uri: String): ByteArray = Downloader.fetch(link, uri)

    /** Size of a file the listing reported without one (HEAD request over Wi-Fi). */
    suspend fun fileSize(file: CameraFile): Long? = Downloader.contentLength(link, file.uri)

    /** Every uri of the last listing, LRV previews included (they are deleted along with their video). */
    @Volatile private var allUris: List<String> = emptyList()

    /**
     * DELETE_FILES (12) `{1: repeated uri}` → `{1: repeated fail_uri}` (RigacciOrg pb2). A video takes its hidden
     * LRV preview along. Returns the uris the camera could not delete, or null if the request failed.
     * Never sends an empty list or anything outside /DCIM/.
     */
    suspend fun deleteFiles(files: List<CameraFile>): List<String>? = tcpLock.withLock {
        val t = tcp ?: return null
        val uris = files.map { it.uri }.filter { it.startsWith("/DCIM/") }.toMutableList()
        for (f in files.filter { it.kind == MediaKind.VIDEO }) {
            val dir = f.uri.substringBeforeLast('/')
            val rest = f.name.substringAfter('_').substringBeforeLast('.') // 20261025_074434_001
            val prefix = f.name.substringBefore('_') // VID / LIV
            listOf("$dir/LRV_$rest.lrv", "$dir/${prefix}_$rest.lrv").filter { it in allUris }.forEach(uris::add)
        }
        require(uris.isNotEmpty()) { "nothing to delete" }
        val body = PbWriter().apply { uris.distinct().forEach { string(1, it) } }.toByteArray()
        log("удаление: ${uris.distinct()}")
        val r = t.request(Proto.CMD_DELETE_FILES, body, 30_000) ?: return null
        if (r.code != 200) {
            log("DELETE_FILES: код ${r.code}")
            return null
        }
        r.body.pbFields().filter { it.number == 1 }.mapNotNull { it.string }
    }

    /** Files on the card, newest first, LRV previews excluded. */
    suspend fun files(): List<CameraFile>? = tcpLock.withLock {
        val t = tcp ?: return null
        val uris = t.fileList() ?: return null
        allUris = uris
        val meta = t.fileInfoList() ?: emptyMap()
        uris.filterNot { it.endsWith(".lrv", ignoreCase = true) }
            .map { uri ->
                meta[uri]?.let { CameraFile.fromMetadata(uri, it) }
                    ?: CameraFile(uri, null, null, null, null, null, CameraFile.createdFromName(uri.substringAfterLast('/')))
            }
            .sortedWith(compareByDescending<CameraFile> { it.created }.thenByDescending { it.name })
    }

    /**
     * Photo: EXIF thumbnail via a Range request. Live-photo video and RAW (.dng, no usable preview in its head):
     * EXIF of the .jpg of the same shot (GetFileExtra returns nothing for LIV videos). Other videos: NV12 frame.
     */
    suspend fun thumbnail(file: CameraFile): Bitmap? {
        val exifSource = when {
            file.isLive || file.name.endsWith(".dng", ignoreCase = true) -> file.uri.substringBeforeLast('.') + ".jpg"
            file.kind == MediaKind.PHOTO -> file.uri
            else -> null
        }
        if (exifSource != null) {
            val head = Downloader.fetchRange(link, exifSource, 0, Thumbnails.EXIF_HEAD_BYTES - 1L)
            head?.let(Thumbnails::fromExif)?.let { return it }
        }
        val raw = tcpLock.withLock { tcp?.fileThumbnail(file.uri) } ?: return null
        return Thumbnails.fromNv12(raw)
    }

    suspend fun download(file: CameraFile, overwrite: Boolean, onProgress: (done: Long, total: Long) -> Unit): Uri =
        Downloader.download(
            context, link, file.uri, overwrite,
            takenAtMillis = file.created?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            onProgress = onProgress,
        )

    // ---------- recording (BLE) ----------

    suspend fun startRecording(): Boolean = ble.request(Proto.CMD_START_CAPTURE)?.code == 200

    suspend fun stopRecording(): Boolean = ble.request(Proto.CMD_STOP_CAPTURE, timeoutMs = 8_000)?.code == 200

    suspend fun captureStatus(): CaptureStatus? =
        ble.request(Proto.CMD_GET_CURRENT_CAPTURE_STATUS, timeoutMs = 3_000, quiet = true)
            ?.takeIf { it.code == 200 }
            ?.let { CaptureStatus.parse(it.body) }

    // ---------- shooting settings (BLE) ----------

    /** Current mode, settings and battery levels; [functionMode] null = the mode the camera reports. */
    suspend fun readSettings(functionMode: Int? = null): CamSettings? {
        val optTypes = with(SettingCodes) {
            listOf(1, OPT_BATTERY, OPT_PHOTO_SUB_MODE, OPT_VIDEO_SUB_MODE, OPT_CHARGE_BOX, OPT_BLUR_REDUCTION_TYPE).map { it.toLong() }
        }
        val opt = ble.request(Proto.CMD_GET_OPTIONS, PbWriter().packed(1, optTypes).toByteArray(), quiet = true)
            ?.takeIf { it.code == 200 } ?: return null
        val mode = CamSettings.modeOf(opt.body)?.let { SettingCodes.modes[it] }
        val fm = functionMode ?: mode?.functionMode ?: return CamSettings.parse(opt.body, null)
        // Every type listed here is answered by GO Ultra (an unknown one would make the whole request fail with 400).
        val poTypes = with(SettingCodes) {
            listOf(SHARPNESS, EXPOSURE_BIAS, FILTER, STILL_EXPOSURE, VIDEO_EXPOSURE, ISO_TOP_LIMIT, RAW_CAPTURE, SELF_TIMER,
                DURATION, RESOLUTION, FOV, WHITE_BALANCE, PHOTO_RESOLUTION, DARK_EIS, BURST_COUNT, BURST_CACHE_FRAMES, METERING, BURST_SECONDS, BURST_CACHE, ZOOM_SCALE,
                DURATION_2, FLOWSTATE, EXPORT_TYPE, DAMAGE_ZOOM, HDR, HORIZON_LOCK, I_LOG, LIVE_PHOTO, BEAUTY).map { it.toLong() }
        }
        val po = ble.request(
            Proto.CMD_GET_PHOTOGRAPHY_OPTIONS,
            PbWriter().packed(1, poTypes).varint(2, fm.toLong()).toByteArray(),
            quiet = true,
        )?.takeIf { it.code == 200 }
        val lapse = mode?.timelapseMode?.let { tm ->
            ble.request(Proto.CMD_GET_TIMELAPSE_OPTIONS, PbWriter().varint(1, tm.toLong()).toByteArray(), quiet = true)
                ?.takeIf { it.code == 200 }
                ?.body?.pbFields()?.field(1)?.data?.pbFields()?.field(2)?.varint ?: 0L
        }
        return CamSettings.parse(opt.body, po?.body, lapse)
    }

    /** Debug: raw GET_OPTIONS reply (hex) for exactly these type numbers. */
    suspend fun rawOptions(types: List<Int>): String {
        val r = ble.request(Proto.CMD_GET_OPTIONS, PbWriter().packed(1, types.map { it.toLong() }).toByteArray(), quiet = true)
        return "${r?.code} ${r?.body?.hex(120)}"
    }

    /** Interval / timelapse step: SET_TIMELAPSE_OPTIONS {1: {2: lapseTime ms}, 2: TimelapseMode}. */
    suspend fun writeLapse(timelapseMode: Int, ms: Long): Boolean {
        val body = PbWriter()
            .bytes(1, PbWriter().varint(2, ms).toByteArray())
            .varint(2, timelapseMode.toLong())
            .toByteArray()
        return ble.request(Proto.CMD_SET_TIMELAPSE_OPTIONS, body)?.code == 200
    }

    /** Photo / burst shutter: TAKE_PICTURE (3) with an empty body (the camera shoots in its current photo mode). */
    suspend fun takePicture(): Boolean = ble.request(Proto.CMD_TAKE_PICTURE, timeoutMs = 15_000)?.code == 200

    /**
     * Read-only dump of every Options / PhotographyOptions field (of the camera's current mode), flattened to
     * "o.41" / "p.21.2" → value. Used to map settings: change one thing on the camera, compare two snapshots.
     * Unknown type numbers make the camera answer 400, so types go in blocks of 10, one by one where a block fails.
     */
    suspend fun snapshot(functionModes: List<Int>, maxPhotoType: Int = 100): Map<String, String> {
        val out = sortedMapOf<String, String>()
        suspend fun read(code: Int, extra: ByteArray, prefix: String, maxType: Int) {
            for (start in 1..maxType step 10) {
                val block = (start.toLong() until start + 10L).toList()
                for (group in listOf(block) + block.map { listOf(it) }) {
                    val body = PbWriter().packed(1, group).toByteArray() + extra
                    val r = ble.request(code, body, timeoutMs = 3_000, quiet = true) ?: continue
                    if (r.code != 200) continue
                    r.body.pbFields().field(2)?.data?.let { flattenPb(it, "$prefix.", out) }
                    if (group.size > 1) break
                }
            }
        }
        read(Proto.CMD_GET_OPTIONS, ByteArray(0), "o", 200)
        // PhotographyOptions are per function mode (fm 0 is NOT the current mode — it answers with another one's).
        for (fm in functionModes) {
            read(Proto.CMD_GET_PHOTOGRAPHY_OPTIONS, PbWriter().varint(2, fm.toLong()).toByteArray(), "p$fm", maxPhotoType)
        }
        // Interval / timelapse parameters live apart: GET_TIMELAPSE_OPTIONS {1: TimelapseMode 0..9} → {1: TimelapseOptions}.
        for (mode in 0..9) {
            val r = ble.request(Proto.CMD_GET_TIMELAPSE_OPTIONS, PbWriter().varint(1, mode.toLong()).toByteArray(),
                timeoutMs = 3_000, quiet = true)
            if (r?.code == 200) flattenPb(r.body, "t$mode.", out)
        }
        return out
    }

    private fun flattenPb(data: ByteArray, prefix: String, out: MutableMap<String, String>) {
        for (f in data.pbFields()) {
            val key = "$prefix${f.number}"
            val value = when (f.wireType) {
                0 -> f.varint.toString()
                1 -> java.nio.ByteBuffer.wrap(f.data!!).order(java.nio.ByteOrder.LITTLE_ENDIAN).double.toString()
                2 -> {
                    val bytes = f.data!!
                    val nested = runCatching { bytes.pbFields() }.getOrNull()
                    if (!nested.isNullOrEmpty()) {
                        flattenPb(bytes, "$key.", out)
                        continue
                    }
                    f.string?.takeIf { s -> s.isNotEmpty() && s.all { it.code in 32..126 } } ?: bytes.hex()
                }
                else -> f.data!!.hex()
            }
            out[key] = out[key]?.let { "$it,$value" } ?: value
        }
    }

    /** SET_PHOTOGRAPHY_OPTIONS for [fields] (field → already encoded value bytes of PhotographyOptions). */
    suspend fun writePhotoOptions(functionMode: Int, fields: Map<Int, ByteArray>): Boolean {
        val value = fields.values.fold(ByteArray(0)) { acc, b -> acc + b }
        val body = PbWriter()
            .packed(1, fields.keys.map { it.toLong() })
            .bytes(2, value)
            .varint(3, functionMode.toLong())
            .toByteArray()
        val r = ble.request(Proto.CMD_SET_PHOTOGRAPHY_OPTIONS, body)
        return r?.code == 200
    }

    /** SET_OPTIONS with one varint field (shooting mode 41, blur reduction 134…); [type] differs for 133/134. */
    suspend fun writeOption(field: Int, value: Long, type: Int = field): Boolean {
        val body = PbWriter()
            .packed(1, listOf(type.toLong()))
            .bytes(2, PbWriter().varint(field, value).toByteArray())
            .toByteArray()
        return ble.request(Proto.CMD_SET_OPTIONS, body)?.code == 200
    }

    /** The camera's capability JSON (needs the Wi-Fi link); throws on failure. */
    suspend fun fetchConf(): String = Downloader.fetch(link, "/CONF").toString(Charsets.UTF_8)

    val hasWifi: Boolean get() = tcp != null

    // ---------- live view (TCP 6666) ----------

    /** Starts the preview stream (empty StartLiveStream → 1080p30 H.265); packets go to [onPacket]. */
    suspend fun startLive(onPacket: (ByteArray) -> Unit): Boolean {
        val t = tcp ?: return false
        t.onStream = onPacket
        val r = t.request(Proto.CMD_START_LIVE_STREAM, ByteArray(0))
        log("StartLiveStream: ${r?.code}")
        if (r?.code == 200) t.request(Proto.CMD_IFRAME_REQUEST, ByteArray(0), 3_000)
        return r?.code == 200
    }

    suspend fun stopLive() {
        val t = tcp ?: return
        t.onStream = null
        t.request(Proto.CMD_STOP_LIVE_STREAM, ByteArray(0), 3_000)
    }

    /** Drops Wi-Fi and TCP, keeps the BLE control link (recording keeps working). */
    fun closeWifi() {
        closing = true
        tcp?.close()
        tcp = null
        wifi.release()
    }

    fun disconnect() {
        closeWifi()
        authorized = false
        ble.disconnect()
    }
}
