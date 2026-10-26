package app.goultra.transfer.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Control channel over Wi-Fi: TCP 192.168.42.1:6666.
 * Frame: [u32 LE length incl. itself][payload]; payload 06 00 00 "syNceNdinS" = sync, 05 00 00 = keepalive (every 2 s),
 * 04 00 00 … = message with the 12-byte header from [Proto.messageHeader].
 */
class CameraTcp(
    private val link: CameraLink,
    private val log: (String) -> Unit,
    private val onClosed: () -> Unit = {},
) : Closeable {
    companion object {
        const val HOST = "192.168.42.1"
        private const val PORT = 6666
        private val SYNC = byteArrayOf(6, 0, 0) + "syNceNdinS".toByteArray(Charsets.US_ASCII)
        private val KEEPALIVE = byteArrayOf(5, 0, 0)
        private const val FILE_LIST_PAGE = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CamMessage>>()
    private val synced = CompletableDeferred<Unit>()
    private val writeLock = Any()
    private var socket: Socket? = null
    private var output: OutputStream? = null
    @Volatile private var lastSend = 0L
    private var seq = 0

    /**
     * Live stream packets (payload type 0x01), called on the reader thread:
     * `01 00 00 [stream: 0x20 video, 0x30 gyro, 0x40 sync] [u64 LE timestamp µs]` + data from byte 12.
     */
    @Volatile var onStream: ((ByteArray) -> Unit)? = null

    /** Camera notifications (code 8xxx), called on the reader thread. */
    @Volatile var onNotification: ((CamMessage) -> Unit)? = null

    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        val s = link.createSocket()
        socket = s // before connect(): close() must be able to release it if connect throws
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(HOST, PORT), 5_000)
        output = s.getOutputStream()
        scope.launch { readLoop(DataInputStream(BufferedInputStream(s.getInputStream()))) }
        scope.launch {
            while (isActive) {
                delay(500)
                if (System.currentTimeMillis() - lastSend >= 2_000) runCatching { send(KEEPALIVE) }
            }
        }
        send(SYNC)
        send(KEEPALIVE)
        val ok = withTimeoutOrNull(5_000) { synced.await() } != null
        log(if (ok) "TCP 6666: sync OK" else "TCP 6666: камера не ответила на sync")
        ok
    }

    private fun send(payload: ByteArray) = synchronized(writeLock) {
        val out = output ?: error("not connected")
        out.write(le32(payload.size + 4) + payload)
        out.flush()
        lastSend = System.currentTimeMillis()
    }

    private fun readLoop(input: DataInputStream) {
        val lenBuf = ByteArray(4)
        try {
            while (true) {
                input.readFully(lenBuf)
                val len = u32(lenBuf, 0)
                require(len in 4..(64 shl 20)) { "bad frame length $len" }
                val p = ByteArray(len - 4)
                input.readFully(p)
                when (p.firstOrNull()?.toInt()) {
                    // A broken stream packet must not look like a dropped connection.
                    1 -> runCatching { onStream?.invoke(p) }.onFailure { log("трансляция: пакет пропущен (${it.message})") }
                    6 -> synced.complete(Unit)
                    5 -> Unit
                    4 -> {
                        val m = Proto.parseMessage(p, 0, seqBytes = 3) ?: continue
                        val waiter = if (m.isResponse) pending.remove(m.seq) else null
                        if (waiter != null) {
                            waiter.complete(m)
                        } else {
                            log("TCP уведомление ${m.code}: ${m.body.hex(24)}")
                            runCatching { onNotification?.invoke(m) }
                        }
                    }
                    else -> log("TCP пакет: ${p.hex(24)}")
                }
            }
        } catch (e: Exception) {
            log("TCP соединение закрыто: ${e.message}")
            val closed = IOException("TCP connection closed")
            pending.values.forEach { it.completeExceptionally(closed) }
            onClosed()
        }
    }

    /** Sends a command and waits for its response; null on timeout or a dead connection. */
    suspend fun request(code: Int, body: ByteArray, timeoutMs: Long = 10_000): CamMessage? {
        val s = synchronized(this) { seq = (seq + 1) and 0xFFFFFF; seq }
        val d = CompletableDeferred<CamMessage>()
        pending[s] = d
        val r = try {
            withContext(Dispatchers.IO) { send(Proto.messageHeader(code, s) + body) }
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (e: IOException) {
            log("TCP: команда $code не прошла: ${e.message}")
            null
        } finally {
            pending.remove(s)
        }
        if (r == null) log("TCP: нет ответа на команду $code")
        return r
    }

    /** GetFileList → URIs like /DCIM/Camera01/VID_….mp4 (LRV included), page by page. */
    suspend fun fileList(): List<String>? {
        val all = LinkedHashSet<String>()
        var pages = 0
        while (pages++ < 100) {
            val body = PbWriter()
                .varint(1, Proto.MEDIA_VIDEO_AND_PHOTO)
                .varint(2, all.size.toLong())
                .varint(3, FILE_LIST_PAGE.toLong())
                .toByteArray()
            val r = request(Proto.CMD_GET_FILE_LIST, body) ?: return null
            if (r.code != 200) {
                log("GetFileList: код ${r.code}")
                return null
            }
            val fields = r.body.pbFields()
            val page = fields.filter { it.number == 1 }.mapNotNull { it.string }
            val total = fields.field(2)?.varint
            val added = all.addAll(page)
            log("GetFileList: страница ${page.size}, всего ${all.size} из $total")
            // The camera returns at most ~100 entries per request whatever the limit: page by total_count.
            // Stop when everything is in, or if the camera ignores `start` and repeats a page.
            if (!added || page.isEmpty() || total == null || all.size >= total) break
        }
        return all.toList()
    }

    /** GetFileInfoList → path → ExtraMetadata fields (main files only, no LRV). */
    suspend fun fileInfoList(): Map<String, List<PbField>>? {
        val r = request(Proto.CMD_GET_FILE_INFO_LIST, ByteArray(0), 20_000) ?: return null
        if (r.code != 200) {
            log("GetFileInfoList: код ${r.code}")
            return null
        }
        val result = mutableMapOf<String, List<PbField>>()
        for (info in r.body.pbFields()) {
            if (info.number != 1) continue
            val fields = info.data?.pbFields() ?: continue
            val path = fields.field(1)?.string ?: continue
            result[path] = fields.field(2)?.data?.pbFields() ?: emptyList()
        }
        return result
    }

    /** GetFileExtra(THUMBNAIL): raw 40-byte header + NV12 frame (see [Thumbnails.fromNv12]). */
    suspend fun fileThumbnail(uri: String): ByteArray? {
        val body = PbWriter().string(1, uri).varint(2, Proto.EXTRA_TYPE_THUMBNAIL).toByteArray()
        val r = request(Proto.CMD_GET_FILE_EXTRA, body) ?: return null
        return r.body.takeIf { r.code == 200 && it.isNotEmpty() }
    }

    override fun close() {
        scope.cancel()
        runCatching { socket?.close() }
    }
}
