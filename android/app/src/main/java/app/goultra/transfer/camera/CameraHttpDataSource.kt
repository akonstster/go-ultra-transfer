package app.goultra.transfer.camera

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Media3 data source that streams a file from the camera's HTTP server through [CameraLink] — the camera's Wi-Fi is
 * bound to this app only, so the stock HTTP data source (and any other app) cannot reach it. Seeks use Range.
 */
@OptIn(UnstableApi::class)
class CameraHttpDataSource(private val link: CameraLink) : BaseDataSource(/* isNetwork = */ true) {
    class Factory(private val link: CameraLink) : DataSource.Factory {
        override fun createDataSource(): DataSource = CameraHttpDataSource(link)
    }

    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val conn = link.openConnection(URL(dataSpec.uri.toString())) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        if (dataSpec.position > 0 || bounded) {
            val end = if (bounded) (dataSpec.position + dataSpec.length - 1).toString() else ""
            conn.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code for ${dataSpec.uri}")
        }
        connection = conn
        val stream = conn.inputStream
        input = stream
        // A server that ignored Range answers 200 with the whole file: skip to the requested position.
        if (code == 200 && dataSpec.position > 0) {
            var toSkip = dataSpec.position
            while (toSkip > 0) {
                val n = stream.skip(toSkip)
                if (n <= 0) throw IOException("cannot skip to ${dataSpec.position}")
                toSkip -= n
            }
        }
        val length = conn.contentLengthLong
        bytesRemaining = when {
            bounded -> dataSpec.length
            length < 0 -> C.LENGTH_UNSET.toLong()
            code == 200 -> length - dataSpec.position
            else -> length
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = input?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        } finally {
            input = null
            connection?.disconnect()
            connection = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
