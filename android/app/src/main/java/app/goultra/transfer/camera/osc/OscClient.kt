package app.goultra.transfer.camera.osc

import app.goultra.transfer.camera.CameraLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Open Spherical Camera HTTP API as Insta360's 360 cameras speak it — official notes:
 * `github.com/Insta360Develop/Insta360_OSC`. Every command is a separate request; the camera asks its clients not to
 * pipeline them, so calls here are strictly sequential.
 */
class OscClient(
    private val link: CameraLink,
    private val log: (String) -> Unit,
    private val host: String = DEFAULT_HOST,
) {
    companion object {
        /** Every Insta360 access point puts the camera at this address. */
        const val DEFAULT_HOST = "192.168.42.1"
    }

    /** `GET /osc/info` — model, serial, firmware. */
    fun info(timeoutMs: Int = 4_000): JSONObject = call("GET", "/osc/info", null, timeoutMs)

    /** `POST /osc/state` — battery, card, storage path. */
    fun state(timeoutMs: Int = 4_000): JSONObject = call("POST", "/osc/state", JSONObject(), timeoutMs)

    /** `camera.getOptions` for exactly [names]; null when the camera refuses the call (older firmware). */
    fun getOptions(names: List<String>, timeoutMs: Int = 6_000): JSONObject? = runCatching {
        execute("camera.getOptions", JSONObject().put("optionNames", JSONArray(names)), timeoutMs)
    }.onFailure { log("OSC getOptions: ${it.message}") }.getOrNull()

    /** `camera.listFiles` — one page; [startPosition] for the next ones (`totalEntries` says how many there are). */
    fun listFiles(
        fileType: String = "all",
        entryCount: Int = 200,
        startPosition: Int? = null,
        timeoutMs: Int = 15_000,
    ): JSONObject {
        val parameters = JSONObject().put("fileType", fileType).put("entryCount", entryCount)
        if (startPosition != null && startPosition > 0) parameters.put("startPosition", startPosition)
        return execute("camera.listFiles", parameters, timeoutMs)
    }

    /** `POST /osc/commands/execute`; an OSC error in the answer becomes an [IOException]. */
    fun execute(name: String, parameters: JSONObject? = null, timeoutMs: Int = 8_000): JSONObject {
        val body = JSONObject().put("name", name)
        if (parameters != null) body.put("parameters", parameters)
        val reply = call("POST", "/osc/commands/execute", body, timeoutMs)
        reply.optJSONObject("error")?.let { err ->
            throw IOException("OSC $name: ${err.optString("code")} ${err.optString("message")}")
        }
        return reply
    }

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject {
        val conn = (link.openConnection(URL("http://$host$path")) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            // Required by Insta360's OSC implementation on every request.
            setRequestProperty("X-XSRF-Protected", "1")
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code on $path: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
