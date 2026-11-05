package app.goultra.transfer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.goultra.transfer.camera.Downloader
import app.goultra.transfer.camera.osc.OscInfo
import app.goultra.transfer.camera.osc.OscSession
import app.goultra.transfer.camera.osc.OscShot
import app.goultra.transfer.camera.osc.OscState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The «other cameras» (alpha) screen's state: find the camera on the phone's Wi-Fi, list its shots, download them and
 * collect a profile for the feedback. Read-only by design — nothing is written to a camera this app doesn't know.
 */
class OscController(private val app: Context) {
    enum class Stage { IDLE, SEARCHING, READY, ERROR }

    var stage by mutableStateOf(Stage.IDLE)
        private set
    var info by mutableStateOf<OscInfo?>(null)
        private set
    var state by mutableStateOf<OscState?>(null)
        private set
    var shots by mutableStateOf<List<OscShot>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var lastResult by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Download progress: files finished of the queue, and the current file's bytes. */
    var doneFiles by mutableStateOf(0)
        private set
    var totalFiles by mutableStateOf(0)
        private set
    var currentDone by mutableStateOf(0L)
        private set
    var currentTotal by mutableStateOf(0L)
        private set

    /** The last lines of what the OSC layer did — shown on the screen (the app's own log also has them). */
    val logLines = mutableStateListOf<String>()

    private val session = OscSession(app.applicationContext) { line -> log(line) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listJob: kotlinx.coroutines.Job? = null

    private fun log(line: String) {
        synchronized(logLines) {
            logLines.add(line)
            while (logLines.size > 200) logLines.removeAt(0)
        }
    }

    /** Looks for the camera on the phone's Wi-Fi (the user joins its network by hand, see the instructions). */
    fun search() {
        if (busy) return
        busy = true
        stage = Stage.SEARCHING
        message = null
        lastResult = null
        scope.launch {
            val found = session.connect()
            if (found == null) {
                busy = false
                stage = Stage.ERROR
                info = null
                shots = emptyList()
                selected = emptySet()
                return@launch
            }
            info = found
            state = session.state()
            stage = Stage.READY
            refreshList()
        }
    }

    /** Re-reads the file list of the found camera. */
    fun refreshList() {
        if (session.link == null || busy) return
        listJob?.cancel()
        busy = true
        message = null
        listJob = scope.launch {
            try {
                val list = session.listShots()
                shots = list
                selected = emptySet()
                state = session.state()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun toggle(shot: OscShot) {
        val key = shot.name
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAll(on: Boolean) {
        selected = if (on) shots.map { it.name }.toSet() else emptySet()
    }

    /** Downloads the selected shots (all of them when nothing is selected) into `DCIM/Camera`. */
    fun download() {
        if (busy) return
        val link = session.link ?: return
        val queue = shots.filter { selected.isEmpty() || it.name in selected }
        val files = queue.flatMap { it.files }
        if (files.isEmpty()) return
        busy = true
        message = null
        lastResult = null
        doneFiles = 0
        totalFiles = files.size
        currentDone = 0
        currentTotal = 0
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            for (f in files) {
                currentDone = 0
                currentTotal = f.size ?: 0
                try {
                    Downloader.download(app, link, f.path, takenAtMillis = f.takenAtMillis) { done, total ->
                        currentDone = done
                        if (total > 0) currentTotal = total
                    }
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += f.name + ": " + (e.message ?: e.javaClass.simpleName)
                }
                doneFiles = ok + failed.size
            }
            busy = false
            lastResult = tr("Скачано: %s из %s", "Downloaded: %s of %s").format(ok, files.size) +
                (if (failed.isNotEmpty()) tr(", ошибок %s: ", ", %s failed: ").format(failed.size) + failed.take(3).joinToString("; ") else "")
        }
    }

    /** Collects the camera profile; [onReady] runs on the main thread with the text to share. */
    fun collectProfile(onReady: (String) -> Unit) {
        scope.launch {
            val text = runCatching { session.profile() }
                .getOrElse { "profile failed: ${it.message}" }
            onReady(text)
        }
    }

    /** Leaving the screen: forget the camera's network (the GO Ultra flow keeps its own link untouched). */
    fun close() {
        listJob?.cancel()
        session.release()
        stage = Stage.IDLE
        info = null
        state = null
        shots = emptyList()
        selected = emptySet()
        message = null
        lastResult = null
        busy = false
    }
}
