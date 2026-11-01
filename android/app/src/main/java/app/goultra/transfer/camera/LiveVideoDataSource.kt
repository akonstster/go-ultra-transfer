package app.goultra.transfer.camera

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException

@OptIn(UnstableApi::class)
class LiveVideoDataSource(
    private val resolver: ContentResolver,
    private val video: LiveCover.Video,
) : BaseDataSource(false) {
    class Factory(private val resolver: ContentResolver, private val video: LiveCover.Video) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveVideoDataSource(resolver, video)
    }

    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position > video.length) throw EOFException("позиция вне видео")
        val pfd = resolver.openFileDescriptor(video.item, "r") ?: throw IOException("не открыть живое фото")
        try {
            val stream = FileInputStream(pfd.fileDescriptor)
            input = stream
            descriptor = pfd
            stream.channel.position(video.start + dataSpec.position)
            val available = video.length - dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            pfd.close()
            descriptor = null
            input = null
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = input?.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            ?: throw IOException("видео не открыто")
        if (n < 0) throw EOFException("неполное видео живого фото")
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri = video.item

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            descriptor?.close()
            descriptor = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
