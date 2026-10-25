package app.goultra.transfer.camera

import org.json.JSONArray
import org.json.JSONObject

/**
 * The camera's own capability description, downloaded from http://192.168.42.1/CONF:
 * mode → parameter → allowed values, where a parameter's list depends on other parameters
 * (`"depend_on": [...]`, list keys are their current values joined with `|`, or `default`).
 */
class CameraConf(json: String) {
    class Param(val dependOn: List<String>, val lists: Map<String, List<String>>)

    val modes: Map<String, Map<String, Param>>
    private val supportAttrs: Map<String, Set<String>>

    init {
        val root = JSONObject(json)
        val lens = root.getJSONObject(root.keys().next())
        val modes = mutableMapOf<String, Map<String, Param>>()
        val support = mutableMapOf<String, Set<String>>()
        for (mode in lens.keys()) {
            val m = lens.optJSONObject(mode) ?: continue
            val params = mutableMapOf<String, Param>()
            for (name in m.keys()) {
                val p = m.get(name)
                if (name == "support_attrs" && p is JSONArray) {
                    support[mode] = (0 until p.length()).map { p.getString(it) }.toSet()
                } else if (p is JSONObject && p.has("list")) {
                    val dep = p.optJSONArray("depend_on")
                    val list = p.getJSONObject("list")
                    params[name] = Param(
                        dependOn = if (dep == null) emptyList() else (0 until dep.length()).map { dep.getString(it) },
                        lists = list.keys().asSequence().associateWith { key ->
                            val arr = list.getJSONArray(key)
                            (0 until arr.length()).map { arr.getString(it) }
                        },
                    )
                }
            }
            modes[mode] = params
        }
        this.modes = modes
        this.supportAttrs = support
    }

    fun supports(mode: String, param: String): Boolean =
        supportAttrs[mode]?.contains(param) == true && modes[mode]?.containsKey(param) == true

    /**
     * HDR works only at the resolutions listed in the separate `hdr_support` parameter (not via depend_on):
     * 4K60, 2.7K/1080p above 30 fps are missing there — the camera rejects a SET of such a resolution with HDR on.
     */
    fun hdrPossible(mode: String, resolution: String?): Boolean {
        val list = allowed(mode, "hdr_support", emptyMap())
        return list.isEmpty() || resolution == null || resolution in list
    }

    /** Values allowed for [param] given the current values of the parameters it depends on. */
    fun allowed(mode: String, param: String, current: Map<String, String>): List<String> {
        val p = modes[mode]?.get(param) ?: return emptyList()
        val key = p.dependOn.joinToString("|") { current[it] ?: DEFAULTS[it].orEmpty() }
        return p.lists[key] ?: p.lists["default"] ?: emptyList()
    }

    private companion object {
        /** Dependencies the app doesn't read from the camera. */
        val DEFAULTS = mapOf(
            "lens_accessory_type" to "STANDARD",
            "exposure_program" to "FULL_AUTO",
            "self_portrait_switch_2" to "false", // selfie mode (beauty filters) is not read; off is the normal case
        )
    }
}

/**
 * Settings mapped on a real GO Ultra (CLAUDE.md, «Карта настроек»): CONF names ↔ protocol field values.
 * PhotographyOptions fields are written with SET_PHOTOGRAPHY_OPTIONS for the mode's function mode,
 * plain Options (blur reduction, shooting mode) with SET_OPTIONS.
 */
