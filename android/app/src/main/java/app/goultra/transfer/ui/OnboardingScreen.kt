package app.goultra.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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

private val STEPS = listOf(
    "Включите камеру кнопкой и положите рядом с телефоном.",
    "Нажмите «Найти камеру».",
    "Появится запрос на сопряжение — он приходит в шторку уведомлений. " +
        "Откройте шторку и нажмите на него (на это есть 30 секунд).",
    "Подтвердите подключение на экране камеры.",
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
            Text("Подключение камеры", style = MaterialTheme.typography.headlineSmall)
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
                        "Готово! Камера запомнила этот телефон.\n\n" +
                            "Дальше включать её не нужно: приложение само разбудит камеру по Bluetooth.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 400.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(controller::finishOnboarding) { Text("К файлам") }
                }
                else -> {
                    Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        STEPS.forEachIndexed { i, text ->
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
                                !hasPermissions -> "Разрешить доступ и найти камеру"
                                state is ConnState.Failed -> "Повторить"
                                else -> "Найти камеру"
                            },
                        )
                    }
                    if (state is ConnState.Failed) {
                        TextButton(onShareLog) { Text("Отправить журнал") }
                    }
                }
            }
        }
    }
}
