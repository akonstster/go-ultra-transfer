package app.goultra.transfer.camera

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoder seeks in MP3s can land at a different sample. Extend each source from zero and serve exact frame ranges. */
internal class ProjectPcmCache(
    private val directory: File,
    private val decode: (String) -> ClipAudio.PcmSource?,
) {
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)

    @Synchronized
    fun open(source: String): ClipAudio.PcmSource? {
        val entry = entries[source] ?: run {
            trim(maxEntries = 7)
            val decoder = decode(source) ?: return null
            try {
                directory.mkdirs()
                val path = File.createTempFile("source-", ".pcm", directory)
                val file = RandomAccessFile(path, "rw")
                // On Android an open descriptor remains usable after unlink. The OS frees the cache on process exit.
                Entry(decoder, file, path.takeUnless { it.delete() }).also { entries[source] = it }
            } catch (error: Exception) {
                decoder.release()
                throw error
            }
        }
        entry.users++
        return Handle(entry)
    }

    private fun trim(maxEntries: Int = 8) {
        var bytes = entries.values.sumOf { it.bytes }
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && (entries.size > maxEntries || bytes > 384L * 1024 * 1024)) {
            val entry = iterator.next()
            if (entry.users != 0) continue
            bytes -= entry.bytes
            iterator.remove()
            entry.close()
        }
    }

    private inner class Handle(private val entry: Entry) : ClipAudio.PcmSource {
        override val rate = entry.rate
        override val channels = entry.channels
        override val durationMs = entry.durationMs
        override val delayFrames = entry.delayFrames
        private var closed = false

        override fun read(from: Long, count: Int): ShortArray = entry.read(from, count)

        override fun release() {
            synchronized(this@ProjectPcmCache) {
                if (closed) return
                closed = true
                entry.users--
                trim()
            }
        }
    }

    private class Entry(decoder: ClipAudio.PcmSource, private val file: RandomAccessFile, private val path: File?) {
        var users = 0
        val rate = decoder.rate
        val channels = decoder.channels
        val durationMs = decoder.durationMs
        val delayFrames = decoder.delayFrames
        private val frameBytes = channels * 2
        private val end = if (durationMs > 0L) (durationMs + 250L) * rate / 1000 + delayFrames else Long.MAX_VALUE
        private var reader: ClipAudio.PcmSource? = decoder
        private var frames = 0L
        @Volatile var bytes = 0L
            private set

        @Synchronized
        fun read(from: Long, count: Int): ShortArray {
            require(from >= 0L)
            val target = minOf(from + count, end)
            while (frames < target) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PCM read interrupted")
                val size = minOf(target - frames, 16_384L).toInt()
                val pcm = checkNotNull(reader).read(frames, size)
                val data = ByteBuffer.allocate(size * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
                data.asShortBuffer().put(pcm)
                file.seek(bytes)
                file.write(data.array())
                frames += size
                bytes = frames * frameBytes
            }
            if (frames == end) {
                reader?.release()
                reader = null
            }
            val result = ShortArray(count * channels)
            val available = (minOf(frames - from, count.toLong()).coerceAtLeast(0L) * frameBytes).toInt()
            if (available > 0) {
                val data = ByteArray(available)
                file.seek(from * frameBytes)
                file.readFully(data)
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result, 0, available / 2)
            }
            return result
        }

        fun close() {
            try {
                reader?.release()
            } finally {
                file.close()
                path?.delete()
            }
        }
    }

    companion object {
        private var shared: ProjectPcmCache? = null

        @Synchronized
        fun of(context: Context): ProjectPcmCache = shared ?: run {
            val app = context.applicationContext
            ProjectPcmCache(File(app.cacheDir, "project-pcm")) { source ->
                ClipAudio.openPcm { it.setDataSource(app, Uri.parse(source), null) }
            }.also { shared = it }
        }
    }
}
