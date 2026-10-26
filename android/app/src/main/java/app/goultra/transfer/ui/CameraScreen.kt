package app.goultra.transfer.ui

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.view.TextureView
import android.view.Surface
import android.view.OrientationEventListener
import android.graphics.SurfaceTexture
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PanoramaWideAngle
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import app.goultra.transfer.camera.CameraConf
import app.goultra.transfer.camera.CamSettings
import app.goultra.transfer.camera.CardInfo
import app.goultra.transfer.camera.SettingCodes

private val RecordRed = Color(0xFFE53935)

/** One adjustable setting: CONF parameter name, how to show it, whether it is an on/off switch. */
private class SettingSpec(
    val param: String,
    private val ru: String,
    private val en: String,
    val icon: ImageVector,
    val toggle: Boolean = false,
) {
    val title: String get() = tr(ru, en)
}

/** Settings grouped like the camera menu; every tile keeps its place (unavailable ones grey out, never vanish). */
private class SettingGroup(private val ru: String, private val en: String, val specs: List<SettingSpec>) {
    val title: String get() = tr(ru, en)
}

private val GROUPS = listOf(
    SettingGroup(
        "Кадр", "Frame",
        listOf(
            SettingSpec("record_resolution", "Разрешение", "Resolution", Icons.Filled.HighQuality),
            SettingSpec("photo_resolution", "Разрешение", "Resolution", Icons.Filled.HighQuality),
            SettingSpec("fov_type", "Угол обзора", "Field of view", Icons.Filled.PanoramaWideAngle),
            SettingSpec("raw_capture_type", "Формат", "Format", Icons.Filled.RawOn),
            SettingSpec("lapse_time", "Интервал", "Interval", Icons.Filled.Timelapse),
            SettingSpec("burst_capture_params", "Серия", "Burst", Icons.Filled.BurstMode),
            SettingSpec("cache_capture_enable", "Кэш серии", "Burst cache", Icons.Filled.History, toggle = true),
            SettingSpec("record_duration", "Длительность", "Duration", Icons.Filled.HourglassBottom),
            SettingSpec("export_type", "Результат", "Output", Icons.Filled.Stars),
            SettingSpec("photography_self_timer", "Таймер", "Timer", Icons.Filled.Timer),
            SettingSpec("live_photo_switch", "Живое фото", "Live photo", Icons.Filled.MotionPhotosOn, toggle = true),
        ),
    ),
    SettingGroup(
        "Стабилизация", "Stabilization",
        listOf(
            SettingSpec("flowstate_level", "FlowState", "FlowState", Icons.Filled.ControlCamera),
            SettingSpec("horizon_lock", "Горизонт", "Horizon lock", Icons.Filled.Straighten, toggle = true),
            SettingSpec("dark_eis_enable", "Антиразмытие", "Anti-blur", Icons.Filled.BlurOff, toggle = true),
        ),
    ),
    SettingGroup(
        "Цвет", "Color",
        listOf(
            SettingSpec("filter_mode", "Фильтр", "Filter", Icons.Filled.FilterVintage),
            SettingSpec("hdr_switch", "HDR", "HDR", Icons.Filled.HdrOn, toggle = true),
            SettingSpec("i_log_switch", "LOG", "LOG", Icons.Filled.Gradient, toggle = true),
            SettingSpec("sharpness_level", "Резкость", "Sharpness", Icons.Filled.Details),
            // Portrait mode: two independent switches, sent to the camera as one self_portrait_switch_2 value.
            SettingSpec(PORTRAIT_BRIGHTEN, "Осветить", "Brighten", Icons.Filled.LightMode, toggle = true),
            SettingSpec(PORTRAIT_SMOOTH, "Сгладить", "Smooth", Icons.Filled.Face, toggle = true),
        ),
    ),
    SettingGroup(
        "Экспозиция", "Exposure",
        listOf(
            SettingSpec("exposure_program", "Режим", "Mode", Icons.Filled.Camera),
            SettingSpec("exposure_iso", "ISO", "ISO", Icons.Filled.Iso),
            SettingSpec("exposure_shutter_speed", "Выдержка", "Shutter", Icons.Filled.ShutterSpeed),
            SettingSpec("exposure_bias", "EV", "EV", Icons.Filled.Exposure),
            SettingSpec("white_balance", "Баланс белого", "White balance", Icons.Filled.WbSunny),
            SettingSpec("metering_mode", "Замер", "Metering", Icons.Filled.CenterFocusWeak),
        ),
    ),
)

private fun modeLabel(mode: String): String = when (mode) {
    "VIDEO_NORMAL" -> tr("Видео", "Video")
    "VIDEO_PURE" -> "PureVideo"
    "VIDEO_SLOW_MOTION" -> tr("Замедление", "Slow motion")
    "VIDEO_TIMESHIFT" -> "TimeShift"
    "VIDEO_TIMELAPSE" -> tr("Таймлапс", "Timelapse")
    "VIDEO_LOOPRECORDING" -> tr("Петля", "Loop")
    "VIDEO_SUPER" -> "FreeFrame"
    "VIDEO_BABYVIEW" -> tr("Глазами ребёнка", "Toddler Titan")
    "PHOTO_SINGLE" -> tr("Фото", "Photo")
    "PHOTO_BURST" -> tr("Серия", "Burst")
    "PHOTO_INTERVAL" -> tr("Интервал", "Interval")
    "PHOTO_STARLAPSE" -> "Starlapse"
    else -> mode
}

