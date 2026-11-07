package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.CardInfo
import app.goultra.transfer.camera.CardState

/** Menu ⋮ → «Камера»: which camera this phone uses, its state, firmware and serial; connect / disconnect. */
@Composable
fun CameraInfoDialog(vm: CameraController, onDismiss: () -> Unit) {
    LaunchedEffect(vm.conn) { vm.loadCameraInfo() }
    val conn = vm.conn
    val info = vm.cameraInfo
    val live = conn == ConnState.Connected || conn == ConnState.Remote
    val s = vm.settings?.takeIf { live }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { CameraArt(Modifier.widthIn(max = 120.dp), withPod = false, blinking = conn is ConnState.Working) },
        title = { Text("GO Ultra" + (vm.cameraName?.let { " $it" } ?: "")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (conn) {
                        ConnState.Connected -> tr("Подключена (Wi-Fi и Bluetooth)", "Connected (Wi-Fi and Bluetooth)")
                        ConnState.Remote -> tr("Подключена только по Bluetooth", "Connected over Bluetooth only")
                        is ConnState.Working -> tr("Подключение…", "Connecting…")
                        else -> tr("Не подключена", "Not connected")
                    },
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(4.dp))
                info?.model?.let { InfoRow(tr("Модель", "Model"), it) }
                info?.firmware?.let { InfoRow(tr("Прошивка", "Firmware"), it) }
                info?.serial?.let { InfoRow(tr("Серийный номер", "Serial number"), it) }
                s?.batteryPercent?.let { InfoRow(tr("Заряд камеры", "Camera battery"), "$it %" + if (s.charging) tr(", заряжается", ", charging") else "") }
                s?.podBatteryPercent?.let { InfoRow(tr("Заряд Pod", "Pod battery"), "$it %" + if (s.podCharging) tr(", заряжается", ", charging") else "") }
                s?.card?.let { InfoRow(tr("Карта памяти", "Memory card"), cardLine(it, vm.cardState)) }
                if (info == null && !live) {
                    Text(
                        tr("Подключитесь, чтобы увидеть прошивку, заряд и карту.", "Connect to see the firmware, battery and card."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (live || conn is ConnState.Working) {
                TextButton({ vm.disconnect() }) { Text(tr("Отключить", "Disconnect")) }
            } else {
                TextButton({ vm.showLinkHints(); vm.connect() }) { Text(tr("Подключить", "Connect")) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value)
    }
}

/**
 * The card's row: free of total plus the camera's own reserve (exactly 10 % — it never records into that part,
 * which is why a 62.5 GB card refuses to record while «free» still shows a gigabyte), or the trouble in words.
 */
private fun cardLine(c: CardInfo, state: CardState): String = buildString {
    when {
        // Zero sizes with an «OK» state = no usable card (the state itself arrives only on a change).
        state == CardState.NO_CARD || (state == CardState.OK && c.totalBytes == 0L) -> append(tr("нет карты", "no card"))
        state == CardState.UNUSABLE -> append(tr("карта не читается", "card unreadable"))
        state == CardState.WRITE_ERROR -> append(tr("ошибка карты", "card error"))
        state == CardState.FULL -> {
            append(tr("карта заполнена", "card full"))
            append(" · ")
            append(formatSize(c.freeBytes))
        }
        else -> {
            append(formatSize(c.freeBytes))
            append(tr(" свободно из ", " free of "))
            append(formatSize(c.totalBytes))
            if (c.reserveBytes > 0) {
                append(tr(" (резерв камеры ", " (camera reserve "))
                append(formatSize(c.reserveBytes))
                append(")")
            }
        }
    }
}
