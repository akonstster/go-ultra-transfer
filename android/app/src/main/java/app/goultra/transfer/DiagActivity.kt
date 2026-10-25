package app.goultra.transfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Step-by-step protocol diagnostics with a raw log (also in logcat, tag GUT). */
class DiagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { DiagScreen() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagScreen(vm: DiagViewModel = viewModel()) {
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.addLog("разрешения: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
    }

    val listState = rememberLazyListState()
    LaunchedEffect(vm.log.size) {
        if (vm.log.isNotEmpty()) listState.scrollToItem(vm.log.size - 1)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = !vm.busy
            Button(vm::runAll, enabled = enabled) { Text("▶ Всё подряд") }
            StepButton("1 BLE", enabled, vm::connectBle)
            StepButton("Сопряжение", enabled, vm::bond)
            StepButton("2 Разбудить", enabled, vm::wake)
            StepButton("3 Авторизация", enabled, vm::authorize)
            StepButton("4 Пароль", enabled, vm::wifiPassword)
            StepButton("5 Wi-Fi", enabled, vm::connectWifi)
            StepButton("6 Список", enabled, vm::fileList)
            StepButton("7 Фото", enabled, vm::downloadPhoto)
            StepButton("Отключить", true, vm::disconnectAll)
            StepButton("Разрешения", true) { permissions.launch(requiredPermissions()) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(vm.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(text) }
}
