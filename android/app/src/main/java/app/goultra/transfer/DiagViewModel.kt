package app.goultra.transfer

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.goultra.transfer.camera.CameraBle
import app.goultra.transfer.camera.CameraSession
import app.goultra.transfer.camera.CameraTcp
import app.goultra.transfer.camera.CameraWifi
import app.goultra.transfer.camera.Downloader
import app.goultra.transfer.camera.WifiCreds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Step-by-step diagnostic flow: BLE → wake → auth → Wi-Fi password → Wi-Fi → file list → download. */
class DiagViewModel(app: Application) : AndroidViewModel(app) {
    val log = mutableStateListOf<String>()
    var busy by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ble = CameraBle(app, ::addLog)
    private val wifi = CameraWifi(app, ::addLog)
    private var creds: WifiCreds? = null
    private var tcp: CameraTcp? = null
    private var files: List<String> = emptyList()

    /** Authorization id the camera remembers for this install. */
    private val authId: String by lazy { CameraSession.authId(app) }

    fun addLog(msg: String) {
        Log.i("GUT", msg)
        val line = "${timeFormat.format(Date())} $msg"
        main.post { log.add(line) }
    }

    private fun launchStep(name: String, block: suspend () -> Boolean) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            addLog("=== $name")
            try {
                addLog(if (block()) "=== $name: OK" else "=== $name: не удалось")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("=== $name: ошибка ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun connectBle() = launchStep("1. BLE") { stepConnect() }
    fun bond() = launchStep("Сопряжение") { ble.bond() }
    fun wake() = launchStep("2. Разбудить") { stepWake() }
    fun authorize() = launchStep("3. Авторизация") { stepAuth() }
    fun wifiPassword() = launchStep("4. Пароль Wi-Fi") { stepCreds() }
    fun connectWifi() = launchStep("5. Wi-Fi") { stepWifi() }
    fun fileList() = launchStep("6. Список файлов") { stepList() }
    fun downloadPhoto() = launchStep("7. Скачать фото") { stepDownload() }

    fun runAll() = launchStep("Всё подряд") {
        stepConnect() && stepWake() && stepAuth() && stepCreds() && stepWifi() && stepList() && stepDownload()
    }

    fun disconnectAll() {
        tcp?.close()
        tcp = null
        wifi.release()
        ble.disconnect()
        addLog("всё отключено")
    }

    private suspend fun stepConnect(): Boolean {
        val r = ble.scan() ?: run { addLog("камера не найдена"); return false }
        return ble.connect(r.device)
    }

    private suspend fun stepWake(): Boolean {
        if (ble.waitSync(2_500)) {
            addLog("камера не спит")
            return true
        }
        return ble.wake(authId)
    }

    private suspend fun stepAuth() = ble.authorize(authId)

    private suspend fun stepCreds(): Boolean {
        val c = ble.wifiCredentials() ?: return false
        creds = c
        addLog("Wi-Fi: «${c.ssid}», канал ${c.channel}, пароль получен (${c.password.length} симв.)")
        return true
    }

    private suspend fun stepWifi(): Boolean {
        val c = creds ?: run { addLog("сначала шаг 4"); return false }
        return wifi.connect(c) != null
    }

    private suspend fun stepList(): Boolean {
        val link = wifi.link ?: run { addLog("сначала шаг 5"); return false }
        tcp?.close()
        val t = CameraTcp(link, ::addLog)
        tcp = t
        if (!t.open()) return false
        files = t.fileList() ?: return false
        addLog("файлов: ${files.size}, последние:")
        files.takeLast(5).forEach { addLog("  $it") }
        return true
    }

    private suspend fun stepDownload(): Boolean {
        val link = wifi.link ?: run { addLog("сначала шаг 5"); return false }
        val uri = files.lastOrNull { it.contains("/IMG_") && it.endsWith(".jpg", ignoreCase = true) }
            ?: run { addLog("в списке нет фото (сначала шаг 6)"); return false }
        addLog("скачиваю $uri")
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        val item = Downloader.download(getApplication(), link, uri) { done, total ->
            bytes = done
            addLog("  $done / $total")
        }
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        addLog("сохранено: $item, %.1f с, %.1f МБ/с".format(sec, bytes / 1e6 / sec.coerceAtLeast(0.001)))
        return true
    }

    override fun onCleared() {
        disconnectAll()
    }
}
