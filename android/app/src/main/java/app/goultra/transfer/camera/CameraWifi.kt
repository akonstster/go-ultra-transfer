package app.goultra.transfer.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.MacAddress
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
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

    private val prefs = context.getSharedPreferences("camera", 0)

    /**
     * A request naming the access point's BSSID too (remembered from the first connection) is auto-approved by
     * Android: no device-selection dialog. EMUI shows that dialog on every name-only request, even when it connects
     * by itself, and tapping the stale dialog drops the link. If the remembered BSSID doesn't match any more, the
     * name-only request (with the dialog) follows.
     */
    suspend fun connect(creds: WifiCreds, timeoutMs: Int = 60_000): CameraLink? {
        val key = "bssid:${creds.ssid}"
        val saved = prefs.getString(key, null)?.let { runCatching { MacAddress.fromString(it) }.getOrNull() }
        if (saved != null) {
            connectOnce(creds, saved, timeoutMs)?.let { return it }
            log("Wi-Fi камеры: по запомненному BSSID не подключилось — забываю его")
            prefs.edit().remove(key).apply()
        }
        return connectOnce(creds, null, timeoutMs)
    }

    private suspend fun connectOnce(creds: WifiCreds, bssid: MacAddress?, timeoutMs: Int): CameraLink? {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(creds.ssid)
            .apply { if (bssid != null) setBssid(bssid) }
            .setWpa2Passphrase(creds.password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val result = CompletableDeferred<Network?>()
        // Location info in the callback (Android 12+): the BSSID to remember; needs the location permission.
        val flags = if (Build.VERSION.SDK_INT >= 31) ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
        val cb = object : ConnectivityManager.NetworkCallback(flags) {
            override fun onAvailable(n: Network) {
                result.complete(n)
            }

            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (bssid != null || Build.VERSION.SDK_INT < 31) return
                val seen = (caps.transportInfo as? WifiInfo)?.bssid?.takeIf { it != "02:00:00:00:00:00" } ?: return
                if (prefs.getString("bssid:${creds.ssid}", null) != seen) {
                    prefs.edit().putString("bssid:${creds.ssid}", seen).apply()
                    log("Wi-Fi камеры: BSSID запомнен — дальше без системного запроса")
                }
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
        log(if (bssid != null) "подключение к «${creds.ssid}» по запомненному BSSID…" else "подключение к «${creds.ssid}» (подтвердите системный запрос)…")
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
