package app.goultra.transfer.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
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
import androidx.compose.ui.viewinterop.AndroidView
import app.goultra.transfer.camera.CameraConf
import app.goultra.transfer.camera.CamSettings
import app.goultra.transfer.camera.SettingCodes

private val RecordRed = Color(0xFFE53935)

/** One adjustable setting: CONF parameter name, how to show it, whether it is an on/off switch. */
private class SettingSpec(val param: String, val title: String, val icon: ImageVector, val toggle: Boolean = false)

/** Settings grouped like the camera menu; every tile keeps its place (unavailable ones grey out, never vanish). */
private class SettingGroup(val title: String, val specs: List<SettingSpec>)

private val GROUPS = listOf(
    SettingGroup(
        "Кадр",
        listOf(
            SettingSpec("record_resolution", "Разрешение", Icons.Filled.HighQuality),
            SettingSpec("photo_resolution", "Разрешение", Icons.Filled.HighQuality),
            SettingSpec("fov_type", "Угол обзора", Icons.Filled.PanoramaWideAngle),
            SettingSpec("raw_capture_type", "Формат", Icons.Filled.RawOn),
            SettingSpec("lapse_time", "Интервал", Icons.Filled.Timelapse),
            SettingSpec("burst_capture_params", "Серия", Icons.Filled.BurstMode),
            SettingSpec("cache_capture_enable", "Кэш серии", Icons.Filled.History, toggle = true),
            SettingSpec("record_duration", "Длительность", Icons.Filled.HourglassBottom),
            SettingSpec("export_type", "Результат", Icons.Filled.Stars),
            SettingSpec("photography_self_timer", "Таймер", Icons.Filled.Timer),
            SettingSpec("live_photo_switch", "Живое фото", Icons.Filled.MotionPhotosOn, toggle = true),
        ),
    ),
    SettingGroup(
        "Стабилизация",
        listOf(
            SettingSpec("flowstate_level", "FlowState", Icons.Filled.ControlCamera),
            SettingSpec("horizon_lock", "Горизонт", Icons.Filled.Straighten, toggle = true),
            SettingSpec("dark_eis_enable", "Антиразмытие", Icons.Filled.BlurOff, toggle = true),
        ),
    ),
    SettingGroup(
        "Цвет",
        listOf(
            SettingSpec("filter_mode", "Фильтр", Icons.Filled.FilterVintage),
            SettingSpec("hdr_switch", "HDR", Icons.Filled.HdrOn, toggle = true),
            SettingSpec("i_log_switch", "LOG", Icons.Filled.Gradient, toggle = true),
            SettingSpec("sharpness_level", "Резкость", Icons.Filled.Details),
            // Portrait mode: two independent switches, sent to the camera as one self_portrait_switch_2 value.
            SettingSpec(PORTRAIT_BRIGHTEN, "Осветить", Icons.Filled.LightMode, toggle = true),
            SettingSpec(PORTRAIT_SMOOTH, "Сгладить", Icons.Filled.Face, toggle = true),
        ),
    ),
    SettingGroup(
        "Экспозиция",
        listOf(
            SettingSpec("exposure_program", "Режим", Icons.Filled.Camera),
            SettingSpec("exposure_iso", "ISO", Icons.Filled.Iso),
            SettingSpec("exposure_shutter_speed", "Выдержка", Icons.Filled.ShutterSpeed),
            SettingSpec("exposure_bias", "EV", Icons.Filled.Exposure),
            SettingSpec("white_balance", "Баланс белого", Icons.Filled.WbSunny),
            SettingSpec("metering_mode", "Замер", Icons.Filled.CenterFocusWeak),
        ),
    ),
)