object SettingCodes {
    // PhotographyOptions fields
    const val SHARPNESS = 6
    const val EXPOSURE_BIAS = 7
    const val FILTER = 18
    const val STILL_EXPOSURE = 20 // {1: program, 2: ISO (0 auto), 3: shutter s (double, 0 auto)}
    const val VIDEO_EXPOSURE = 21 // same, the camera keeps both equal
    const val ISO_TOP_LIMIT = 24 // max ISO while ISO is auto (0 = none)
    const val RAW_CAPTURE = 25
    const val SELF_TIMER = 26
    const val DURATION = 29 // written together with DURATION_2
    const val RESOLUTION = 31
    const val FOV = 33
    const val WHITE_BALANCE = 39
    const val PHOTO_RESOLUTION = 40
    const val DARK_EIS = 42 // blur reduction, written together with option 134
    const val BURST_COUNT = 44
    const val BURST_CACHE_FRAMES = 45
    const val METERING = 46
    const val BURST_SECONDS = 51
    const val BURST_CACHE = 52
    const val ZOOM_SCALE = 53
    const val DURATION_2 = 54
    const val FLOWSTATE = 55
    const val EXPORT_TYPE = 56
    const val DAMAGE_ZOOM = 59
    const val HDR = 63
    const val HORIZON_LOCK = 70
    const val I_LOG = 72
    const val LIVE_PHOTO = 85 // {1: 0, 2: 1 off / 2 on}
    const val BEAUTY = 90 // portrait mode {1: «Осветить» float 0/1, 2: «Сгладить» float 0/1}; the camera adds 89 itself

    /** CONF self_portrait_switch_2 value → (brighten, smooth). */
    val portrait = mapOf(
        "false" to (false to false),
        "self_portrait_whitening_switch" to (true to false),
        "self_portrait_dermabrasion_switch" to (false to true),
        "self_portrait_derm_white" to (true to true),
    )

    // Options fields
    const val OPT_BATTERY = 11 // {1: power source (1 = external), 2: %}
    const val OPT_PHOTO_SUB_MODE = 40
    const val OPT_VIDEO_SUB_MODE = 41
    const val OPT_CHARGE_BOX = 85 // {1: Pod attached, 2: {1: charging, 2: %}}
    const val OPT_BLUR_REDUCTION = 134
    /** GO Ultra quirk: Options field 134 is requested (and set) as type 133, and type 134 answers with field 133. */
    const val OPT_BLUR_REDUCTION_TYPE = 133
    /** Sub-mode value meaning «the other kind is active» (video_sub_mode = 100 while in a photo mode and vice versa). */
    const val SUB_MODE_NONE = 100

    /**
     * Shooting modes (all verified on the camera, CLAUDE.md): selected with SET_OPTIONS 41 (video) or 40 (photo),
     * settings live in PhotographyOptions of [functionMode]; [timelapseMode] — GET/SET_TIMELAPSE_OPTIONS for lapse_time.
     */
    class Mode(val videoSub: Int?, val photoSub: Int?, val functionMode: Int, val timelapseMode: Int? = null) {
        val photo: Boolean get() = photoSub != null
        /** Single shots: the shutter takes a picture instead of starting a recording. */
        val takesPicture: Boolean get() = photoSub == 0 || photoSub == 3
    }

    val modes = linkedMapOf(
        "VIDEO_NORMAL" to Mode(0, null, 7),
        "VIDEO_PURE" to Mode(11, null, 27),
        "VIDEO_SLOW_MOTION" to Mode(9, null, 21),
        "VIDEO_TIMELAPSE" to Mode(2, null, 2, timelapseMode = 1),
        "VIDEO_TIMESHIFT" to Mode(4, null, 12),
        "VIDEO_LOOPRECORDING" to Mode(6, null, 17),
        "VIDEO_SUPER" to Mode(5, null, 16),
        "PHOTO_SINGLE" to Mode(null, 0, 6),
        "PHOTO_BURST" to Mode(null, 3, 5),
        "PHOTO_INTERVAL" to Mode(null, 2, 3, timelapseMode = 2),
        "PHOTO_STARLAPSE" to Mode(null, 7, 18),
    )

    fun modeOf(videoSub: Int, photoSub: Int): String? = modes.entries.firstOrNull { (_, m) ->
        if (videoSub != SUB_MODE_NONE) m.videoSub == videoSub else m.photoSub == photoSub
    }?.key

