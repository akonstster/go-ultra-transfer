package app.goultra.transfer.camera

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware H.265 decoder for the camera preview stream, rendering straight into [surface].
 * Video packets: `01 00 00 20 [u64 LE timestamp µs]` + Annex B NAL units from byte 12.
 * The codec is configured at the first keyframe, with its VPS/SPS/PPS as csd-0: some decoders (Huawei Kirin)
 * output nothing when parameter sets only arrive in-band.
 * The first [DUMP_BYTES] of the stream are saved to [dumpFile] for offline analysis.
 */
class LiveDecoder(
    @Volatile private var surface: Surface,
    private val log: (String) -> Unit,
    private val dumpFile: File? = null,
    /** Real picture size from the decoder: the stream follows the recording's shape (16:9, 4:3, 2.35:1). */
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private val queue = ArrayBlockingQueue<Pair<ByteArray, Long>>(90)
    @Volatile private var running = true
    @Volatile private var csd: ByteArray? = null
    @Volatile var width = 1280
    @Volatile var height = 720
    private var dropped = 0
    private var dump: FileOutputStream? = runCatching { dumpFile?.let { FileOutputStream(it) } }.getOrNull()
    private var dumped = 0L
    /** Guards codec calls: [setSurface] comes from the UI thread while the loop runs on its own. */
    private val codecLock = Any()
    private var codec: MediaCodec? = null
    private val thread = Thread(::loop, "live-decoder").apply { start() }

    /**
     * Moves the picture to another view without restarting the stream (portrait box ↔ full-screen landscape).
     * Synchronous, so the caller may release the old surface right after.
     */
    fun setSurface(s: Surface) {
        synchronized(codecLock) {
            if (s == surface) return
            surface = s
            codec?.let { c -> runCatching { c.setOutputSurface(s) }.onFailure { log("декодер: смена экрана: ${it.message}") } }
        }
    }

    /** Called with every stream packet (reader thread); non-video packets are ignored. */
    fun onPacket(p: ByteArray) {
        if (!running || p.size <= 12) return
        dump?.let { out ->
            runCatching { out.write(le32(p.size)); out.write(p) } // whole packets, for offline analysis
            dumped += p.size + 4
            if (dumped > DUMP_BYTES) {
                runCatching { out.close() }
                dump = null
                log("трансляция: ${dumped / 1024} КБ потока сохранено в ${dumpFile?.name}")
            }
        }
        if (p[3].toInt() != 0x20) return // gyro / sync packets
        val data = p.copyOfRange(12, p.size)
        if (csd == null) {
            val params = parameterSets(data) ?: return // wait for a keyframe with VPS/SPS/PPS
            csd = params
            log("трансляция: ключевой кадр, параметры ${params.size} Б: ${params.hex(24)}")
        }
        // A frame comes as 3 slice packets; decoders (Huawei Kirin) want the whole access unit in one input
        // buffer. A new frame starts at a parameter set or a slice with first_slice_segment_in_pic_flag = 1
        // (timestamps can't be used: slices of one frame may differ by 1 ms). Timestamps are milliseconds.
        if (startsPicture(data) && au.size() > 0) {
            if (!queue.offer(au.toByteArray() to auTs)) dropped++ // decoder too slow: drop rather than lag
            au.reset()
        }
        if (au.size() == 0) auTs = ((u32(p, 4).toLong() and 0xFFFFFFFFL) or (u32(p, 8).toLong() shl 32)) * 1000
        au.write(data)
    }

    private val au = java.io.ByteArrayOutputStream()
    private var auTs = -1L

    private fun loop() {
        while (running && csd == null) Thread.sleep(10)
        val params = csd ?: return
        val codec = synchronized(codecLock) { openCodec(params)?.also { codec = it } } ?: return
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var fed = 0
        try {
            while (running) {
                queue.poll(10, TimeUnit.MILLISECONDS)?.let { (data, ts) ->
                    val index = synchronized(codecLock) { codec.dequeueInputBuffer(20_000) }
                    if (index >= 0) {
                        synchronized(codecLock) {
                            codec.getInputBuffer(index)!!.apply { clear(); put(data) }
                            codec.queueInputBuffer(index, 0, data.size, ts, 0)
                        }
                        fed++
                    } else {
                        dropped++
                    }
                }
                while (true) {
                    val out = synchronized(codecLock) { codec.dequeueOutputBuffer(info, 0) }
                    if (out >= 0) {
                        synchronized(codecLock) { codec.releaseOutputBuffer(out, true) }
                        if (++frames == 1) log("трансляция: первый кадр на экране")
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        // The visible area (crop) if the decoder reports one, else the full buffer.
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        else f.getInteger(MediaFormat.KEY_HEIGHT)
                        log("трансляция: ${w}×$h")
                        onSize(w, h)
                    } else {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            log("декодер остановлен: ${e.message}")
        } finally {
            synchronized(codecLock) {
                this.codec = null
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            log("трансляция: подано $fed, кадров $frames, пропущено $dropped")
        }
    }

    /** Tries every HEVC decoder of the phone (hardware first) until one accepts the format. */
    private fun openCodec(params: ByteArray): MediaCodec? {
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
            .sortedBy { if (Build.VERSION.SDK_INT >= 29 && it.isSoftwareOnly) 1 else 0 }
            .map { it.name }
        for (name in names) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(params))
                codec.configure(format, surface, null, 0)
                codec.start()
                log("декодер: $name, ${width}×$height")
                return codec
            } catch (e: Exception) {
                log("декодер $name не настроился: ${e.message}")
                runCatching { codec.release() }
            }
        }
        log("ни один декодер H.265 не подошёл")
        return null
    }

    fun release() {
        running = false
        thread.join(1_000)
        runCatching { dump?.close() }
    }

    private companion object {
        const val DUMP_BYTES = 5_000_000L

        /** The packet's first NAL is a VPS/SPS/PPS/AUD, or a slice that begins a new picture. */
        fun startsPicture(data: ByteArray): Boolean {
            val (start, _) = splitNals(data).firstOrNull() ?: return false
            val type = (data[start].toInt() shr 1) and 0x3F
            return when {
                type in 32..35 -> true
                type < 32 && start + 2 < data.size -> data[start + 2].toInt() and 0x80 != 0
                else -> false
            }
        }

        /** VPS (32), SPS (33), PPS (34) NAL units of an Annex B access unit, with start codes; null if absent. */
        fun parameterSets(data: ByteArray): ByteArray? {
            val nals = splitNals(data)
            val wanted = nals.filter { (start, end) -> end > start && (data[start].toInt() shr 1) and 0x3F in 32..34 }
            if (wanted.none { (s, _) -> (data[s].toInt() shr 1) and 0x3F == 32 }) return null
            val out = java.io.ByteArrayOutputStream()
            for ((s, e) in wanted) {
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(data, s, e - s)
            }
            return out.toByteArray()
        }

        /** [start, end) of every NAL payload (after its start code). */
        fun splitNals(data: ByteArray): List<Pair<Int, Int>> {
            val starts = mutableListOf<Pair<Int, Int>>() // (start code position, payload start)
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) {
                        starts += i to i + 3
                        i += 3
                        continue
                    }
                    if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                        starts += i to i + 4
                        i += 4
                        continue
                    }
                }
                i++
            }
            return starts.mapIndexed { n, (_, payload) ->
                payload to (if (n + 1 < starts.size) starts[n + 1].first else data.size)
            }
        }
    }
}