private val MODE_LABELS = mapOf(
    "VIDEO_NORMAL" to "Видео",
    "VIDEO_PURE" to "PureVideo",
    "VIDEO_SLOW_MOTION" to "Замедление",
    "VIDEO_TIMESHIFT" to "TimeShift",
    "VIDEO_TIMELAPSE" to "Таймлапс",
    "VIDEO_LOOPRECORDING" to "Петля",
    "VIDEO_SUPER" to "FreeFrame",
    "PHOTO_SINGLE" to "Фото",
    "PHOTO_BURST" to "Серия",
    "PHOTO_INTERVAL" to "Интервал",
    "PHOTO_STARLAPSE" to "Starlapse",
)

private val PHOTO_SIZES = mapOf(
    "8192_6144" to "50 МП 4:3", "8192_4608" to "37 МП 16:9", "4096_3072" to "12.5 МП 4:3", "4000_2250" to "9 МП 16:9",
)

/** «600» seconds → «10 мин»; 0 = no limit. */
private fun durationLabel(seconds: String): String {
    val s = seconds.toIntOrNull() ?: return seconds
    return when {
        s == 0 -> "Без огранич."
        s % 3600 == 0 -> "${s / 3600} ч"
        s % 60 == 0 -> "${s / 60} мин"
        else -> "$s с"
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
    value.startsWith("CODE_") -> "код ${value.removePrefix("CODE_")}" // not mapped yet (see CLAUDE.md)
    param == "record_resolution" -> value.substringBeforeLast('_').let { size ->
        sizeLabel(size) + (if (aspectOf(size) == "16:9") "" else " " + aspectOf(size)) + " · " + value.substringAfterLast('_')
    }
    param == "fov_type" -> mapOf(
        "MEGA_VIEW" to "MegaView", "ULTRAWIDE" to "Сверхширокий", "DEWARP" to "Без дисторсии", "LINEAR" to "Линейный",
        "ACTION_VIEW" to "ActionView", "LINEARHORIZON_45" to "Горизонт 45°", "LINEARHORIZON_360" to "Горизонт 360°",
    )[value] ?: value
    param == "flowstate_level" -> mapOf(
        "FLOW_STATE_LEVEL_CV5_CLOSE" to "Выкл", "FLOW_STATE_LEVEL_CV5_SLIGHT" to "Слабая",
        "FLOW_STATE_LEVEL_CV5_GENERAL" to "Стандарт", "FLOW_STATE_LEVEL_CV5_STRONG" to "Высокая",
    )[value] ?: value
    param == "sharpness_level" -> mapOf("LOW" to "Низкая", "MEDIUM" to "Средняя", "HIGH" to "Высокая")[value] ?: value
    param == "filter_mode" -> mapOf(
        "STANDARD" to "Стандарт", "VIVID" to "Яркий", "VINTAGE" to "Винтажная плёнка", "URBAN" to "Контрастная плёнка", "FILM" to "Плёнка",
        "NIGHT" to "Ночная плёнка", "GLOW" to "Сияние", "SELFIE" to "Селфи", "FROSTEDCCD" to "Матовый CCD",
        "AMBERCCD" to "Янтарный CCD", "BLUECCD" to "Синий CCD",
    )[value]
        ?: value.lowercase().replaceFirstChar { it.uppercase() }
    param == "metering_mode" -> mapOf("EVALUATIVE" to "Матрица", "FACE_PRIORITY" to "По лицу")[value] ?: value
    param == "white_balance" -> if (value == "0") "Авто" else "${value}K"
    param == "exposure_bias" -> SettingCodes.tenthsToEv(SettingCodes.evToTenths(value))
    param == "photo_resolution" -> PHOTO_SIZES[value] ?: value.replace('_', '×')
    param == "raw_capture_type" -> mapOf("PURESHOT" to "PureShot", "PURESHOT_RAW" to "PureShot + RAW")[value] ?: value
    param == "export_type" -> mapOf(
        "STARLAPSE_VIDEO" to "Видео", "STAR_TRAILS_VIDEO" to "Следы звёзд, видео", "STAR_TRAILS_PHOTO" to "Следы звёзд, фото",
    )[value] ?: value
    param == "photography_self_timer" -> if (value == "0") "Выкл" else "$value с"
    param == "exposure_program" -> if (value == "FULL_AUTO") "Авто" else "Ручная"
    param == "self_portrait_switch_2" -> mapOf(
        "false" to "Выкл", "self_portrait_whitening_switch" to "Осветить",
        "self_portrait_dermabrasion_switch" to "Сгладить", "self_portrait_derm_white" to "Осветить + сгладить",
    )[value] ?: value
    param == "exposure_iso" -> when {
        value.startsWith("AUTO_") -> value.removePrefix("AUTO_").let { if (it == "0") "Авто" else "Авто ≤$it" }
        else -> value
    }
    param == "exposure_shutter_speed" -> if (value == "0") "Авто" else if (value.startsWith("1/")) value else "$value с"
    param == "video_iso_top_limit" -> if (value == "0") "Нет" else value
    param == "lapse_time" -> "$value с"
    param == "record_duration" -> durationLabel(value)
    param == "burst_capture_params" -> value.split('/').let { if (it.size == 2) "${it[0]} за ${it[1]} с" else value }
    value == "true" -> "Вкл"
    value == "false" -> "Выкл"
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

/** Live preview, shooting modes, record button and settings (recording works over BLE alone). */
@Composable
fun CameraTab(vm: CameraController, onConnect: () -> Unit, onRecord: () -> Unit) {
    val conn = vm.conn
    val linked = conn == ConnState.Connected || conn == ConnState.Remote
    val recording = vm.capture?.recording == true
    val settings = vm.settings
    val conf = vm.conf
    val picture = settings?.mode?.let { SettingCodes.modes[it] }?.takesPicture == true
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewBox(vm, conn, recording, settings, onConnect)

        if (linked && settings != null) {
            Spacer(Modifier.height(12.dp))
            ModeRow(vm, settings, enabled = !recording && vm.settingBusy == null)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                ZoomButton(vm, settings, conf)
            }
            RecordButton(recording = recording, picture = picture, busy = vm.recordBusy || conn is ConnState.Working) {
                if (recording) vm.stopRecording() else onRecord()
            }
            Spacer(Modifier.width(88.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                recording -> "Идёт запись ${formatDuration(vm.capture?.seconds ?: 0)}"
                linked && picture -> "Нажмите, чтобы сделать снимок"
                linked -> "Нажмите, чтобы начать запись"
                else -> "Нажмите — камера проснётся и сразу начнёт запись"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (linked && settings != null) {
            Spacer(Modifier.height(16.dp))
            SettingsGrid(vm, settings, conf, enabled = !recording)
            if (conn == ConnState.Connected) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Пока идёт трансляция, меню на самой камере заблокировано — " +
                        "чтобы менять настройки кнопками камеры, перейдите на вкладку «Файлы».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (conn is ConnState.Failed) {
            Spacer(Modifier.height(16.dp))
            Text(conn.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (!linked && conn !is ConnState.Working) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Для записи хватает Bluetooth — это быстро, Wi-Fi не нужен. " +
                    "Для трансляции и настроек нужно подключение.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onConnect) { Text("Подключиться с трансляцией") }
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
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (conn) {
            ConnState.Connected -> LivePreview(vm)
            is ConnState.Working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(conn.step, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
            ConnState.Remote -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Трансляция — при подключении по Wi-Fi", color = Color.White)
                TextButton(onConnect) { Text("Подключиться по Wi-Fi") }
            }
            else -> Text("Камера не подключена", color = Color.White)
        }
        vm.liveError?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        if (recording) {
            Text(
                "● REC ${formatDuration(vm.capture?.seconds ?: 0)}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(RecordRed, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (settings?.batteryPercent != null || settings?.podBatteryPercent != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                settings.batteryPercent?.let { BatteryLabel("Камера", it, settings.charging) }
                if (settings.batteryPercent != null && settings.podBatteryPercent != null) Spacer(Modifier.width(10.dp))
                settings.podBatteryPercent?.let { BatteryLabel("Pod", it, settings.podCharging) }
            }
        }
    }
}

/** «Видео | Фото» switch, then the modes of that kind (like the camera menu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeRow(vm: CameraController, settings: CamSettings, enabled: Boolean) {
    val photo = settings.mode?.let { SettingCodes.modes[it]?.photo } == true
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false to "Видео", true to "Фото").forEachIndexed { i, (kind, label) ->
            SegmentedButton(
                selected = photo == kind,
                onClick = { if (photo != kind) vm.selectMode(if (kind) "PHOTO_SINGLE" else "VIDEO_NORMAL") },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                enabled = enabled,
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(SettingCodes.modes.filterValues { it.photo == photo }.keys.toList()) { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = { if (settings.mode != mode) vm.selectMode(mode) },
                enabled = enabled,
                label = { Text(MODE_LABELS[mode] ?: mode) },
            )
        }
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
            "Режим камеры не распознан (${settings.videoSub}/${settings.photoSub})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        conf == null -> Text(
            "Подключитесь по Wi-Fi один раз — приложение загрузит с камеры список настроек.",
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
                                value = if (locked(spec.param, settings.values) && spec.param != "exposure_bias") "Авто"
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
                    onPick = { vm.setSetting(spec.param, it); open = null },
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
    onDismiss: () -> Unit,
) {
    // Fully expanded and scrollable: the frame-rate row must not end up under the navigation bar.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(spec.icon, null)
                Spacer(Modifier.width(10.dp))
                Text(spec.title, style = MaterialTheme.typography.titleLarge)
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
                    Text("Формат кадра", style = MaterialTheme.typography.labelLarge)
                    OptionChips(aspects, aspect, { it }) { a ->
                        aspect = a
                        if (size == null || aspectOf(size!!) != a) size = allSizes.firstOrNull { aspectOf(it) == a }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text("Разрешение", style = MaterialTheme.typography.labelLarge)
                OptionChips(sizes, size, ::sizeLabel) { size = it }
                Spacer(Modifier.height(12.dp))
                Text("Кадров в секунду", style = MaterialTheme.typography.labelLarge)
                val rates = options.filter { it.substringBeforeLast('_') == size }
                    .sortedBy { it.substringAfterLast('_').toIntOrNull() ?: 0 }
                OptionChips(rates, current, { it.substringAfterLast('_') }, enabled = canSet) { onPick(it) }
                if (size != currentSize) {
                    Text(
                        if (size == null) "Выберите разрешение" else "Выберите частоту кадров — настройка применится сразу",
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
            "Серые значения камера поддерживает, но приложение пока не умеет их выставлять — выберите на камере",
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
        if (charging) "заряжается" else null,
        Modifier.size(14.dp),
        tint = if (charging) Color(0xFF7CDB7C) else Color.White,
    )
    Spacer(Modifier.width(2.dp))
    Text("$name $percent%", color = Color.White, fontSize = 12.sp)
}

/** Red dot — record, red square — stop, white disc — take a picture (photo / burst). */
@Composable
private fun RecordButton(recording: Boolean, picture: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(84.dp)
            .border(4.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(36.dp))
            recording -> Box(Modifier.size(30.dp).background(RecordRed, RoundedCornerShape(6.dp)))
            picture -> Box(
                Modifier.size(62.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(Color.White, CircleShape),
            )
            else -> Box(Modifier.size(62.dp).background(RecordRed, CircleShape))
        }
    }
}

/** SurfaceView for the hardware decoder; the stream starts when the surface exists and stops with it. */
@Composable
private fun LivePreview(vm: CameraController) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = vm.attachLive(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = vm.detachLive()
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
