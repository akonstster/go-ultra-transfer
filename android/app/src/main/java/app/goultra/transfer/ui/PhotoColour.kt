package app.goultra.transfer.ui

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/** Longest side of the picture a photo's colour is shown on: sharp enough on the screen, light for the GPU. */
internal const val PHOTO_STILL_SIDE = 2048

/** Longest side a photo with colour is saved at: the GPU's textures stay within every phone's limit and memory. */
internal const val PHOTO_SAVE_SIDE = 4096

/**
 * A photo's colour: the very steps a video's colour uses (exposure / saturation, sharpening, «Стиль», clipping stripes),
 * drawn over a still by [StillFrame]. The sharpening radius is set for 1080 lines, as on a video's preview, so it is
 * widened by [scale] for a bigger picture.
 */
@OptIn(UnstableApi::class)
internal class PhotoSteps(look: ColorLook, clipping: Boolean, private val scale: Float) {
    val matrix = LiveColorMatrix(look.exposure, look.saturation)
    val sharpen = Sharpen(look.sharpness, look.sharpRadius * scale)
    val film = FilmLook(look)
    val stripes = ClippingStripes().also { it.on = clipping }
    val steps: List<ColorStep> = listOf(matrix, sharpen, film, stripes)

    fun set(look: ColorLook) {
        matrix.set(look.exposure, look.saturation)
        sharpen.amount = look.sharpness
        sharpen.radius = look.sharpRadius * scale
        film.set(look)
    }

    fun sharpLive(amount: Float, radius: Float) {
        sharpen.amount = amount
        sharpen.radius = radius * scale
    }

    /** «До / после»: the photo as shot (the clipping stripes stay — they measure, not grade). */
    fun bypass(on: Boolean) {
        matrix.bypass = on
        sharpen.bypass = on
        film.bypass = on
    }

    companion object {
        fun scaleFor(bitmap: Bitmap) = maxOf(1f, minOf(bitmap.width, bitmap.height) / 1080f)

        /** What saving a photo draws: only the steps that change something, no stripes (as a video's save). */
        fun forSaving(look: ColorLook, bitmap: Bitmap): List<ColorStep> {
            val out = mutableListOf<ColorStep>()
            if (look.exposure != 0f || look.saturation != 1f) out += LiveColorMatrix(look.exposure, look.saturation)
            if (look.sharpness > 0f) out += Sharpen(look.sharpness, look.sharpRadius * scaleFor(bitmap))
            if (look.hasStyle) out += FilmLook(look)
            return out
        }
    }
}
