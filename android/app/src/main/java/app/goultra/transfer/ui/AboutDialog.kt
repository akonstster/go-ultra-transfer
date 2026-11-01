package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.goultra.transfer.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun formatBuildTime(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS XXX"))

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val built = remember {
        context.assets.open("build-info.txt").bufferedReader().use { it.readText().trim().toLong() }
    }
    val time = remember(built) { formatBuildTime(built, ZoneId.systemDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("О приложении", "About")) },
        text = {
            Column {
                Text("GO Ultra Transfer")
                Spacer(Modifier.height(12.dp))
                Text("${tr("Версия", "Version")}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
                Text("${tr("Номер сборки", "Build number")}: $built")
                Text("${tr("Дата и время сборки", "Build date and time")}: $time")
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
}
