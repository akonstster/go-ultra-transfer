package app.goultra.transfer.camera.osc

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The Open Spherical Camera (OSC) family — Insta360 X3/X4/X5, ONE X2/ONE RS and friends, see
 * `docs/ALPHA-OTHER-CAMERAS.md`. These cameras answer the OSC HTTP API on `http://192.168.42.1` and serve their
 * files over plain HTTP from the same host the GO Ultra uses, so downloads go through the regular
 * [app.goultra.transfer.camera.Downloader] once the phone is on the camera's Wi-Fi.
 */

/** Identity from `/osc/info`. */
data class OscInfo(val model: String, val serial: String, val firmware: String) {
    val label: String get() = model.ifBlank { "Insta360" }

    /** The serial with its middle masked — safe to paste into a report. */
    val maskedSerial: String get() = if (serial.length > 6) serial.take(4) + "****" + serial.takeLast(2) else "****"
}

/** Battery and card as `/osc/state` reports them. */
data class OscState(val batteryLevel: Double?, val storageUri: String?, val cardState: String?)

/** One file as `camera.listFiles` reports it. */
data class OscFile(
    val name: String,
    val path: String,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    val takenAtMillis: Long?,
    val url: String?,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = extension in VIDEO_EXTENSIONS
    val is360: Boolean get() = extension == "insv" || extension == "insp"
    val stem: String get() = name.substringBeforeLast('.')

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "mkv", "webm", "insv")

        /** The hidden low-resolution copy the camera keeps next to a video — never an item of its own. */
        fun isPreview(name: String): Boolean =
            name.startsWith("LRV_", ignoreCase = true) || name.endsWith(".lrv", ignoreCase = true)
    }
}

/** One shot: a single file, or the two `.insv` halves of a 360 video. */
data class OscShot(val files: List<OscFile>) {
    val name: String get() = files.firstOrNull()?.name ?: "?"
    val is360: Boolean get() = files.any { it.is360 }
    val isVideo: Boolean get() = files.firstOrNull()?.isVideo == true
    val totalSize: Long? get() = if (files.all { it.size != null }) files.sumOf { it.size ?: 0L } else null
    val takenAtMillis: Long? get() = files.mapNotNull { it.takenAtMillis }.minOrNull()
}

/** Parsing of `/osc/info`, `/osc/state` and `camera.listFiles` answers (shapes from the official Insta360 OSC docs). */
object OscParsing {
    private val DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ssXXX")

    /** `VID_…_00_001` / `VID_…_10_001` — the two halves of one 360 shot share this key. */
    private val INSV_PAIR = Regex("""^(.*)_(00|10)_(\d+)$""", RegexOption.IGNORE_CASE)

    fun info(json: JSONObject): OscInfo = OscInfo(
        model = json.optString("model"),
        serial = json.optString("serialNumber"),
        firmware = json.optString("firmwareVersion"),
    )

    fun state(json: JSONObject): OscState {
        val s = json.optJSONObject("state")
        return OscState(
            batteryLevel = s?.optDouble("batteryLevel")?.takeIf { !it.isNaN() },
            storageUri = s?.optString("storageUri")?.takeIf { it.isNotEmpty() },
            cardState = s?.optString("_cardState")?.takeIf { it.isNotEmpty() },
        )
    }

    /** The entries of a `camera.listFiles` answer (empty on an unexpected shape). */
    fun files(json: JSONObject): List<OscFile> {
        val entries = json.optJSONObject("results")?.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i -> entries.optJSONObject(i)?.let(::file) }
    }

    /** `totalEntries` of a `camera.listFiles` answer, when the camera reports one. */
    fun totalEntries(json: JSONObject): Int? =
        json.optJSONObject("results")?.optInt("totalEntries")?.takeIf { it > 0 }

    fun file(json: JSONObject): OscFile? {
        val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val path = json.optString("_localFileUrl").takeIf { it.isNotEmpty() }
            ?: json.optString("fileUrl").substringAfter("192.168.42.1").takeIf { it.startsWith("/") }
            ?: return null
        return OscFile(
            name = name,
            path = path,
            size = json.optLong("size").takeIf { json.has("size") },
            width = json.optInt("width").takeIf { json.has("width") },
            height = json.optInt("height").takeIf { json.has("height") },
            takenAtMillis = takenAt(json.optString("dateTimeZone")),
            url = json.optString("fileUrl").takeIf { it.isNotEmpty() },
        )
    }

    /** `2018:01:06 18:02:14+08:00` → epoch millis (null when absent or oddly formatted). */
    fun takenAt(value: String?): Long? = value?.takeIf { it.isNotBlank() }
        ?.let { runCatching { OffsetDateTime.parse(it, DATE).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * Groups the camera's flat list into shots: the `_00_`/`_10_` halves of a 360 video become one item, everything
     * else stands alone, and the order of the list is kept (a shot appears where its first half does).
     */
    fun groupIntoShots(files: List<OscFile>): List<OscShot> {
        val cleaned = files.filterNot { OscFile.isPreview(it.name) }
        val pairs = cleaned.groupBy { pairKey(it) }.filterKeys { it != null }
        val emitted = mutableSetOf<String>()
        val out = mutableListOf<OscShot>()
        for (f in cleaned) {
            val key = pairKey(f)
            if (key == null) {
                out += OscShot(listOf(f))
                continue
            }
            if (emitted.add(key)) out += OscShot(pairs.getValue(key).sortedBy { it.name })
        }
        return out
    }

    /** The pair key of an `.insv` half (`VID_…_00_001.insv` → `VID_…_001`), or null for anything else. */
    fun pairKey(file: OscFile): String? {
        if (file.extension != "insv") return null
        val m = INSV_PAIR.matchEntire(file.stem) ?: return null
        return m.groupValues[1] + "_" + m.groupValues[3]
    }
}