private val PHOTO_SIZES = mapOf(
    "8192_6144" to "50 4:3", "8192_4608" to "37 16:9", "4096_3072" to "12.5 4:3", "4000_2250" to "9 16:9",
)

private fun photoSizeLabel(value: String): String =
    PHOTO_SIZES[value]?.let { s -> s.substringBefore(' ') + tr(" МП ", " MP ") + s.substringAfter(' ') }
        ?: value.replace('_', '×')

/** «600» seconds → «10 мин»; 0 = no limit. */
private fun durationLabel(seconds: String): String {
    val s = seconds.toIntOrNull() ?: return seconds
    return when {
        s == 0 -> tr("Без огранич.", "No limit")
        s % 3600 == 0 -> "${s / 3600} " + tr("ч", "h")
        s % 60 == 0 -> "${s / 60} " + tr("мин", "min")
        else -> "$s " + tr("с", "s")
    }
}

/** Frame sizes by aspect ratio, largest first. */
private val RES_SIZES = linkedMapOf(
    "3840_2160" to "4K", "2720_1530" to "2.7K", "1920_1080" to "1080p",
    "4000_3000" to "4K", "2720_2040" to "2.7K", "1920_1440" to "1440p",
    "4096_1744" to "4K",
)

private fun sizeLabel(size: String) = RES_SIZES[size] ?: size.replace('_', '×')

private fun aspectOf(size: String): String {
    val parts = size.split('_').mapNotNull { it.toIntOrNull() }
    if (parts.size != 2 || parts[1] == 0) return "?"
    val (w, h) = parts
    return when {
        kotlin.math.abs(w * 9 - h * 16) <= w / 50 -> "16:9"
        w * 3 == h * 4 -> "4:3"
        else -> String.format(java.util.Locale.US, "%.2f:1", w.toFloat() / h) // 4096×1744 → 2.35:1
    }
}

private fun valueLabel(param: String, value: String?): String = when {
    value == null -> "—"
    value.startsWith("CODE_") -> tr("код ", "code ") + value.removePrefix("CODE_") // not mapped yet (see CLAUDE.md)
    param == "record_resolution" -> value.substringBeforeLast('_').let { size ->
        sizeLabel(size) + (if (aspectOf(size) == "16:9") "" else " " + aspectOf(size)) + " · " + value.substringAfterLast('_')
    }
    param == "fov_type" -> mapOf(
        "MEGA_VIEW" to "MegaView", "ULTRAWIDE" to tr("Сверхширокий", "Ultrawide"),
        "DEWARP" to tr("Без дисторсии", "Dewarp"), "LINEAR" to tr("Линейный", "Linear"),
        "ACTION_VIEW" to "ActionView", "LINEARHORIZON_45" to tr("Горизонт 45°", "Horizon 45°"),
        "LINEARHORIZON_360" to tr("Горизонт 360°", "Horizon 360°"),
        "SUPER_NEW" to "Titan",
    )[value] ?: value
    param == "flowstate_level" -> mapOf(
        "FLOW_STATE_LEVEL_CV5_CLOSE" to tr("Выкл", "Off"), "FLOW_STATE_LEVEL_CV5_SLIGHT" to tr("Слабая", "Low"),
        "FLOW_STATE_LEVEL_CV5_GENERAL" to tr("Стандарт", "Standard"), "FLOW_STATE_LEVEL_CV5_STRONG" to tr("Высокая", "High"),
    )[value] ?: value
    param == "sharpness_level" -> mapOf("LOW" to tr("Низкая", "Low"), "MEDIUM" to tr("Средняя", "Medium"), "HIGH" to tr("Высокая", "High"))[value] ?: value
    param == "filter_mode" -> mapOf(
        "STANDARD" to tr("Стандарт", "Standard"), "VIVID" to tr("Яркий", "Vivid"),
        "VINTAGE" to tr("Винтажная плёнка", "Vintage film"), "URBAN" to tr("Контрастная плёнка", "Contrast film"),
        "FILM" to tr("Плёнка", "Film"), "NIGHT" to tr("Ночная плёнка", "Night film"), "GLOW" to tr("Сияние", "Glow"),
        "SELFIE" to tr("Селфи", "Selfie"), "FROSTEDCCD" to tr("Матовый CCD", "Frosted CCD"),
        "AMBERCCD" to tr("Янтарный CCD", "Amber CCD"), "BLUECCD" to tr("Синий CCD", "Blue CCD"),
    )[value]
        ?: value.lowercase().replaceFirstChar { it.uppercase() }
    param == "metering_mode" -> mapOf("EVALUATIVE" to tr("Матрица", "Matrix"), "FACE_PRIORITY" to tr("По лицу", "Face"))[value] ?: value
    param == "white_balance" -> if (value == "0") tr("Авто", "Auto") else "${value}K"
    param == "exposure_bias" -> SettingCodes.tenthsToEv(SettingCodes.evToTenths(value))
    param == "photo_resolution" -> photoSizeLabel(value)
    param == "raw_capture_type" -> mapOf("PURESHOT" to "PureShot", "PURESHOT_RAW" to "PureShot + RAW")[value] ?: value
    param == "export_type" -> mapOf(
        "STARLAPSE_VIDEO" to tr("Видео", "Video"), "STAR_TRAILS_VIDEO" to tr("Следы звёзд, видео", "Star trails, video"),
        "STAR_TRAILS_PHOTO" to tr("Следы звёзд, фото", "Star trails, photo"),
    )[value] ?: value
    param == "photography_self_timer" -> if (value == "0") tr("Выкл", "Off") else "$value " + tr("с", "s")
    param == "exposure_program" -> if (value == "FULL_AUTO") tr("Авто", "Auto") else tr("Ручная", "Manual")
    param == "self_portrait_switch_2" -> mapOf(
        "false" to tr("Выкл", "Off"), "self_portrait_whitening_switch" to tr("Осветить", "Brighten"),
        "self_portrait_dermabrasion_switch" to tr("Сгладить", "Smooth"),
        "self_portrait_derm_white" to tr("Осветить + сгладить", "Brighten + smooth"),
    )[value] ?: value
    param == "exposure_iso" -> when {
        value.startsWith("AUTO_") -> value.removePrefix("AUTO_").let { if (it == "0") tr("Авто", "Auto") else tr("Авто", "Auto") + " ≤$it" }
        else -> value
    }
    param == "exposure_shutter_speed" -> if (value == "0") tr("Авто", "Auto") else if (value.startsWith("1/")) value else "$value " + tr("с", "s")
    param == "video_iso_top_limit" -> if (value == "0") tr("Нет", "None") else value
    param == "lapse_time" -> "$value " + tr("с", "s")
    param == "record_duration" -> durationLabel(value)
    param == "burst_capture_params" -> value.split('/').let { if (it.size == 2) tr("${it[0]} за ${it[1]} с", "${it[0]} in ${it[1]} s") else value }
    value == "true" -> tr("Вкл", "On")
    value == "false" -> tr("Выкл", "Off")
    else -> value
}

