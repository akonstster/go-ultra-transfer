package app.goultra.transfer.camera

import kotlin.math.pow

/**
 * The colour matrix for the viewer's exposure and saturation, as media3's RgbMatrix takes it: 4×4, column-major
 * (android.opengl.Matrix). The player's effects see SDR video gamma-encoded (BT.709 / SMPTE 170M), so exposure in
 * stops scales by 2^(ev / 2.2) there — about 2^ev in light. Saturation mixes with the Rec.709 luma: 0 = grey,
 * 1 = as shot, 2 = twice as saturated.
 */
object ColorMath {
    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    fun matrix(ev: Float, saturation: Float): FloatArray {
        val k = 2.0.pow(ev / 2.2).toFloat()
        val out = FloatArray(16)
        for (row in 0..2) {
            for (col in 0..2) {
                val m = (if (row == col) saturation else 0f) + (1f - saturation) * LUMA[col]
                out[col * 4 + row] = m * k
            }
        }
        out[15] = 1f
        return out
    }

    /** Applies [m] (column-major) to an RGB colour — for tests and checks. */
    fun apply(m: FloatArray, rgb: FloatArray): FloatArray =
        FloatArray(3) { row -> (0..2).sumOf { col -> (m[col * 4 + row] * rgb[col]).toDouble() }.toFloat() }
}
