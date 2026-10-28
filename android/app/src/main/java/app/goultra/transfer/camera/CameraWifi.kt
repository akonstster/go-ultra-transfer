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
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    /**
     * The current request's answer (available / unavailable). Taking a request back while the system is still
     * connecting crashes Android 12's Wi-Fi service (its connection timeout fires on a request already cleared:
     * NullPointerException in WifiNetworkFactory, system restart — seen on the Poco). So a request is only taken back
     * once answered; one dropped before that is [abandoned] and taken back when its answer comes.
     */
    @Volatile private var answered: CompletableDeferred<Unit>? = null
    @Volatile private var abandoned = false
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
        // A request still waiting for its answer: wait for it first (see [answered]) — never two at once.
        answered?.takeIf { !it.isCompleted }?.let {
            log("Wi-Fi камеры: жду ответа на прошлый запрос")
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 10_000L) { it.await() }
        }
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
        val answer = CompletableDeferred<Unit>()
        answered = answer
        abandoned = false
        // Location info in the callback (Android 12+): the BSSID to remember; needs the location permission.
        val flags = if (Build.VERSION.SDK_INT >= 31) ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
        val cb = object : ConnectivityManager.NetworkCallback(flags) {
            override fun onAvailable(n: Network) {
                answer.complete(Unit)
                if (takeBackIfAbandoned(this)) return
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
                answer.complete(Unit)
                log("Wi-Fi камеры недоступен (отказ или таймаут)")
                takeBackIfAbandoned(this)
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

    /** [cb]'s answer came after it was dropped: take the request back now. */
    private fun takeBackIfAbandoned(cb: ConnectivityManager.NetworkCallback): Boolean {
        if (!abandoned || callback !== cb) return false
        runCatching { cm.unregisterNetworkCallback(cb) }
        callback = null
        abandoned = false
        log("Wi-Fi камеры: брошенный запрос снят после ответа")
        return true
    }

    fun release() {
        val cb = callback
        if (cb != null && answered?.isCompleted == false) {
            // Still connecting: taken back when the answer comes (see [answered]).
            abandoned = true
            log("Wi-Fi камеры: запрос ещё подключается — сниму после ответа")
        } else {
            cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = null
        }
        link = null
        if (processBound) {
            cm.bindProcessToNetwork(null)
            processBound = false
        }
    }
}
