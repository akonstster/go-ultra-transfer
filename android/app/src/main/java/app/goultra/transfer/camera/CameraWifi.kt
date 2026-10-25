package app.goultra.transfer.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.net.DatagramSocket

/**
 * Joins the camera's access point (no internet) via WifiNetworkSpecifier.
 * Traffic goes through [link]: sockets bound to the camera network, the phone's default network stays untouched.
 */
class CameraWifi(
    context: Context,
    private val log: (String) -> Unit,
    private val onLost: () -> Unit = {},
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var processBound = false

    @Volatile var link: CameraLink? = null
        private set

    suspend fun connect(creds: WifiCreds, timeoutMs: Int = 60_000): CameraLink? {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(creds.ssid)
            .setWpa2Passphrase(creds.password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val result = CompletableDeferred<Network?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                result.complete(n)
            }

            override fun onUnavailable() {
                log("Wi-Fi камеры недоступен (отказ или таймаут)")
                result.complete(null)
            }

            override fun onLost(n: Network) {
                log("Wi-Fi камеры потерян")
                if (link?.network == n) {
                    link = null
                    onLost()
                }
            }
        }
        callback = cb
        log("подключение к «${creds.ssid}» (подтвердите системный запрос)…")
        cm.requestNetwork(request, cb, timeoutMs)
        val n = result.await() ?: return null
        log("Wi-Fi камеры подключён (сеть $n)")
        return CameraLink(n, processBound = !waitBindable(n)).also { link = it }
    }

    /**
     * Right after onAvailable some firmwares (Huawei EMUI 15) still refuse to bind sockets to the network (EPERM).
     * Wait a little; if binding never works, bind the whole process to the camera network instead.
     */
    private suspend fun waitBindable(n: Network): Boolean {
        var lastError: String? = null
        repeat(20) {
            val error = runCatching { DatagramSocket().use { s -> n.bindSocket(s) } }.exceptionOrNull()
            if (error == null) {
                if (lastError != null) log("привязка к сети заработала через ${it * 250} мс")
                return true
            }
            if (lastError == null) log("привязка сокета к сети не прошла: ${error.message}")
            lastError = error.message
            delay(250)
        }
        processBound = cm.bindProcessToNetwork(n)
        log("привязка сокетов так и не заработала — весь процесс привязан к сети камеры: $processBound")
        return false
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        link = null
        if (processBound) {
            cm.bindProcessToNetwork(null)
            processBound = false
        }
    }
}