/** Values the app can actually send (some CONF names have no known protocol code yet). */
internal fun encodable(param: String, value: String): Boolean = when (param) {
    "record_resolution" -> value in SettingCodes.resolutions
    "fov_type" -> value in SettingCodes.fov
    "flowstate_level" -> value in SettingCodes.flowstate
    "sharpness_level" -> value in SettingCodes.sharpness
    "filter_mode" -> value in SettingCodes.filter
    "metering_mode" -> value in SettingCodes.metering
    "photo_resolution" -> value in SettingCodes.photoResolutions
    "raw_capture_type" -> value in SettingCodes.rawCapture
    "export_type" -> value in SettingCodes.exportType
    "burst_capture_params" -> value in SettingCodes.burstCacheFrames
    "lapse_time" -> (value.toDoubleOrNull() ?: 0.0) > 0 // interval «0» — meaning unknown
    "self_portrait_switch_2" -> value in SettingCodes.portrait
    else -> true
}

/**
 * Depends on the mode only, so tiles keep their places while settings change.
 * record_duration is verified only for loop / interval / starlapse (fields 29 + 54).
 */
internal fun shown(mode: String, param: String): Boolean =
    param != "record_duration" || mode in setOf("VIDEO_LOOPRECORDING", "PHOTO_INTERVAL", "PHOTO_STARLAPSE")

/**
 * ISO and shutter are set only in manual exposure (greyed «Авто» otherwise), like the camera menu.
 * EV has no effect with both ISO and shutter fixed (MANUAL) — the camera ignores it (self-test, 25.10).
 */
internal fun locked(param: String, values: Map<String, String>): Boolean = when (param) {
    "exposure_iso", "exposure_shutter_speed" -> values["exposure_program"].let { it == null || it == "FULL_AUTO" }
    "exposure_bias" -> values["exposure_program"] == "MANUAL"
    else -> false
}

private const val PORTRAIT_BRIGHTEN = "portrait_brighten"
private const val PORTRAIT_SMOOTH = "portrait_smooth"

/** The CONF parameter behind a tile (the two portrait switches share self_portrait_switch_2). */
private fun confParam(param: String) =
    if (param == PORTRAIT_BRIGHTEN || param == PORTRAIT_SMOOTH) "self_portrait_switch_2" else param

/** self_portrait_switch_2 value after flipping one of the portrait switches. */
private fun portraitWith(values: Map<String, String>, param: String, on: Boolean): String {
    val (brighten, smooth) = SettingCodes.portrait[values["self_portrait_switch_2"]] ?: (false to false)
    val target = if (param == PORTRAIT_BRIGHTEN) on to smooth else brighten to on
    return SettingCodes.portrait.entries.first { it.value == target }.key
}

