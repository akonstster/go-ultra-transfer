package app.goultra.transfer.camera

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Builds a Motion Photo MP4 with the camera's preview first and original video second. */
internal object LiveMuxer {
    fun mux(
        resolver: ContentResolver,
        item: Uri,
        originalStart: Long,
        originalLength: Long,
        previewLength: Long,
        output: File,
    ) {
        val originalDescriptor = resolver.openFileDescriptor(item, "r") ?: throw IOException("не открыть оригинальное видео")
        originalDescriptor.use { originalFd ->
            val previewDescriptor = resolver.openFileDescriptor(item, "r") ?: throw IOException("не открыть превью")
            previewDescriptor.use { previewFd ->
                val original = MediaExtractor()
                val preview = MediaExtractor()
                try {
                    original.setDataSource(originalFd.fileDescriptor, originalStart, originalLength)
                    preview.setDataSource(previewFd.fileDescriptor, originalStart + originalLength, previewLength)
                    val highTrack = track(original, "video/")
                    val lowTrack = track(preview, "video/")
                    val highFormat = original.getTrackFormat(highTrack)
                    val lowFormat = preview.getTrackFormat(lowTrack)
                    val highRotation = rotation(highFormat)
                    if (highRotation != rotation(lowFormat)) throw IOException("поворот оригинала и превью не совпадает")
                    verifyTimes(original, highTrack, preview, lowTrack)
                    val audioTrack = trackOrNull(original, "audio/")
                    val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        if (highRotation != 0) muxer.setOrientationHint(highRotation)
                        val lowOutput = muxer.addTrack(lowFormat)
                        val highOutput = muxer.addTrack(highFormat)
                        val audioOutput = audioTrack?.let { muxer.addTrack(original.getTrackFormat(it)) }
                        muxer.start()
                        copyTrack(preview, lowTrack, muxer, lowOutput)
                        copyTrack(original, highTrack, muxer, highOutput)
                        if (audioTrack != null && audioOutput != null) copyTrack(original, audioTrack, muxer, audioOutput)
                        muxer.stop()
                    } finally {
                        muxer.release()
                    }
                } finally {
                    preview.release()
                    original.release()
                }
            }
        }
    }

    private fun rotation(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

    private fun track(extractor: MediaExtractor, prefix: String): Int =
        trackOrNull(extractor, prefix) ?: throw IOException("нет дорожки $prefix")

    private fun trackOrNull(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun verifyTimes(original: MediaExtractor, highTrack: Int, preview: MediaExtractor, lowTrack: Int) {
        original.selectTrack(highTrack)
        preview.selectTrack(lowTrack)
        try {
            fun times(extractor: MediaExtractor): Sequence<Long> = sequence {
                while (extractor.sampleTrackIndex >= 0) {
                    yield(extractor.sampleTime)
                    extractor.advance()
                }
            }
            verifyLiveFrameTimes(times(original), times(preview))
        } finally {
            original.unselectTrack(highTrack)
            preview.unselectTrack(lowTrack)
            original.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            preview.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
    }

    private fun copyTrack(extractor: MediaExtractor, track: Int, muxer: MediaMuxer, outputTrack: Int) {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        try {
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTrackIndex >= 0) {
                val size = extractor.sampleSize
                if (size < 0 || size > 64L * 1024 * 1024) throw IOException("неверный размер кадра: $size")
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read != size.toInt()) throw IOException("неполный кадр видео")
                val flags = extractor.sampleFlags
                if (flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0)
                    throw IOException("неподдерживаемый тип кадра")
                info.set(0, read, extractor.sampleTime, if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.unselectTrack(track)
        }
    }
}
