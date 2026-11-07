package app.goultra.transfer.camera.osc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.goultra.transfer.BuildConfig
import app.goultra.transfer.camera.CameraLink
import app.goultra.transfer.camera.CameraNetworks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Other-camera (alpha) session. It works when the user has joined the camera's Wi-Fi by hand: the camera's network is
 * found among the phone's networks by its subnet (every Insta360 access point hands out `192.168.42.…` addresses and
 * the camera answers at `192.168.42.1`), so nothing model-specific is needed to reach it. Everything here is
 * read-only: listing, downloading and profile collection — no settings are ever written in the alpha.
 */
class OscSession(
    context: Context,
    private val log: (String) -> Unit,
    /** Translation of the journal lines — they are shown on the screen, so they follow the interface language. */
    private val t: (String, String) -> String,
) {
    companion object {
        private const val PAGE = 200
        private const val MAX_PAGES = 50
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var link: CameraLink? = null
        private set

    /** True when the whole process had to be bound to the camera network (socket binding failed, EMUI). */
    private var processBound = false

    /** The phone's network that looks like a camera access point, or null. */
    private fun findCameraNetwork(): Network? = CameraNetworks.find(cm)

    /**
     * Finds the camera's network and probes `/osc/info`. Null when the phone isn't on a camera's Wi-Fi or the camera
     * doesn't answer the OSC API (a GO-family camera, a wrong network).
     */
    suspend fun connect(): OscInfo? = withContext(Dispatchers.IO) {
        release()
        val network = findCameraNetwork() ?: run {
            log(
                t(
                    "другая камера: телефон не в сети камеры (нет адреса ${CameraNetworks.CAMERA_SUBNET}x)",
                    "other camera: the phone is not on a camera network (no ${CameraNetworks.CAMERA_SUBNET}x address)",
                ),
            )
            return@withContext null
        }
        val adoption = CameraNetworks.adopt(cm, network)
        processBound = adoption.boundProcess
        if (!adoption.socketsBindable) {
            log(
                t(
                    "другая камера: сокеты к сети не привязываются — привязан весь процесс: $processBound",
                    "other camera: sockets can't be bound to the network — the whole process is bound: $processBound",
                ),
            )
        }
        val l = adoption.link
        link = l
        val info = probe(l)
        if (info == null) {
            release()
            return@withContext null
        }
        log(t("другая камера: ${info.model} ${info.firmware}", "other camera: ${info.model} ${info.firmware}"))
        info
    }

    /** `/osc/info`, with a journal line explaining a refusal (a GO-family camera answers HTTP 400 here). */
    private fun probe(l: CameraLink): OscInfo? =
        runCatching { OscParsing.info(OscClient(l, log).info()) }
            .onFailure {
                log(
                    t(
                        "другая камера: /osc/info не ответил (${it.message}) — похоже, это не OSC-модель (GO-серия?) " +
                            "или камера занята",
                        "other camera: /osc/info didn't answer (${it.message}) — looks like a non-OSC model " +
                            "(GO series?) or the camera is busy",
                    ),
                )
            }
            .getOrNull()

    suspend fun state(): OscState? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null
        runCatching { OscParsing.state(OscClient(l, log).state()) }
            .onFailure {
                log(
                    t(
                        "другая камера: /osc/state не ответил: ${it.message}",
                        "other camera: /osc/state didn't answer: ${it.message}",
                    ),
                )
            }
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
        log(
            t(
                "другая камера: файлов получено ${files.size} (страниц $pages)",
                "other camera: received ${files.size} files (${pages} pages)",
            ),
        )
        OscParsing.groupIntoShots(files)
    }

    /**
     * A text report for the alpha feedback (no secrets: the serial is masked). When nothing is connected yet it looks
     * for the camera's network itself, so the report always says whether a camera network was seen and what
     * `/osc/info` answered. [journal] — the screen's journal lines, appended as the connection history.
     */
    suspend fun profile(journal: List<String>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("insta360 transfer ").append(BuildConfig.VERSION_NAME).append(" — camera profile (alpha)\n")
        sb.append("phone: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append(", Android SDK ").append(android.os.Build.VERSION.SDK_INT)
            .append('\n')
        val l = link
        if (l == null) {
            val network = findCameraNetwork()
            sb.append(
                if (network == null) {
                    "network ${CameraNetworks.CAMERA_SUBNET}x: not found (the phone is not on a camera's Wi-Fi)\n"
                } else {
                    "network ${CameraNetworks.CAMERA_SUBNET}x: found, not connected yet — probing\n"
                },
            )
            if (network != null) {
                val adoption = CameraNetworks.adopt(cm, network)
                val probeClient = OscClient(adoption.link, log)
                sb.append("probe /osc/info: ").append(raw { probeClient.info() }).append('\n')
                sb.append("probe /osc/state: ").append(raw { probeClient.state() }).append('\n')
                if (adoption.boundProcess) CameraNetworks.unbind(cm)
            }
            return@withContext finish(sb, journal)
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
        finish(sb, journal)
    }

    private fun finish(sb: StringBuilder, journal: List<String>): String {
        if (journal.isNotEmpty()) {
            sb.append("\n--- journal (screen) ---\n")
            journal.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
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