/** ISO list like the camera: «AUTO_<max ISO>» entries first, then fixed values. */
internal fun optionsFor(conf: CameraConf, mode: String, param: String, values: Map<String, String>): List<String> =
    if (param == PORTRAIT_BRIGHTEN || param == PORTRAIT_SMOOTH) {
        val allowed = conf.allowed(mode, "self_portrait_switch_2", values)
        listOf("true", "false").filter { portraitWith(values, param, it == "true") in allowed }
    } else if (param == "hdr_switch" && !conf.hdrPossible(mode, values["record_resolution"])) {
        listOf("false") // can only be switched off at this resolution
    } else if (param == "exposure_iso") {
        val limits = conf.allowed(mode, "video_iso_top_limit", values).sortedByDescending { it.toIntOrNull() ?: 0 }
        (if (limits.isEmpty()) listOf("AUTO_0") else limits.map { "AUTO_$it" }) +
            conf.allowed(mode, param, values).filter { it != "0" }
    } else {
        conf.allowed(mode, param, values)
    }

internal fun currentValue(param: String, values: Map<String, String>): String? = when {
    param == "exposure_iso" && values[param] == "0" -> "AUTO_${values["video_iso_top_limit"] ?: "0"}"
    param == PORTRAIT_BRIGHTEN -> SettingCodes.portrait[values["self_portrait_switch_2"]]?.first?.toString()
    param == PORTRAIT_SMOOTH -> SettingCodes.portrait[values["self_portrait_switch_2"]]?.second?.toString()
    else -> values[param]
}

/** Options the camera menu offers: exposure is only Auto / Manual (priority modes follow from ISO / shutter). */
internal fun offered(param: String, value: String): Boolean =
    param != "exposure_program" || value == "FULL_AUTO" || value == "MANUAL"

internal fun sameValue(param: String, a: String?, b: String): Boolean = when {
    a == null -> false
    param == "exposure_bias" -> SettingCodes.evToTenths(a) == SettingCodes.evToTenths(b)
    param == "exposure_program" -> (a == "FULL_AUTO") == (b == "FULL_AUTO")
    param == "exposure_shutter_speed" -> {
        val x = SettingCodes.shutterSeconds(a)
        val y = SettingCodes.shutterSeconds(b)
        if (x == 0.0 || y == 0.0) x == y else kotlin.math.abs(x - y) / y < 0.02
    }
    else -> a == b
}

/**
 * Live preview pinned on top (a settings change shows in the frame right away), settings scrolling under it,
 * modes and the shutter pinned at the bottom under the thumb — like the camera and the official app.
 * Recording works over BLE alone.
 */
@Composable
fun CameraTab(vm: CameraController, onConnect: () -> Unit, onRecord: () -> Unit) {
    val conn = vm.conn
    val linked = conn == ConnState.Connected || conn == ConnState.Remote
    val recording = vm.capture?.recording == true
    val settings = vm.settings
    val conf = vm.conf
    val picture = settings?.mode?.let { SettingCodes.modes[it] }?.takesPicture == true
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            PreviewBox(vm, conn, recording, settings, onConnect)
        }
        // Settings (or how to connect) scroll in the middle.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (linked && settings != null) {
                SettingsGrid(vm, settings, conf, enabled = !recording)
                if (conn == ConnState.Connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(
                            "Пока идёт трансляция, меню на самой камере заблокировано — " +
                                "чтобы менять настройки кнопками камеры, перейдите на вкладку «Файлы».",
                            "While the live view runs, the camera's own menu is locked — " +
                                "to change settings with the camera's buttons, switch to the «Files» tab.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (conn is ConnState.Failed) {
                Spacer(Modifier.height(16.dp))
                Text(conn.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (!linked && conn !is ConnState.Working) {
                Spacer(Modifier.height(16.dp))
                Text(
                    tr(
                        "Для записи хватает Bluetooth — это быстро, Wi-Fi не нужен. " +
                            "Для трансляции и настроек нужно подключение.",
                        "Recording works over Bluetooth alone — fast, no Wi-Fi needed. " +
                            "The live view and settings need a connection.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onConnect) { Text(tr("Подключиться с трансляцией", "Connect with live view")) }
            }
        }
        // Pinned bottom: modes, zoom, shutter.
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (linked && settings != null) ModeRow(vm, settings, enabled = !recording && vm.settingBusy == null)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                        if (vm.timeShiftNormal != null) SpeedButton(vm) else ZoomButton(vm, settings, conf)
                    }
                    RecordButton(
                        recording = recording,
                        picture = picture,
                        busy = (vm.recordBusy && vm.countdown == null) || conn is ConnState.Working,
                        countdown = vm.countdown,
                    ) {
                        if (recording) vm.stopRecording() else onRecord()
                    }
                    Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                        if (recording && conn == ConnState.Connected) EcoButton(vm)
                    }
                }
                if (!linked) {
                    Text(
                        tr("Нажмите — камера проснётся и сразу начнёт запись", "Tap — the camera wakes up and starts recording"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
@Composable
private fun PreviewBox(
    vm: CameraController,
    conn: ConnState,
    recording: Boolean,
    settings: CamSettings?,
    onConnect: () -> Unit,
) {
    // The box takes the stream's real shape (4:3 in FreeFrame…), capped so a tall 4:3 frame leaves room below.
    val aspect = if (conn == ConnState.Connected) vm.liveAspect else 16f / 9f
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.36f).dp
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (conn) {
            ConnState.Connected -> {
                LivePreview(vm)
                if (settings?.mode == "VIDEO_SUPER") FreeFrameGuides()
            }
            is ConnState.Working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(conn.step, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
            ConnState.Remote -> if (vm.ecoRecording) {
                Text(
                    tr("Экономия: Wi-Fi камеры выключен до конца записи", "Saving power: the camera's Wi-Fi is off until the recording ends"),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Трансляция — при подключении по Wi-Fi", "Live view needs a Wi-Fi connection"), color = Color.White)
                    TextButton(onConnect) { Text(tr("Подключиться по Wi-Fi", "Connect over Wi-Fi")) }
                }
            }
            else -> Text(tr("Камера не подключена", "Camera not connected"), color = Color.White)
        }
        // Self-timer: big seconds over the preview.
        vm.countdown?.let {
            Text(
                it.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0x66000000), CircleShape).padding(horizontal = 36.dp),
            )
        }
        vm.liveError?.let {
            // Above the REC badge, which takes the bottom edge while recording.
            Text(
                it,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = if (recording) 44.dp else 8.dp),
            )
        }
        // Readings only for a live link: after a disconnect the last values would be stale.
        val live = conn == ConnState.Connected || conn == ConnState.Remote
        // Card and battery on top, the recording time at the bottom (they don't fit one row in TimeShift).
        if (live) settings?.card?.let { CardChip(it, Modifier.align(Alignment.TopStart).padding(10.dp)) }
        if (recording) RecBadge(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
        if (live && (settings?.batteryPercent != null || settings?.podBatteryPercent != null)) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                settings.batteryPercent?.let { BatteryLabel(tr("Камера", "Camera"), it, settings.charging) }
                if (settings.batteryPercent != null && settings.podBatteryPercent != null) Spacer(Modifier.width(10.dp))
                settings.podBatteryPercent?.let { BatteryLabel("Pod", it, settings.podCharging) }
            }
        }
    }
}

