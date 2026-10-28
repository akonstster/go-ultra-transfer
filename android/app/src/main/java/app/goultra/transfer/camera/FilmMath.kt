package app.goultra.transfer.camera

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The «Плёнка» look on one pixel, as the viewer's shader does it (ui/ColorEffects.kt FilmLook; the constants are shared,
 * so the two can't drift apart) — here for tests. Each step is a colourist's standard move, not a tint:
 *
 * 1. Warmth: a white-balance gain on red and blue in light, brightness kept.
 * 2. Tone, per channel as a print stock does it: contrast in stops around 18 % grey (grey stays), then a soft shoulder
 *    that rolls the highlights into white instead of clipping, and a print's black a touch above zero. Per channel,
 *    bright colours run to white the way film's do.
 * 3. Density («subtractive saturation»): the more saturated a colour, the darker — rich, not bright, colour.
 * 4. Split toning in YCbCr: shadows a little towards teal, highlights towards warm; black and white stay neutral.
 * 5. Skin: colours near the skin tone line (123° on a vectorscope) keep the hue they were shot with through 1 and 4,
 *    pulled a little towards that line.
 *
 * Input and output are gamma-encoded BT.709 (what media3's effects see for SDR video); [film] 0…1, [warmth] −1…1,
 * [skin] 0…1. Halation and grain need neighbours / time and live in the shader only.
 */
object FilmMath {
    const val GAMMA = 2.4f
    const val GREY = 0.18f
    /** Extra contrast in stops per stop at film = 1. */
    const val CONTRAST = 0.25f
    /** The shoulder starts here (light); above it values approach 1 and never reach it. */
    const val KNEE = 0.7f
    /** The print's black (light) at film = 1: about 13 of 255. */
    const val BLACK = 0.0008f
    /** At most this much darker (light) for a fully saturated colour at film = 1. */
    const val DENSITY = 0.3f
    /** Red / blue gain at warmth ±1. */
    const val WARMTH = 0.1f
    /** Split toning in Cb / Cr at film = 1: shadows towards teal, highlights towards warm. */
    const val SHADOW_CB = 0.02f
    const val SHADOW_CR = -0.024f
    const val HIGH_CB = -0.014f
    const val HIGH_CR = 0.012f
    /** How far skin hues move towards the skin line at skin = 1 (and film = 1). */
    const val SKIN_PULL = 0.3f
    /** The skin tone line on a vectorscope: 123° from +Cb towards +Cr. */
    const val SKIN_CB = -0.5446f
    const val SKIN_CR = 0.8387f

    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /** One channel's light through the print curve; film = 0 leaves it as is. */
    fun tone(lin: Float, film: Float): Float {
        if (film == 0f) return lin
        val stops = ln(max(lin, 1e-5f) / GREY) / ln(2f) * (1f + CONTRAST * film)
        var y = GREY * 2f.pow(stops)
        if (y > KNEE) y = KNEE + (1f - KNEE) * (1f - exp(-(y - KNEE) / (1f - KNEE)))
        y = BLACK * film + (1f - BLACK * film) * y
        return lin + (y - lin) * film
    }

    /** How much of skin [cb], [cr] is (0…1): close to the skin line in hue, with some colour, not near black / white. */
    fun skinMask(luma: Float, cb: Float, cr: Float): Float {
        val c = sqrt(cb * cb + cr * cr)
        if (c < 1e-4f) return 0f
        val cos = (cb * SKIN_CB + cr * SKIN_CR) / c
        // Within about ±25° of the line, fully within ±10°.
        return smooth(0.906f, 0.985f, cos) * smooth(0.015f, 0.05f, c) * smooth(0.08f, 0.2f, luma) * (1f - smooth(0.85f, 0.97f, luma))
    }

    fun apply(rgb: FloatArray, film: Float, warmth: Float, skin: Float): FloatArray {
        val (r0, g0, b0) = rgb
        val y0 = KR * r0 + KG * g0 + KB * b0
        val cb0 = (b0 - y0) / 1.8556f
        val cr0 = (r0 - y0) / 1.5748f
        val mask = skinMask(y0, cb0, cr0) * skin

        // 1–3 in light.
        var lin = FloatArray(3) { max(rgb[it], 0f).pow(GAMMA) }
        val wr = 1f + WARMTH * warmth
        val wb = 1f - WARMTH * warmth
        val norm = KR * wr + KG + KB * wb
        lin = floatArrayOf(lin[0] * wr / norm, lin[1] / norm, lin[2] * wb / norm)
        lin = FloatArray(3) { tone(lin[it], film) }
        val top = max(lin[0], max(lin[1], lin[2]))
        val sat = if (top > 1e-5f) (top - min(lin[0], min(lin[1], lin[2]))) / top else 0f
        val dense = 1f - DENSITY * film * sat * sat
        val g = FloatArray(3) { (lin[it] * dense).coerceIn(0f, 1f).pow(1f / GAMMA) }

        // 4–5 in YCbCr.
        val y = KR * g[0] + KG * g[1] + KB * g[2]
        var cb = (g[2] - y) / 1.8556f
        var cr = (g[0] - y) / 1.5748f
        // In the shadows and the highlights, not in black and white: those stay neutral, as on a print.
        val shadows = smooth(0f, 0.12f, y) * (1f - smooth(0.12f, 0.55f, y))
        val highs = smooth(0.45f, 0.8f, y) * (1f - 0.7f * smooth(0.85f, 1f, y))
        val tcb = cb + film * (SHADOW_CB * shadows + HIGH_CB * highs)
        val tcr = cr + film * (SHADOW_CR * shadows + HIGH_CR * highs)
        // Skin: the toned colour, or the shot's hue (before warmth and toning) turned part of the way to the skin
        // line, at the graded strength.
        val c = sqrt(cb * cb + cr * cr)
        val c0 = sqrt(cb0 * cb0 + cr0 * cr0)
        var pcb = cb
        var pcr = cr
        if (c0 > 1e-5f) {
            val k = SKIN_PULL * skin * film
            val dx = (1f - k) * cb0 / c0 + k * SKIN_CB
            val dy = (1f - k) * cr0 / c0 + k * SKIN_CR
            val d = sqrt(dx * dx + dy * dy)
            pcb = c * dx / d
            pcr = c * dy / d
        }
        cb = tcb + (pcb - tcb) * mask
        cr = tcr + (pcr - tcr) * mask
        val r = y + 1.5748f * cr
        val b = y + 1.8556f * cb
        val gg = (y - KR * r - KB * b) / KG
        return floatArrayOf(r.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** For tests: the hue angle (degrees) of a gamma-encoded colour on a vectorscope. */
    fun hue(rgb: FloatArray): Float {
        val y = KR * rgb[0] + KG * rgb[1] + KB * rgb[2]
        return Math.toDegrees(kotlin.math.atan2(((rgb[0] - y) / 1.5748f).toDouble(), ((rgb[2] - y) / 1.8556f).toDouble())).toFloat()
    }
}