    val resolutions = mapOf(
        "1920_1080_24" to 53, "1920_1080_25" to 52, "1920_1080_30" to 29, "1920_1080_50" to 81, "1920_1080_60" to 40,
        "1920_1080_100" to 150, "1920_1080_120" to 28, "1920_1080_200" to 26, "1920_1080_240" to 27,
        "1920_1440_24" to 59, "1920_1440_25" to 58, "1920_1440_30" to 42,
        "2720_1530_24" to 51, "2720_1530_25" to 50, "2720_1530_30" to 39, "2720_1530_50" to 100, "2720_1530_60" to 38,
        "2720_1530_100" to 25, "2720_1530_120" to 217,
        "2720_2040_24" to 57, "2720_2040_25" to 56, "2720_2040_30" to 41,
        "3840_2160_24" to 49, "3840_2160_25" to 48, "3840_2160_30" to 24, "3840_2160_50" to 92, "3840_2160_60" to 23,
        "4000_3000_24" to 55, "4000_3000_25" to 54, "4000_3000_30" to 31,
        // 2.35:1, read from the camera (24.10)
        "4096_1744_24" to 346, "4096_1744_25" to 345, "4096_1744_30" to 344, "4096_1744_48" to 343,
        "4096_1744_50" to 342, "4096_1744_60" to 341,
        "3840_2160_48" to 258, "2720_1530_48" to 259, "1920_1080_48" to 260,
    )
    val fov = mapOf(
        "LINEAR" to 1, "ULTRAWIDE" to 2, "ACTION_VIEW" to 4, "LINEARHORIZON_45" to 6, "LINEARHORIZON_360" to 11,
        "DEWARP" to 13, "MEGA_VIEW" to 14,
    )
    val flowstate = mapOf(
        "FLOW_STATE_LEVEL_CV5_CLOSE" to 0, "FLOW_STATE_LEVEL_CV5_SLIGHT" to 1,
        "FLOW_STATE_LEVEL_CV5_GENERAL" to 2, "FLOW_STATE_LEVEL_CV5_STRONG" to 3,
    )
    val sharpness = mapOf("LOW" to 0, "MEDIUM" to 1, "HIGH" to 2)
    /** CCD filters (40+) also make the camera add fields 89 = [1, 1] and 90.2 = [1.0, 1.0]; it clears them itself. */
    val filter = mapOf(
        "STANDARD" to 0, "VIVID" to 2, "VINTAGE" to 17, "URBAN" to 18, "FILM" to 19, "NIGHT" to 20, "GLOW" to 22,
        "SELFIE" to 23, "FROSTEDCCD" to 40, "AMBERCCD" to 41, "BLUECCD" to 42,
    )
    val metering = mapOf("EVALUATIVE" to 0, "FACE_PRIORITY" to 1)
    /**
     * Exposure program. The camera menu has only Auto (FULL_AUTO 5) and Manual; in Manual it stores
     * ISO + shutter → MANUAL 3, ISO with auto shutter → ISO_PRIORITY 1, auto ISO with shutter → SHUTTER_PRIORITY 2.
     */
    val exposurePrograms = mapOf("AUTO" to 0, "ISO_PRIORITY" to 1, "SHUTTER_PRIORITY" to 2, "MANUAL" to 3, "FULL_AUTO" to 5)

    fun programFor(iso: Long, shutter: Double): String = when {
        iso > 0 && shutter > 0 -> "MANUAL"
        iso > 0 -> "ISO_PRIORITY"
        shutter > 0 -> "SHUTTER_PRIORITY"
        else -> "FULL_AUTO"
    }

    /** CONF shutter value ("1/100", "1/1.25", "2.5", "0" = auto) → seconds, and back. */
    fun shutterSeconds(value: String): Double = if (value.startsWith("1/")) {
        value.removePrefix("1/").toDoubleOrNull()?.let { 1.0 / it } ?: 0.0
    } else {
        value.toDoubleOrNull() ?: 0.0
    }

    fun shutterName(seconds: Double): String {
        fun trim(d: Double) = "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
        return when {
            seconds <= 0 -> "0"
            seconds >= 1 -> trim(seconds)
            else -> "1/" + trim(1.0 / seconds)
        }
    }

    val photoResolutions = mapOf("8192_6144" to 17, "8192_4608" to 18, "4096_3072" to 19, "4000_2250" to 4)
    val rawCapture = mapOf("PURESHOT" to 3, "PURESHOT_RAW" to 4)
    val exportType = mapOf("STARLAPSE_VIDEO" to 0, "STAR_TRAILS_VIDEO" to 1, "STAR_TRAILS_PHOTO" to 2)

