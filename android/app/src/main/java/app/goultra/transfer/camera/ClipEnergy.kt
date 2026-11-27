package app.goultra.transfer.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much movement a take has, 0…1 (P3): a few small frames are enough. The score is the frame-to-frame brightness
 * change with a smaller share of contrast and brightness change, so a pan, a wave or a person walking scores above a
 * locked-off shot. Pure arithmetic over [Frame]s for the JVM; [of] is the only Android entry, used by the controller
 * after it has decoded the frames through the same [FrameReader] the strip uses.
 */
object ClipEnergy {
    /** A tiny grayscale picture: [luma] is width × height values 0…255, row by row. */
    data class Frame(val width: Int, val height: Int, val luma: IntArray) {
        val size: Int get() = luma.size
    }

    /** The movement score of [frames], 0…1; 0 when there is nothing to compare (one frame or broken sizes). */
    fun score(frames: List<Frame>): Double {
        val good = frames.filter { it.width > 0 && it.height > 0 && it.size >= it.width * it.height }
        if (good.size < 2) return 0.0
        var motion = 0.0
        var contrastChange = 0.0
        var brightnessChange = 0.0
        for (i in 1 until good.size) {
            val a = good[i - 1]
            val b = good[i]
            val n = minOf(a.size, b.width * b.height, b.size)
            if (n <= 0) continue
            var diff = 0L
            var sumA = 0L
            var sumB = 0L
            for (j in 0 until n) {
                val va = a.luma[j].coerceIn(0, 255)
                val vb = b.luma[j].coerceIn(0, 255)
                diff += abs(va - vb)
                sumA += va
                sumB += vb
            }
            val meanA = sumA.toDouble() / n
            val meanB = sumB.toDouble() / n
            var varA = 0.0
            var varB = 0.0
            for (j in 0 until n) {
                val da = a.luma[j].coerceIn(0, 255) - meanA
                val db = b.luma[j].coerceIn(0, 255) - meanB
                varA += da * da
                varB += db * db
            }
            motion += diff.toDouble() / n
            contrastChange += abs(sqrt(varA / n) - sqrt(varB / n))
            brightnessChange += abs(meanA - meanB)
        }
        val pairs = (good.size - 1).coerceAtLeast(1)
        // A typical pan moves the luma by tens; dividing by 48 puts real movement high without a black frame noise
        // pushing it to the top. The two smaller terms tell a cut or a flash from steady motion.
        val motionPart = (motion / pairs / 48.0).coerceIn(0.0, 1.0)
        val contrastPart = (contrastChange / pairs / 32.0).coerceIn(0.0, 1.0)
        val brightnessPart = (brightnessChange / pairs / 64.0).coerceIn(0.0, 1.0)
        return (motionPart * 0.75 + contrastPart * 0.15 + brightnessPart * 0.10).coerceIn(0.0, 1.0)
    }

    /**
     * The luma of [bitmap] as a [Frame] no wider than [width] and [height]: scaled down first, so the arithmetic sees
     * a small picture and a large LRV frame does not cost memory. Null when the bitmap has no pixels.
     */
    fun of(bitmap: Bitmap, width: Int = 32, height: Int = 18): Frame? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        return try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            Frame(
                scaled.width,
                scaled.height,
                IntArray(pixels.size) { i ->
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    (77 * r + 150 * g + 29 * b) shr 8
                },
            )
        } catch (e: RuntimeException) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}