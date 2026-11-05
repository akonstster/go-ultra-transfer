package app.goultra.transfer.camera.osc

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import app.goultra.transfer.BuildConfig
import app.goultra.transfer.camera.CameraLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket

/**
 * Other-camera (alpha) session. It works when the user has joined the camera's Wi-Fi by hand: the camera's network is
 * found among the phone's networks by its subnet (every Insta360 access point hands out `192.168.42.…` addresses and
 * the camera answers at `192.168.42.1`), so nothing model-specific is needed to reach it. Everything here is
 * read-only: listing, downloading and profile collection — no settings are ever written in the alpha.
 */
class OscSession(
    context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        /** The subnet of every Insta360 access point (the camera itself is `192.168.42.1`). */
        const val CAMERA_SUBNET = "192.168.42."
        private const val PAGE = 200
        private const val MAX_PAGES = 50
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var link: CameraLink? = null
        private set

    /** True when the whole process had to be bound to the camera network (socket binding failed, EMUI). */
    private var processBound = false

    /** The phone's network that looks like a camera access point, or null. */
    private fun findCameraNetwork(): Network? {
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val wifi = all.filter { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi.firstOrNull { isCameraSubnet(cm.getLinkProperties(it)) }
    }

    private fun isCameraSubnet(lp: LinkProperties?): Boolean =
        lp?.linkAddresses?.any { it.address.hostAddress?.startsWith(CAMERA_SUBNET) == true } == true

    /**
     * Finds the camera's network and probes `/osc/info`. Null when the phone isn't on a camera's Wi-Fi or the camera
     * doesn't answer the OSC API (a GO-family camera, a wrong network).
     */
    suspend fun connect(): OscInfo? = withContext(Dispatchers.IO) {
        release()
        val network = findCameraNetwork() ?: run {
            log("другая камера: телефон не в сети камеры (нет адреса ${CAMERA_SUBNET}x)")
            return@withContext null
        }
        val bindable = runCatching { DatagramSocket().use { network.bindSocket(it) } }.isSuccess
        processBound = if (bindable) false else cm.bindProcessToNetwork(network)
        if (!bindable) log("другая камера: сокеты к сети не привязываются — привязан весь процесс: $processBound")
        val l = CameraLink(network, processBound = !bindable)
        link = l
        val info = runCatching { OscParsing.info(OscClient(l, log).info()) }
            .onFailure { log("другая камера: /osc/info не ответил: ${it.message}") }
            .getOrNull()
        if (info == null) {
            release()
            return@withContext null
        }
        log("другая камера: ${info.model} ${info.firmware}")
        info
    }

    suspend fun state(): OscState? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null
        runCatching { OscParsing.state(OscClient(l, log).state()) }
            .onFailure { log("другая камера: /osc/state не ответил: ${it.message}") }
            .getOrNull()
    }

    /** The whole file list as shots (pages of [PAGE] until the camera stops returning entries). */
    suspend fun listShots(): List<OscShot> = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext emptyList()
        val client = OscClient(l, log)
        val files = mutableListOf<OscFile>()
        var start = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = client.listFiles(entryCount = PAGE, startPosition = start.takeIf { it > 0 })
            val got = OscParsing.files(page)
            files += got
            start += got.size
            pages++
            val total = OscParsing.totalEntries(page)
            if (got.isEmpty()) break
            if (total != null && start >= total) break
            if (got.size < PAGE) break
        }
        log("другая камера: файлов получено ${files.size} (страниц $pages)")
        OscParsing.groupIntoShots(files)
    }

    /** A text report about this camera for the alpha feedback (no secrets: the serial is masked). */
    suspend fun profile(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("GO Ultra Transfer ").append(BuildConfig.VERSION_NAME).append(" — camera profile (alpha)\n")
        sb.append("phone: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append(", Android SDK ").append(android.os.Build.VERSION.SDK_INT)
            .append('\n')
        val l = link
        if (l == null) {
            sb.append("camera: not connected (no ${CAMERA_SUBNET}x network)\n")
            return@withContext sb.toString()
        }
        val client = OscClient(l, log)
        val info = runCatching { OscParsing.info(client.info()) }.getOrNull()
        if (info != null) {
            sb.append("model: ").append(info.model).append('\n')
            sb.append("firmware: ").append(info.firmware).append('\n')
            sb.append("serial: ").append(info.maskedSerial).append('\n')
        }
        sb.append("\n--- /osc/info ---\n").append(raw { client.info() })
        sb.append("\n--- /osc/state ---\n").append(raw { client.state() })
        sb.append("\n--- camera.getOptions ---\n")
            .append(raw { client.getOptions(listOf("captureMode", "batteryStatus", "remainingSpace", "totalSpace")) })
        sb.append("\n--- camera.listFiles (first 20) ---\n").append(raw { client.listFiles(entryCount = 20) })
        sb.append('\n')
        sb.toString()
    }

    private inline fun raw(block: () -> Any?): String =
        runCatching { block()?.toString() ?: "(empty)" }
            .getOrElse { "(error: ${it.message})" }

    /** Forgets the camera's network (unbinds the process when it had to be bound). */
    fun release() {
        if (processBound) {
            runCatching { cm.bindProcessToNetwork(null) }
            processBound = false
        }
        link = null
    }
}
