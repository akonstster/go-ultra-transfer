package app.goultra.transfer.camera

/** Message from the camera: response (code 200/400/500…) or notification (code 8xxx). */
class CamMessage(val code: Int, val seq: Int, val body: ByteArray) {
    val isResponse: Boolean get() = code < 1000
}

/**
 * GET_CURRENT_CAPTURE_STATUS → {1: {1: state (0 idle, 1 recording, 13 TimeShift, 24 Pure Video…), 2: seconds,
 * 10: phase}}; phase as field 7 of the 8208 notification: 0 running, 1 paused, 2 stopping (5 right at the start).
 */
/**
 * GetCurrentCaptureStatus over BLE: {1: CameraCaptureStatus {1 state, 2 seconds, 10 phase}}. The GO Ultra sends only
 * these three — not keyTimePointDetail (field 4), even with marks set (checked 28.10, fw 1.9.29).
 */
data class CaptureStatus(val state: Int, val seconds: Int, val phase: Int = 0) {
    val paused: Boolean get() = recording && phase == 1
    val recording: Boolean get() = state != 0

    companion object {
        const val TIMESHIFT = 13
        /** A Pure Video recording (also when the camera switched to Pure by itself). */
        const val PURE = 24
        fun parse(body: ByteArray): CaptureStatus {
            val status = body.pbFields().field(1)?.data?.pbFields() ?: emptyList()
            return CaptureStatus(
                status.field(1)?.varint?.toInt() ?: 0,
                status.field(2)?.varint?.toInt() ?: 0,
                status.field(10)?.varint?.toInt() ?: 0,
            )
        }
    }
}

/** Options 48 model («Insta360 GO Ultra»), 30 firmware («v1.9.29»), 15 serial number. */
data class CameraInfo(val model: String?, val firmware: String?, val serial: String?)

class WifiCreds(val ssid: String, val password: String, val channel: Int) {
    override fun toString() = "WifiCreds($ssid, channel=$channel, password=***)"
}

object Proto {
    const val CMD_START_LIVE_STREAM = 1
    const val CMD_STOP_LIVE_STREAM = 2
    const val CMD_TAKE_PICTURE = 3
    const val CMD_START_CAPTURE = 4 // empty body! {1: mode} is ignored by GO Ultra
    const val CMD_STOP_CAPTURE = 5
    const val CMD_GET_CURRENT_CAPTURE_STATUS = 15
    const val CMD_IFRAME_REQUEST = 105
    /** UPDATE_LIVE_STREAM_PARAMS {1 encode, 2 width, 3 height, 4 fps}: after StartLiveStream and when the camera restarts its encoder. */
    /** Codes above 152 come from the newer MessageCode table (OpenInstaCine / insta360-nas), checked on GO Ultra:
     * GET_ZOOM 171 answers {1: 100}. Used: 60 SET_KEY_TIME_POINT (highlight mark), 164 PAUSE_RECORDING (toggle). */
    const val CMD_SET_KEY_TIME_POINT = 60
    const val CMD_PAUSE_RECORDING = 164
    const val NOTIFY_LIVE_STREAM_PARAMS = 8234
    const val NOTIFY_CAPTURE_STATUS = 8208
    /** {1: photo_sub_mode, 2: video_sub_mode}: the mode changed (from the app, the camera's menu, or by itself). */
    const val NOTIFY_SUB_MODE = 8275
    const val CMD_SET_OPTIONS = 7
    const val CMD_SET_PHOTOGRAPHY_OPTIONS = 9
    const val CMD_GET_PHOTOGRAPHY_OPTIONS = 10
    const val CMD_GET_OPTIONS = 8
    const val CMD_GET_FILE_EXTRA = 11
    /** {1: repeated uri} → {1: repeated fail_uri}; only exact uris from the file list, never an empty list. */
    const val CMD_DELETE_FILES = 12
    const val CMD_GET_FILE_LIST = 13
    const val CMD_GET_TIMELAPSE_OPTIONS = 17 // {1: TimelapseMode} → {1: {1 duration, 2 lapseTime, 3 output, 4 accel}}
    const val CMD_SET_TIMELAPSE_OPTIONS = 18 // {1: TimelapseOptions, 2: TimelapseMode}
    const val CMD_GET_FILE_INFO_LIST = 38
    const val CMD_OPEN_CAMERA_WIFI = 33
    const val CMD_CHECK_AUTHORIZATION = 39

    const val NOTIFY_AUTHORIZATION_RESULT = 8209
    const val NOTIFY_CAM_WIFI_START = 8215

    /** Sent when the camera's or the Action Pod's power changes (bodies not decoded: 8222 = option 85 again). */
    val NOTIFY_POWER = setOf(8217, 8219, 8222)

    /**
     * Storage notifications (measured on a GO Ultra, fw v1.9.29 — PROTOCOL.md, «Card states and errors»):
     * 8198 `{1: state, 2: 0}`, 8199 STORAGE_FULL, 8201 `{1: reason}` a refused/aborted capture,
     * 8256 `{1: 1}`/`{1: 0}` a recording started/stopped, 8290 `{1: total, 2: free, 3: reserve, 4: 0}`.
     */
    const val NOTIFY_STORAGE_UPDATE = 8198
    const val NOTIFY_STORAGE_FULL = 8199
    const val NOTIFY_CAPTURE_STOPPED = 8201
    const val NOTIFY_RECORD_STATE = 8256
    const val NOTIFY_CARD_INFO = 8290

    /** The card was inserted or removed: seen on the GO Ultra as `{1: 1}`/`{1: 0}` toggles (06.11.2026). */
    const val NOTIFY_MEDIA_CHANGE = 8250

