package app.goultra.transfer.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.goultra.transfer.camera.osc.OscShot

/**
 * «Other cameras» (alpha): download files from an Insta360 camera this app has not been built around. The user joins
 * the camera's Wi-Fi by hand, the screen finds it on `192.168.42.1`, lists the files (OSC HTTP API) and saves the
 * chosen ones into the gallery through the same downloader the GO Ultra uses. Control and editing stay out of scope.
 */
@Composable
fun OscScreen(osc: OscController, onClose: () -> Unit, onShareProfile: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад", "Back")) }
            Text(
                tr("Другие камеры (альфа)", "Other cameras (alpha)"),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            item { Instructions() }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = { osc.search() }, enabled = !osc.busy) {
                        Text(tr("Найти камеру", "Find the camera"))
                    }
                    OutlinedButton(onClick = { osc.refreshList() }, enabled = !osc.busy && osc.info != null) {
                        Text(tr("Обновить список", "Refresh"))
                    }
                    TextButton(onClick = { osc.collectProfile(onShareProfile) }) {
                        Text(tr("Отправить профиль камеры", "Send camera profile"))
                    }
                }
            }

            val info = osc.info
            if (info != null) {
                item {
                    val state = osc.state
                    val battery = state?.batteryLevel?.let { "%.0f %%".format(it * 100) } ?: "—"
                    val card = state?.cardState ?: "—"
                    Text(
                        "${info.label} · ${info.firmware.ifBlank { "?" }} · ${tr("батарея", "battery")} $battery · SD $card",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            items(osc.shots, key = { it.name }) { shot ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Checkbox(checked = shot.name in osc.selected, onCheckedChange = { osc.toggle(shot) })
                    Column(Modifier.weight(1f)) {
                        Text(shot.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val size = shot.totalSize?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                        val kind = when {
                            shot.is360 -> "360°"
                            shot.isVideo -> tr("видео", "video")
                            else -> tr("фото", "photo")
                        }
                        val parts = if (shot.files.size > 1) " · ${shot.files.size} ${tr("частей", "files")}" else ""
                        Text(
                            "$kind · $size$parts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    if (osc.busy && osc.totalFiles > 0) {
                        LinearProgressIndicator(
                            progress = { osc.doneFiles.toFloat() / osc.totalFiles.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val current = if (osc.currentTotal > 0) {
                            Formatter.formatShortFileSize(context, osc.currentDone) + " / " +
                                Formatter.formatShortFileSize(context, osc.currentTotal)
                        } else {
                            ""
                        }
                        Text(
                            tr("Скачиваю: %s из %s", "Downloading: %s of %s").format(osc.doneFiles, osc.totalFiles) +
                                if (current.isNotEmpty()) " · $current" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (osc.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (osc.stage) {
                                    OscController.Stage.SEARCHING -> tr("ищу камеру…", "looking for the camera…")
                                    else -> tr("читаю список…", "reading the list…")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    osc.lastResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    osc.message?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (osc.stage == OscController.Stage.ERROR) {
                        Text(
                            tr(
                                "Камера не найдена. Телефон точно в сети камеры «…OSC»?",
                                "Camera not found. Is the phone on the camera's «…OSC» network?",
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (osc.shots.isEmpty() && osc.stage == OscController.Stage.READY && !osc.busy) {
                        Text(
                            tr(
                                "Файлов не видно (пустая карта или камера отвечает иначе).",
                                "No files (empty card, or the camera answers differently).",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (osc.logLines.isNotEmpty()) {
                        Text(
                            osc.logLines.takeLast(4).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        if (osc.shots.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { osc.selectAll(osc.selected.size != osc.shots.size) }) {
                    Text(tr("Выбрать все", "Select all"))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { osc.download() }, enabled = !osc.busy) {
                    val n = if (osc.selected.isEmpty()) osc.shots.size else osc.selected.size
                    Text(tr("Скачать (%s)", "Download (%s)").format(n))
                }
            }
        }
    }
}

@Composable
private fun Instructions() {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("Как подключить другую камеру", "Connecting another camera"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                tr(
                    "1. Включите камеру и откройте её точку доступа «…OSC» в настройках Wi-Fi телефона " +
                        "(пароль обычно 88888888).\n" +
                        "2. Вернитесь сюда и нажмите «Найти камеру».\n" +
                        "3. Выберите файлы и скачайте — они сохранятся в DCIM/Camera.\n\n" +
                        "В альфе работают только просмотр списка и скачивание; настройки камеры не меняются. " +
                        "Поддержаны модели с OSC (X3, X4, X5, ONE X2/X3, ONE RS, Ace и другие) — если камера " +
                        "не нашлась, отправьте профиль: это помогает добавить её.",
                    "1. Switch the camera on and join its «…OSC» access point in the phone's Wi-Fi settings " +
                        "(the password is usually 88888888).\n" +
                        "2. Come back here and tap «Find the camera».\n" +
                        "3. Pick files and download — they land in DCIM/Camera.\n\n" +
                        "In the alpha only listing and downloading work; no camera settings are changed. Cameras with " +
                        "the OSC API are supported (X3, X4, X5, ONE X2/X3, ONE RS, Ace and others) — if yours isn't " +
                        "found, send the profile: that is what gets it added.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
