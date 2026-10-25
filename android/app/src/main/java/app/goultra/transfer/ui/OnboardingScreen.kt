package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CameraArt(Modifier.fillMaxWidth(0.55f).widthIn(max = 300.dp), withPod = true, blinking = controller.setup is ConnState.Working)
            Spacer(Modifier.height(24.dp))
            Text(tr("Подключение камеры", "Connect the camera"), style = MaterialTheme.typography.headlineSmall)
            PhoneRadiosBanner(controller)
            Spacer(Modifier.height(24.dp))
            when (val state = controller.setup) {
                is ConnState.Working -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(24.dp))
                    Text(
                        state.step,
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
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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
        }
    }
}
