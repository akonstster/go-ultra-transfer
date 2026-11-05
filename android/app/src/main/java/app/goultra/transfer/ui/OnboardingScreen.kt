package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private fun steps() = listOf(
    tr("Включите камеру кнопкой и положите рядом с телефоном.", "Switch the camera on with its button and put it next to the phone."),
    tr("Нажмите «Найти камеру».", "Tap «Find camera»."),
    tr(
        "Появится запрос на сопряжение — он приходит в шторку уведомлений. " +
            "Откройте шторку и нажмите на него (на это есть 30 секунд).",
        "A pairing request appears — it comes as a notification. " +
            "Open the notification shade and tap it (you have 30 seconds).",
    ),
    tr("Подтвердите подключение на экране камеры.", "Confirm the connection on the camera's screen."),
)

/** First run: bond with the camera and authorize this phone, so later it can be woken up over Bluetooth. */
@Composable
fun OnboardingScreen(
    controller: CameraController,
    hasPermissions: Boolean,
    onStart: () -> Unit,
    onShareLog: () -> Unit,
    /** Opens the «other cameras» (alpha) instructions for the picked series. */
    onOtherCameras: (OtherSeries?) -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The wizard stays centred in the space above «Пропустить», which sits at the bottom.
            Spacer(Modifier.weight(1f))
            CameraArt(Modifier.fillMaxWidth(0.55f).widthIn(max = 300.dp), withPod = true, blinking = controller.setup is ConnState.Working)
            Spacer(Modifier.height(24.dp))
            Text(tr("Подключение камеры", "Connect the camera"), style = MaterialTheme.typography.headlineSmall)
            PhoneRadiosBanner(controller)
            Spacer(Modifier.height(24.dp))
            val choices = controller.cameraChoices
            when (val state = controller.setup) {
                // Several cameras around (another Insta360 nearby): which one is this phone's.
                is ConnState.Working -> if (choices != null) {
                    Text(
                        tr("Рядом несколько камер. Выберите свою — название есть на её экране и коробке:",
                            "Several cameras nearby. Pick yours — its name is on its screen and box:"),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { name ->
                            OutlinedButton({ controller.chooseCamera(name) }, Modifier.fillMaxWidth()) { Text("GO Ultra $name") }
                        }
                    }
                    TextButton({ controller.chooseCamera(null) }) { Text(tr("Отмена", "Cancel")) }
                } else {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        state.step.text,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                ConnState.Connected -> {
                    Text(
                        tr(
                            "Готово! Камера запомнила этот телефон.\n\n" +
                                "Дальше включать её не нужно: приложение само разбудит камеру по Bluetooth.",
                            "Done! The camera remembers this phone.\n\n" +
                                "From now on there is no need to switch it on: the app wakes it over Bluetooth.",
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(controller::finishOnboarding) { Text(tr("К файлам", "To files")) }
                }
                else -> {
                    Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        steps().forEachIndexed { i, text ->
                            Row {
                                Text("${i + 1}.", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(text)
                            }
                        }
                    }
                    if (state is ConnState.Failed) {
                        Spacer(Modifier.height(16.dp))
                        Text(state.message.text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onStart) {
                        Text(
                            when {
                                !hasPermissions -> tr("Разрешить доступ и найти камеру", "Allow access and find the camera")
                                state is ConnState.Failed -> tr("Повторить", "Try again")
                                else -> tr("Найти камеру", "Find camera")
                            },
                        )
                    }
                    if (state is ConnState.Failed) {
                        TextButton(onShareLog) { Text(tr("Отправить журнал", "Send log")) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // No camera (yet): use the app with the phone's files; the camera can be set up later from menu ⋮.
            if (controller.setup !is ConnState.Working && controller.setup != ConnState.Connected) {
                TextButton(controller::skipOnboarding) { Text(tr("Пропустить", "Skip")) }
            }
            OtherCamerasPicker(controller, onOtherCameras)
        }
    }
}

/**
 * The alpha corner of the wizard: pick another Insta360 series (or «another model») and open the download
 * instructions. The GO family speaks its own protocol, so picking it shows what to do instead.
 */
@Composable
private fun OtherCamerasPicker(controller: CameraController, onPick: (OtherSeries?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var goInfo by remember { mutableStateOf(false) }
    if (goInfo) {
        GoFamilyDialog(
            onDismiss = { goInfo = false },
            onConnect = {
                goInfo = false
                // Leave the wizard and connect at once: the app picks up the Wi-Fi the user joined by hand.
                controller.skipOnboarding()
                controller.connect()
            },
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        tr("Экспериментально — поддержка других камер", "Experimental — support for other cameras"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Box {
        OutlinedButton({ open = true }, Modifier.widthIn(max = 360.dp)) {
            Text(tr("Выбрать серию камеры", "Choose the camera series"))
        }
        DropdownMenu(open, { open = false }) {
            OtherSeries.entries.forEach { series ->
                DropdownMenuItem(
                    text = { Text(seriesLabel(series)) },
                    // The OSC instructions are for X/ONE/Ace; a GO 2/3/3S works over the network joined by hand.
                    onClick = { open = false; if (series == OtherSeries.GO) goInfo = true else onPick(series) },
                )
            }
        }
    }
}

/** What to do when the user picks GO 2/3/3S: the app picks up the camera's Wi-Fi joined by hand. */
@Composable
private fun GoFamilyDialog(onDismiss: () -> Unit, onConnect: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(seriesLabel(OtherSeries.GO)) },
        text = {
            Text(
                tr(
                    "Включите камеру и подключитесь к её Wi-Fi (пароль — на экране камеры, обычно 88888888), " +
                        "затем нажмите «Подключиться».",
                    "Switch the camera on and join its Wi-Fi (the password is on the camera's screen, usually " +
                        "88888888), then tap «Connect».",
                ),
            )
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Закрыть", "Close")) } },
        confirmButton = { TextButton(onConnect) { Text(tr("Подключиться", "Connect")) } },
    )
}
