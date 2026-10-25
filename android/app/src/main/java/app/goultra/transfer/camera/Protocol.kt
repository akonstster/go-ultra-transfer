package app.goultra.transfer.camera

/** Message from the camera: response (code 200/400/500…) or notification (code 8xxx). */
class CamMessage(val code: Int, val seq: Int, val body: ByteArray) {
    val isResponse: Boolean get() = code < 1000
}

/** GET_CURRENT_CAPTURE_STATUS → {1: {1: state (0 idle, 1 recording …), 2: seconds}}. */
data class CaptureStatus(val state: Int, val seconds: Int) {
    val recording: Boolean get() = state != 0

    companion object {
        fun parse(body: ByteArray): CaptureStatus {
            val status = body.pbFields().field(1)?.data?.pbFields() ?: emptyList()
            return CaptureStatus(
                status.field(1)?.varint?.toInt() ?: 0,
                status.field(2)?.varint?.toInt() ?: 0,
            )
        }
    }
}

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
