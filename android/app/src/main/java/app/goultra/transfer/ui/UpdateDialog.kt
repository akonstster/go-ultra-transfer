package app.goultra.transfer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * «Доступно обновление»: a newer build of our own channel on GitHub (see [UpdateCheck]). The button opens the
 * release's APK in the browser — installing from unknown sources stays the user's step, as with any sideloaded build.
 */
@Composable
fun UpdateDialog(vm: CameraController, info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Доступно обновление", "An update is available")) },
        text = {
            Column {
                Text(tr("У вас %s, на GitHub — %s", "You have %s, GitHub has %s").format(vm.appVersion, info.tag.trimStart('v')))
                info.name.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton({
                val url = info.apkUrl ?: info.pageUrl
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                vm.dismissUpdate()
            }) { Text(tr("Скачать обновление", "Download the update")) }
        },
        dismissButton = {
            TextButton({ vm.skipUpdate(info.tag) }) { Text(tr("Не напоминать", "Skip this version")) }
            TextButton(onDismiss) { Text(tr("Позже", "Later")) }
        },
    )
}