/**
 * One scrolling strip of all modes, video then photo (like the camera and the official app): compact, the current
 * mode highlighted and scrolled into view.
 */
@Composable
private fun ModeRow(vm: CameraController, settings: CamSettings, enabled: Boolean) {
    val modes = SettingCodes.modes.keys.toList()
    val state = rememberLazyListState()
    val current = modes.indexOf(settings.mode)
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(maxOf(0, current - 1)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(modes) { i, mode ->
            val m = SettingCodes.modes.getValue(mode)
            // A thin divider between the video and the photo modes.
            if (i > 0 && m.photo && !SettingCodes.modes.getValue(modes[i - 1]).photo) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            val selected = settings.mode == mode
            Text(
                modeLabel(mode),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && !selected) { vm.selectMode(mode) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Recording time; in TimeShift also the speed. Not the video length: Auto picks its speed itself and doesn't
 * report it, so the length is shown after the stop, from the file.
 */
@Composable
private fun RecBadge(vm: CameraController, modifier: Modifier = Modifier) {
    // Ticks from the phone clock (smooth), not from the camera's replies (every 1–2 s).
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(200)
            value = SystemClock.elapsedRealtime()
        }
    }
    val real = formatDuration(vm.recordSeconds(now))
    val text = when (vm.timeShiftNormal) {
        true -> "● REC $real · " + tr("Обычная", "Normal")
        false -> "● REC $real · " + tr("Авто", "Auto")
        null -> "● REC $real"
    }
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(RecordRed, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Economy recording: after a warning, the live view stops and the camera's Wi-Fi is left — the camera keeps it off
 * until the recording ends; Bluetooth still shows the time and stops the recording.
 */
@Composable
private fun EcoButton(vm: CameraController) {
    var ask by remember { mutableStateOf(false) }
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { ask = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.BatterySaver, tr("Экономия батареи", "Save battery"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(tr("Экономия батареи камеры", "Save the camera's battery")) },
            text = {
                Text(
                    tr(
                        "Трансляция остановится, телефон отключится от Wi-Fi камеры, и камера выключит свой Wi-Fi. " +
                            "Так будет до конца записи: ни картинки, ни файлов до остановки, включить обратно нельзя.\n\n" +
                            "Время записи и кнопка «Стоп» (здесь и в уведомлении) работают по Bluetooth. " +
                            "После остановки приложение снова подключится по Wi-Fi само.",
                        "The live view stops, the phone leaves the camera's Wi-Fi and the camera turns its Wi-Fi off. " +
                            "It stays so until the recording ends: no picture and no files until the stop, and it can't " +
                            "be turned back on.\n\nThe recording time and Stop (here and in the notification) work over " +
                            "Bluetooth. After the stop the app joins the Wi-Fi again by itself.",
                    ),
                )
            },
            confirmButton = {
                TextButton({ ask = false; vm.startEcoRecording() }) { Text(tr("Включить экономию", "Save battery")) }
            },
            dismissButton = { TextButton({ ask = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** TimeShift recording: Auto (sped up, the camera picks how much) ↔ normal speed (1×). */
@Composable
private fun SpeedButton(vm: CameraController) {
    val fast = vm.timeShiftNormal?.not() ?: return
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (fast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !vm.speedBusy) { vm.toggleTimeShiftSpeed() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (fast) tr("Авто", "Auto") else "1×",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (fast) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ZoomButton(vm: CameraController, settings: CamSettings?, conf: CameraConf?) {
    val mode = settings?.mode ?: return
    // Pure Video: damage_zoom; Video / loop / photo: undamage_zoom — offered only where CONF allows both states now.
    val param = listOf("damage_zoom", "undamage_zoom").firstOrNull { conf?.supports(mode, it) == true } ?: return
    if (conf!!.allowed(mode, param, settings.values).toSet() != setOf("true", "false")) return
    val on = settings.values[param] == "true"
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = vm.settingBusy == null) { vm.setSetting(param, (!on).toString()) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "x2" else "x1",
            fontWeight = FontWeight.Bold,
            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsGrid(vm: CameraController, settings: CamSettings, conf: CameraConf?, enabled: Boolean) {
    val mode = settings.mode
    when {
        mode == null -> Text(
            tr("Режим камеры не распознан", "Unknown camera mode") + " (${settings.videoSub}/${settings.photoSub})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        conf == null -> Text(
            tr(
                "Подключитесь по Wi-Fi один раз — приложение загрузит с камеры список настроек.",
                "Connect over Wi-Fi once — the app loads the list of settings from the camera.",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        else -> Column(Modifier.fillMaxWidth()) {
            var open by remember { mutableStateOf<SettingSpec?>(null) }
            GROUPS.forEach { group ->
                val specs = group.specs.filter { conf.supports(mode, confParam(it.param)) && shown(mode, it.param) }
                if (specs.isEmpty()) return@forEach
                Text(
                    group.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
                )
                // Fixed 3-column grid: a tile never changes size or position because of another setting.
                specs.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { spec ->
                            val current = currentValue(spec.param, settings.values)
                            val options = optionsFor(conf, mode, spec.param, settings.values)
                                .filter { offered(spec.param, it) && encodable(spec.param, it) }
                            val usable = enabled && vm.settingBusy == null && !locked(spec.param, settings.values) &&
                                (if (spec.toggle) options.any { it != current } else options.size > 1)
                            SettingTile(
                                spec = spec,
                                value = if (locked(spec.param, settings.values) && spec.param != "exposure_bias") tr("Авто", "Auto")
                                else valueLabel(spec.param, current),
                                active = spec.toggle && current == "true",
                                enabled = usable,
                                busy = vm.settingBusy == confParam(spec.param),
                                modifier = Modifier.weight(1f),
                            ) {
                                val flipped = (current != "true").toString()
                                when {
                                    confParam(spec.param) != spec.param ->
                                        vm.setSetting(confParam(spec.param), portraitWith(settings.values, spec.param, current != "true"))
                                    spec.toggle -> vm.setSetting(spec.param, flipped)
                                    else -> open = spec
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            open?.let { spec ->
                SettingSheet(
                    spec = spec,
                    current = currentValue(spec.param, settings.values),
                    options = optionsFor(conf, mode, spec.param, settings.values).filter { offered(spec.param, it) },
                    canSet = { encodable(spec.param, it) },
                    // The panel stays open: pick, look at the preview, pick again; tap outside or swipe to close.
                    onPick = { vm.setSetting(spec.param, it) },
                    busy = vm.settingBusy != null,
                    onDismiss = { open = null },
                )
            }
        }
    }
}

/** Fixed-size tile: icon + title on top, value (up to two lines) below; on/off tiles are tinted when on. */
@Composable
private fun SettingTile(
    spec: SettingSpec,
    value: String,
    active: Boolean,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = container,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(88.dp).alpha(if (enabled || busy) 1f else 0.4f)
            .clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(spec.icon, null, Modifier.size(18.dp), tint = content)
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.weight(1f))
            FitText(spec.title, 12f, 9f, content)
            FitText(value, 15f, 10f, content, FontWeight.SemiBold)
        }
    }
}

/** One line that shrinks its font (down to [minSp]) until it fits — long Russian labels on large system fonts. */
@Composable
private fun FitText(text: String, maxSp: Float, minSp: Float, color: Color, weight: FontWeight? = null) {
    var size by remember(text) { mutableStateOf(maxSp) }
    var fits by remember(text) { mutableStateOf(false) }
    Text(
        text,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size > minSp) size = maxOf(minSp, size * 0.92f) else fits = true
        },
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
    )
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingSheet(
    spec: SettingSpec,
    current: String?,
    options: List<String>,
    canSet: (String) -> Boolean,
    onPick: (String) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    // Fully expanded and scrollable: the frame-rate row must not end up under the navigation bar.
    // No scrim and at most ~half the screen: the live preview above stays visible and shows the change at once.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
    ) {
        Column(
            Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(spec.icon, null)
                Spacer(Modifier.width(10.dp))
                Text(spec.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(16.dp))
            if (spec.param == "record_resolution") {
                // Aspect ratio → frame size → frame rate (the camera allows high rates only in 16:9).
                val allSizes = options.map { it.substringBeforeLast('_') }.distinct()
                    .sortedBy { RES_SIZES.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }
                val currentSize = current?.substringBeforeLast('_')
                val aspects = allSizes.map(::aspectOf).distinct()
                // The current value may be unknown to the app (CODE_n): then start from the first format.
                var aspect by remember {
                    mutableStateOf(currentSize?.let(::aspectOf)?.takeIf { it in aspects } ?: aspects.firstOrNull() ?: "16:9")
                }
                var size by remember { mutableStateOf(currentSize?.takeIf { it in allSizes }) }
                val sizes = allSizes.filter { aspectOf(it) == aspect }
                if (aspects.size > 1) {
                    Text(tr("Формат кадра", "Aspect ratio"), style = MaterialTheme.typography.labelLarge)
                    OptionChips(aspects, aspect, { it }) { a ->
                        aspect = a
                        if (size == null || aspectOf(size!!) != a) size = allSizes.firstOrNull { aspectOf(it) == a }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(tr("Разрешение", "Resolution"), style = MaterialTheme.typography.labelLarge)
                OptionChips(sizes, size, ::sizeLabel) { size = it }
                Spacer(Modifier.height(12.dp))
                Text(tr("Кадров в секунду", "Frames per second"), style = MaterialTheme.typography.labelLarge)
                val rates = options.filter { it.substringBeforeLast('_') == size }
                    .sortedBy { it.substringAfterLast('_').toIntOrNull() ?: 0 }
                OptionChips(rates, current, { it.substringAfterLast('_') }, enabled = canSet) { onPick(it) }
                if (size != currentSize) {
                    Text(
                        if (size == null) tr("Выберите разрешение", "Choose a resolution")
                        else tr("Выберите частоту кадров — настройка применится сразу", "Choose the frame rate — it applies at once"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                val sorted = when (spec.param) {
                    "exposure_bias" -> options.sortedBy { SettingCodes.evToTenths(it) }
                    "white_balance" -> options.sortedBy { it.toIntOrNull() ?: 0 }
                    else -> options
                }
                OptionChips(sorted, current, { valueLabel(spec.param, it) }, { sameValue(spec.param, current, it) }, canSet) {
                    onPick(it)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(
    options: List<String>,
    selected: String?,
    label: (String) -> String,
    isSelected: (String) -> Boolean = { it == selected },
    enabled: (String) -> Boolean = { true },
    onPick: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = isSelected(option),
                onClick = { onPick(option) },
                enabled = enabled(option),
                label = { Text(label(option)) },
            )
        }
    }
    if (options.any { !enabled(it) }) {
        Text(
            tr(
                "Серые значения камера поддерживает, но приложение пока не умеет их выставлять — выберите на камере",
                "Greyed values are supported by the camera, but the app can't set them yet — pick them on the camera",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BatteryLabel(name: String, percent: Int, charging: Boolean) {
    Icon(
        if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
        if (charging) tr("заряжается", "charging") else null,
        Modifier.size(14.dp),
        tint = if (charging) Color(0xFF7CDB7C) else Color.White,
    )
    Spacer(Modifier.width(2.dp))
    Text("$name $percent%", color = Color.White, fontSize = 12.sp)
}

/** «SD 46 мин» (video) or «SD 1630 фото» (photo): what still fits on the card. */
@Composable
private fun CardChip(card: CardInfo, modifier: Modifier = Modifier) {
    val left = when {
        card.photosLeft != null -> "${card.photosLeft} " + tr("фото", "photos")
        card.videoSecondsLeft != null -> {
            val m = card.videoSecondsLeft / 60
            if (m >= 60) "${m / 60} " + tr("ч", "h") + " ${m % 60} " + tr("мин", "min") else "$m " + tr("мин", "min")
        }
        else -> null
    }
    // Only what still fits (the free size made the label run into the battery one); the size if that's unknown.
    Text(
        "SD " + (left ?: formatSize(card.freeBytes)),
        color = if (card.freeBytes < card.totalBytes / 20) Color(0xFFFFB4A9) else Color.White, // < 5 % free: warn
        fontSize = 12.sp,
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Red dot — record, red square — stop, white disc — take a picture (photo / burst). */
@Composable
private fun RecordButton(
    recording: Boolean,
    picture: Boolean,
    busy: Boolean,
    countdown: Int? = null,
    light: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(72.dp)
            .border(4.dp, if (light) Color.White else MaterialTheme.colorScheme.onSurface, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(36.dp))
            countdown != null -> Text(
                countdown.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = if (light) Color.White else Color.Unspecified,
            ) // tap cancels
            recording -> Box(Modifier.size(30.dp).background(RecordRed, RoundedCornerShape(6.dp)))
            picture -> Box(
                Modifier.size(52.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(Color.White, CircleShape),
            )
            else -> Box(Modifier.size(52.dp).background(RecordRed, CircleShape))
        }
    }
}

/**
 * FreeFrame records the whole 4:3 sensor; like the camera, mark the future crops: a full-width 16:9 band and a
 * full-height 9:16 column in the centre, thin light edges, the corners outside both darkened.
 */
@Composable
internal fun FreeFrameGuides() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val bandH = w * 9f / 16f
        val bandTop = (h - bandH) / 2
        val colW = h * 9f / 16f
        val colLeft = (w - colW) / 2
        val shade = Color(0x66000000)
        // Corners: outside the band (top / bottom) and outside the column (left / right).
        for ((x, cw) in listOf(0f to colLeft, colLeft + colW to w - colLeft - colW)) {
            drawRect(shade, topLeft = Offset(x, 0f), size = Size(cw, bandTop))
            drawRect(shade, topLeft = Offset(x, bandTop + bandH), size = Size(cw, h - bandTop - bandH))
        }
        val line = Color(0x99FFFFFF)
        val stroke = 1.dp.toPx()
        drawLine(line, Offset(0f, bandTop), Offset(w, bandTop), stroke)
        drawLine(line, Offset(0f, bandTop + bandH), Offset(w, bandTop + bandH), stroke)
        drawLine(line, Offset(colLeft, 0f), Offset(colLeft, h), stroke)
        drawLine(line, Offset(colLeft + colW, 0f), Offset(colLeft + colW, h), stroke)
    }
}

/**
 * TextureView for the hardware decoder (unlike SurfaceView it can be rotated with the rest of the UI).
 * The stream starts with the first surface and moves between views without a restart.
 */
@Composable
internal fun LivePreview(vm: CameraController, modifier: Modifier = Modifier.fillMaxSize()) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(st).also(vm::attachLive)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        // Switch the decoder away first (synchronous), then the texture may go.
                        surface?.let { vm.detachLive(it); it.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
}

/** Phone held sideways (by the tilt sensor, so it works with auto-rotate off): 90 or 270, else 0. */
@Composable
internal fun rememberSidewaysRotation(): Int {
    val context = LocalContext.current
    var rotation by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // Hysteresis: enter landscape past 60°, leave below 30° from upright.
                rotation = when {
                    deg in 60..120 -> 270 // right edge down
                    deg in 240..300 -> 90 // left edge down
                    deg <= 30 || deg >= 330 || deg in 150..210 -> 0
                    else -> rotation
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return rotation
}

/**
 * Full-screen preview for a phone held sideways, like the official app: the picture turned upright and as large as
 * possible; only the shutter and the battery on top. Drawn over the whole app.
 */
@Composable
internal fun LandscapeLive(vm: CameraController, rotation: Int, onRecord: () -> Unit) {
    val recording = vm.capture?.recording == true
    val picture = vm.settings?.mode?.let { SettingCodes.modes[it] }?.takesPicture == true
    // Full screen: the status and navigation bars hidden while the phone is sideways (back on leaving).
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        // Lay the content out landscape (swapped constraints), then turn it to match the phone; kept clear of the
        // front camera cutout (and of the bars if the user swipes them in).
        Box(
            Modifier.align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .layout { measurable, c ->
                    val p = measurable.measure(Constraints.fixed(c.maxHeight, c.maxWidth))
                    layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
                }
                .graphicsLayer { rotationZ = rotation.toFloat() },
        ) {
            // The picture with its labels on top of it (they move with the picture, never off to the screen edge).
            Box(Modifier.fillMaxHeight().aspectRatio(vm.liveAspect).align(Alignment.Center)) {
                LivePreview(vm)
                if (vm.settings?.mode == "VIDEO_SUPER") FreeFrameGuides()
                vm.countdown?.let {
                    Text(
                        it.toString(), color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (recording) RecBadge(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                // Same places as in portrait: card top left, batteries top right.
                val s = vm.settings
                s?.card?.let { CardChip(it, Modifier.align(Alignment.TopStart).padding(12.dp)) }
                if (s?.batteryPercent != null || s?.podBatteryPercent != null) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        s.batteryPercent?.let { BatteryLabel(tr("Камера", "Camera"), it, s.charging) }
                        if (s.batteryPercent != null && s.podBatteryPercent != null) Spacer(Modifier.width(10.dp))
                        s.podBatteryPercent?.let { BatteryLabel("Pod", it, s.podCharging) }
                    }
                }
            }
            // The shutter centred at the side; the TimeShift speed above it, battery saving below.
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(52.dp)) { SpeedButton(vm) }
                RecordButton(
                    recording = recording,
                    picture = picture,
                    busy = vm.recordBusy && vm.countdown == null,
                    countdown = vm.countdown,
                    light = true,
                ) { if (recording) vm.stopRecording() else onRecord() }
                Box(Modifier.size(52.dp)) { if (recording && vm.conn == ConnState.Connected) EcoButton(vm) }
            }
        }
    }
}