    /** Burst «shots/seconds» → cached frames the camera itself stores in field 45 (with the cache on; off → 0). */
    val burstCacheFrames = mapOf(
        "3/1" to 1, "5/1" to 1, "10/1" to 3, "15/1" to 5, "30/1" to 10,
        "10/3" to 1, "30/3" to 1, "30/6" to 1, "30/10" to 1,
    )

    /** CONF value ("-0.7") → tenths of EV, and back ("+0.7"). */
    fun evToTenths(value: String): Int = Math.round(value.toDouble() * 10).toInt()

    fun tenthsToEv(tenths: Int): String = if (tenths == 0) "0" else "%+.1f".format(tenths / 10.0).replace(',', '.')

    fun zigzag(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL

    fun unzigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()
}

/** Current shooting settings as CONF names (what the UI and CONF lookups work with). */
data class CamSettings(
    val mode: String?,
    val videoSub: Int,
    val photoSub: Int,
    val values: Map<String, String>,
    val batteryPercent: Int?,
    val podBatteryPercent: Int?,
    val charging: Boolean = false,
    val podCharging: Boolean = false,
) {
    companion object {
        fun modeOf(optionsBody: ByteArray?): String? {
            val opt = optionsBody?.pbFields()?.field(2)?.data?.pbFields() ?: emptyList()
            val videoSub = opt.field(SettingCodes.OPT_VIDEO_SUB_MODE)?.varint?.toInt() ?: 0
            return SettingCodes.modeOf(videoSub, photoSubOf(opt, videoSub))
        }

        /**
         * GO Ultra sends zero sub-modes explicitly, but a strict proto3 encoder would omit them:
         * in a photo mode (video = 100) a missing photo_sub_mode means 0 = single photo.
         */
        private fun photoSubOf(opt: List<PbField>, videoSub: Int): Int =
            opt.field(SettingCodes.OPT_PHOTO_SUB_MODE)?.varint?.toInt()
                ?: if (videoSub == SettingCodes.SUB_MODE_NONE) 0 else SettingCodes.SUB_MODE_NONE

        /**
         * Builds from GET_OPTIONS / GET_PHOTOGRAPHY_OPTIONS bodies (field 2 = the message);
         * [lapseMs] — lapseTime from GET_TIMELAPSE_OPTIONS for interval / timelapse modes.
         */
        fun parse(optionsBody: ByteArray?, photoBody: ByteArray?, lapseMs: Long? = null): CamSettings {
            val opt = optionsBody?.pbFields()?.field(2)?.data?.pbFields() ?: emptyList()
            val po = photoBody?.pbFields()?.field(2)?.data?.pbFields() ?: emptyList()
            fun int(fields: List<PbField>, n: Int): Long? = fields.field(n)?.varint
            fun nameOf(map: Map<String, Int>, code: Long?) =
                code?.let { c -> map.entries.firstOrNull { it.value.toLong() == c }?.key ?: "CODE_$c" }

            val videoSub = int(opt, SettingCodes.OPT_VIDEO_SUB_MODE)?.toInt() ?: 0
            val photoSub = photoSubOf(opt, videoSub)
            val values = buildMap {
                with(SettingCodes) {
                    nameOf(photoResolutions, int(po, PHOTO_RESOLUTION))?.let { put("photo_resolution", it) }
                    nameOf(rawCapture, int(po, RAW_CAPTURE))?.let { put("raw_capture_type", it) }
                    nameOf(exportType, int(po, EXPORT_TYPE) ?: 0)?.let { put("export_type", it) }
                    put("photography_self_timer", (int(po, SELF_TIMER) ?: 0).toString())
                    put("record_duration", (int(po, DURATION) ?: 0).toString())
                    val live = po.field(LIVE_PHOTO)?.data?.pbFields()?.field(2)?.varint
                    put("live_photo_switch", (live == 2L).toString())
                    val shots = int(po, BURST_COUNT)
                    val seconds = int(po, BURST_SECONDS)
                    if (shots != null && seconds != null) put("burst_capture_params", "$shots/$seconds")
                    put("cache_capture_enable", ((int(po, BURST_CACHE) ?: 0) != 0L).toString())
                    val exposure = (po.field(VIDEO_EXPOSURE) ?: po.field(STILL_EXPOSURE))?.data?.pbFields()
                    if (exposure != null) {
                        val program = exposure.field(1)?.varint ?: 0L
                        exposurePrograms.entries.firstOrNull { it.value.toLong() == program }?.key?.let { put("exposure_program", it) }
                        put("exposure_iso", (exposure.field(2)?.varint ?: 0L).toString())
                        val shutter = exposure.field(3)?.data?.let {
                            java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
                        } ?: 0.0
                        put("exposure_shutter_speed", shutterName(shutter))
                    }
                    put("video_iso_top_limit", (int(po, ISO_TOP_LIMIT) ?: 0).toString())
                    val beauty = po.field(BEAUTY)?.data?.pbFields()
                    fun on(n: Int) = beauty?.field(n)?.data?.let {
                        java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).float > 0f
                    } == true
                    put("self_portrait_switch_2", portrait.entries.first { it.value == (on(1) to on(2)) }.key)
                    // CONF lapse_time is in seconds ("0.5", "10"); the camera keeps milliseconds.
                    lapseMs?.let { ms -> put("lapse_time", if (ms % 1000 == 0L) (ms / 1000).toString() else (ms / 1000.0).toString()) }
                }
                nameOf(SettingCodes.resolutions, int(po, SettingCodes.RESOLUTION))?.let { put("record_resolution", it) }
                nameOf(SettingCodes.fov, int(po, SettingCodes.FOV) ?: 0)?.let { put("fov_type", it) }
                nameOf(SettingCodes.flowstate, int(po, SettingCodes.FLOWSTATE) ?: 0)?.let { put("flowstate_level", it) }
                nameOf(SettingCodes.sharpness, int(po, SettingCodes.SHARPNESS) ?: 0)?.let { put("sharpness_level", it) }
                nameOf(SettingCodes.filter, int(po, SettingCodes.FILTER) ?: 0)?.let { put("filter_mode", it) }
                nameOf(SettingCodes.metering, int(po, SettingCodes.METERING) ?: 0)?.let { put("metering_mode", it) }
                put("white_balance", (int(po, SettingCodes.WHITE_BALANCE) ?: 0).toString())
                put("exposure_bias", SettingCodes.tenthsToEv(SettingCodes.unzigzag(int(po, SettingCodes.EXPOSURE_BIAS) ?: 0)))
                put("horizon_lock", ((int(po, SettingCodes.HORIZON_LOCK) ?: 0) != 0L).toString())
                put("hdr_switch", ((int(po, SettingCodes.HDR) ?: 0) != 0L).toString())
                put("i_log_switch", ((int(po, SettingCodes.I_LOG) ?: 0) != 0L).toString())
                // Pure Video calls the x2 zoom damage_zoom, the other modes undamage_zoom — same fields.
                val zoom = ((int(po, SettingCodes.DAMAGE_ZOOM) ?: 0) != 0L).toString()
                put("damage_zoom", zoom)
                put("undamage_zoom", zoom)
                put("dark_eis_enable", ((int(opt, SettingCodes.OPT_BLUR_REDUCTION) ?: 0) != 0L).toString())
            }
            val battery = opt.field(SettingCodes.OPT_BATTERY)?.data?.pbFields()
            val box = opt.field(SettingCodes.OPT_CHARGE_BOX)?.data?.pbFields()
            val podBattery = if (box?.field(1)?.varint == 1L) box.field(2)?.data?.pbFields() else null
            return CamSettings(
                mode = SettingCodes.modeOf(videoSub, photoSub),
                videoSub = videoSub,
                photoSub = photoSub,
                values = values,
                batteryPercent = battery?.field(2)?.varint?.toInt(),
                podBatteryPercent = podBattery?.field(2)?.varint?.toInt(),
                charging = battery?.field(1)?.varint == 1L,
                podCharging = podBattery?.field(1)?.varint == 1L,
            )
        }
    }
}
