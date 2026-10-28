package app.goultra.transfer.camera

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class MediaKind { PHOTO, VIDEO }

/**
 * A file on the camera card — or, with [localUri], its copy already in the phone's gallery (the Files tab without the
 * camera). Metadata comes from GetFileInfoList (ExtraMetadata):
 * 7 creation_time (yyyyMMddHHmmss, wrong for LIV), 9 file_size (approximate), 10 total_time (s),
 * 19 {1 width, 2 height}, 20 fps, 24 first_frame_timestamp (µs, camera clock — the base of highlight marks),
 * 212 shooting_param_info {8 color_mode: 1 standard, 2 I-Log, 3 Dolby}.
 */
data class CameraFile(
    val uri: String,
    val size: Long?,
    val durationSec: Int?,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val created: LocalDateTime?,
    val firstFrameUs: Long? = null,
    /** Shot in I-Log (metadata 212.8); null = not known (a file listed from the phone: read from its trailer). */
    val iLog: Boolean? = null,
    /** content:// item of the downloaded copy, for a file listed from the phone. */
    val localUri: String? = null,
) {
    val isOnPhone: Boolean get() = localUri != null
    val name: String get() = uri.substringAfterLast('/')
    val kind: MediaKind get() = if (name.endsWith(".mp4", ignoreCase = true)) MediaKind.VIDEO else MediaKind.PHOTO
    val isLive: Boolean get() = name.startsWith("LIV_")

    /** The live photo's picture (LIV_….jpg) — its clip is LIV_….mp4 of the same name. */
    val isLivePhoto: Boolean get() = isLive && name.endsWith(".jpg", ignoreCase = true)

    /** Base name shared by the files of one shot (LIV_…_074 for .jpg / .mp4 / .dng). */
    val stem: String get() = name.substringBeforeLast('.')

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val NAME_DATE = Regex("""_(\d{8})_(\d{6})_""")

        fun parseStamp(stamp: String): LocalDateTime? = runCatching { LocalDateTime.parse(stamp, STAMP) }.getOrNull()

        /** VID_20261018_132815_001.mp4 → 2026-10-18T13:28:15 */
        fun createdFromName(name: String): LocalDateTime? =
            NAME_DATE.find(name)?.let { parseStamp(it.groupValues[1] + it.groupValues[2]) }

        fun fromMetadata(uri: String, meta: List<PbField>): CameraFile {
            val resolution = meta.field(19)?.data?.pbFields()
            return CameraFile(
                uri = uri,
                size = meta.field(9)?.varint,
                durationSec = meta.field(10)?.varint?.toInt(),
                width = resolution?.field(1)?.varint?.toInt(),
                height = resolution?.field(2)?.varint?.toInt(),
                fps = meta.field(20)?.varint?.toInt(),
                // The name is reliable; creation_time of LIV files decodes to year 5026.
                created = createdFromName(uri.substringAfterLast('/'))
                    ?: meta.field(7)?.varint?.let { parseStamp(it.toString()) },
                firstFrameUs = meta.field(24)?.varint,
                iLog = colorMode(meta)?.let { it == COLOR_MODE_ILOG },
            )
        }

        const val COLOR_MODE_ILOG = 2

        /** ExtraMetadata 212 shooting_param_info → 8 color_mode (1 standard, 2 I-Log, 3 Dolby), or null. */
        fun colorMode(meta: List<PbField>): Int? =
            runCatching { meta.field(212)?.data?.pbFields()?.field(8)?.varint?.toInt() }.getOrNull()
    }
}
