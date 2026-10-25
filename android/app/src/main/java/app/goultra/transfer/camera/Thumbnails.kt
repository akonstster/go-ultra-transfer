package app.goultra.transfer.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Thumbnails {
    /** Bytes of the file head to fetch: enough for the APP1 (EXIF) segment with its 160×96 JPEG thumbnail. */
    const val EXIF_HEAD_BYTES = 64 * 1024

    /** Extracts the JPEG thumbnail embedded in the EXIF APP1 segment of a camera JPG. */
    fun fromExif(head: ByteArray): Bitmap? {
        if (head.size < 8 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        if (head[2] != 0xFF.toByte() || head[3] != 0xE1.toByte()) return null
        val app1End = minOf(head.size, 4 + ((head[4].toInt() and 0xFF) shl 8 or (head[5].toInt() and 0xFF)))
        var start = -1
        for (i in 6 until app1End - 2) {
            if (head[i] == 0xFF.toByte() && head[i + 1] == 0xD8.toByte() && head[i + 2] == 0xFF.toByte()) {
                start = i
                break
            }
        }
        if (start < 0) return null
        for (i in start + 2 until app1End - 1) {
            if (head[i] == 0xFF.toByte() && head[i + 1] == 0xD9.toByte()) {
                return BitmapFactory.decodeByteArray(head, start, i + 2 - start)
            }
        }
        return null
    }

    /**
     * GetFileExtra(THUMBNAIL) body: 40-byte header ([16] width u32, [20] height u32) + NV12 frame
     * (Y plane, then interleaved UV). Downscaled by [step] while converting.
     */
    fun fromNv12(body: ByteArray, step: Int = 3): Bitmap? {
        if (body.size < 40) return null
        val w = u32(body, 16)
        val h = u32(body, 20)
        val off = 40
        if (w <= 0 || h <= 0 || off.toLong() + w.toLong() * h * 3 / 2 > body.size) return null
        val ow = w / step
        val oh = h / step
        val px = IntArray(ow * oh)
        val uvBase = off + w * h
        for (y in 0 until oh) {
            val sy = y * step
            for (x in 0 until ow) {
                val sx = x * step
                val yy = body[off + sy * w + sx].toInt() and 0xFF
                val c = uvBase + (sy / 2) * w + (sx / 2) * 2
                val u = (body[c].toInt() and 0xFF) - 128
                val v = (body[c + 1].toInt() and 0xFF) - 128
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                px[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }
}
