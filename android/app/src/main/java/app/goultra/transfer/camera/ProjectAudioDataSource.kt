package app.goultra.transfer.camera

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer

/** ExoPlayer requests a range of the mix. Decoding and mixing run on its loader thread. */
@OptIn(UnstableApi::class)
internal class ProjectAudioDataSource(
    private val context: Context,
    private val clips: List<ProjectAudioClip>,
    private val lengthMs: Long,
    private val mixPreview: ((ByteBuffer, Long) -> Unit)?,
) : BaseDataSource(false) {
    private var wave: ProjectSound.Wave? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val input = try {
            ProjectSound.Wave(clips, lengthMs, ProjectPcmCache.of(context)::open, dataSpec.position, mixPreview)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid project audio range", error)
        }
        wave = input
        uri = dataSpec.uri
        val available = input.length - dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val input = wave ?: throw IOException("Project audio is closed")
        val count = try {
            input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (error: RuntimeException) {
            throw IOException("Could not decode project audio", error)
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val input = wave ?: return
        wave = null
        uri = null
        try {
            input.close()
        } finally {
            transferEnded()
        }
    }
}