    /** STOP reason (8201 field 1): the camera refused to capture because the card is unusable for writing. */
    const val STOP_REASON_CANNOT_WRITE = 11

    const val OPTION_WIFI_INFO = 36L
    const val MEDIA_VIDEO_AND_PHOTO = 2L
    const val EXTRA_TYPE_THUMBNAIL = 2L

    /** 12-byte header shared by TCP 6666 and BLE: 04 00 00 [code u16] 02 [seq u24] 80 00 00. */
    fun messageHeader(code: Int, seq: Int) = byteArrayOf(
        4, 0, 0, code.toByte(), (code shr 8).toByte(), 2,
        seq.toByte(), (seq shr 8).toByte(), (seq shr 16).toByte(), 0x80.toByte(), 0, 0,
    )

    /** Parses a message whose header starts at [offset]. Over BLE only the low seq byte is meaningful. */
    fun parseMessage(p: ByteArray, offset: Int, seqBytes: Int): CamMessage? {
        if (p.size < offset + 12 || p[offset].toInt() != 4) return null
        var seq = 0
        for (i in 0 until seqBytes) seq = seq or ((p[offset + 6 + i].toInt() and 0xFF) shl (8 * i))
        return CamMessage(u16(p, offset + 3), seq, p.copyOfRange(offset + 12, p.size))
    }
}

/**
 * The camera's own verdict on its card — notification 8198 field 1 (verified 05.11.2026, fw v1.9.29; PROTOCOL.md).
 * The reply to a command is not the truth: an unusable card still answers a photo with a file uri while writing
 * nothing, and 8201 carries only a reason — this state is what the screens must trust.
 */
enum class CardState {
    OK, // 0 — everything is fine
    NO_CARD, // 1
    FULL, // 2 — no room for video: the camera keeps a 10 % reserve it never records into
    UNUSABLE, // 3 — a foreign filesystem/unformatted: the card is not mounted at all
    WRITE_ERROR, // 5 — the card was pulled out mid-recording
    UNKNOWN;

    companion object {
        fun of(code: Int): CardState = when (code) {
            0 -> OK
            1 -> NO_CARD
            2 -> FULL
            3 -> UNUSABLE
            5 -> WRITE_ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * A storage notification: the card's state and/or its sizes, or why a capture was refused.
 * 8198 `{1: state, 2: 0}` · 8290 `{1: total, 2: free, 3: reserve}` · 8201 `{1: reason}`.
 */
data class StorageEvent(
    val state: CardState? = null,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val reserveBytes: Long? = null,
    val stopReason: Int? = null,
    /** The card was inserted/removed (8250): the camera re-scans it and the file list is worth reloading. */
    val mediaChanged: Boolean = false,
) {
    companion object {
        fun parse(code: Int, body: ByteArray): StorageEvent? {
            if (code == Proto.NOTIFY_MEDIA_CHANGE) return StorageEvent(mediaChanged = true)
            val f = body.pbFields()
            return when (code) {
                Proto.NOTIFY_STORAGE_UPDATE -> StorageEvent(state = CardState.of(f.field(1)?.varint?.toInt() ?: 0))
                Proto.NOTIFY_CAPTURE_STOPPED -> StorageEvent(stopReason = f.field(1)?.varint?.toInt())
                Proto.NOTIFY_CARD_INFO -> StorageEvent(
                    totalBytes = f.field(1)?.varint,
                    freeBytes = f.field(2)?.varint,
                    reserveBytes = f.field(3)?.varint,
                )
                else -> null
            }
        }
    }
}

/**
 * What the camera put in a reply that should have carried a file uri — its own words («No SD card») — or null for a
 * real uri. A photo refusal comes as code 200 with `{3: {1: "<text>"}}`, so the code alone says nothing.
 */
fun replyRefusal(body: ByteArray): String? =
    body.pbFields().field(3)?.data?.pbFields()?.field(1)?.string?.takeIf { it.isNotEmpty() && !it.startsWith("/") }

object Crc16 {
    /** CRC-16/MODBUS: init 0xFFFF, reflected poly 0xA001. */
    fun modbus(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}

/**
 * BLE packet (Go2BlePacket): FF [type] [subtype] [size u16 LE] [inner] [CRC16/MODBUS LE over all preceding bytes].
 * Phone sends type 0x07, camera answers with 0x06. Subtype 0x40 = message, 0x41 = sync.
 * Message inner = [u32 LE 16+len] + 12-byte message header + protobuf body.
 */
object BleFrame {
    fun build(type: Int, subtype: Int, inner: ByteArray): ByteArray {
        val out = ByteArray(inner.size + 7)
        out[0] = 0xFF.toByte()
        out[1] = type.toByte()
        out[2] = subtype.toByte()
        out[3] = inner.size.toByte()
        out[4] = (inner.size shr 8).toByte()
        inner.copyInto(out, 5)
        val crc = Crc16.modbus(out, 0, inner.size + 5)
        out[inner.size + 5] = crc.toByte()
        out[inner.size + 6] = (crc shr 8).toByte()
        return out
    }

    fun command(code: Int, seq: Int, body: ByteArray): ByteArray =
        build(0x07, 0x40, le32(16 + body.size) + Proto.messageHeader(code, seq) + body)

    fun syncReply(): ByteArray = build(0x07, 0x41, ByteArray(7))

    /** Wakes a powered-off camera (handled by its BLE controller): FF 0C 02 [len] [authorization id]. */
    fun wake(authId: String): ByteArray = build(0x0C, 0x02, authId.toByteArray(Charsets.US_ASCII))
}
