package app.goultra.transfer.camera

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The «Стиль» grade on one pixel, as the viewer's shader does it (ui/ColorEffects.kt FilmLook; the constants are shared,
 * so the two can't drift apart) — here for tests. Each step is a colourist's or a lab's standard move, not a tint:
 *
 * 1. White balance in light: the «Холод ↔ тепло» gain and a preset's filter (e.g. a warming 81EF), brightness kept.
 * 2. Flashing: a little light added to everything (a lab flash of the negative) — shadows lift and lose colour.
 * 3. Faces lighter, skies and corners darker (a preset's power windows, done here by skin / hue / position).
 * 4. Tone, per channel as a print stock does it: contrast in stops around 18 % grey (grey stays), then a soft shoulder
 *    that rolls the highlights into white instead of clipping, and a print's black a touch above zero.
 * 5. Density («subtractive saturation», «Плёнка» only): the more saturated a colour, the darker.
 * 6. Silver retention (bleach bypass / ENR): the picture over its own brightness — more contrast, less colour.
 * 7. Colour in YCbCr: saturation overall and per hue (red, yellow, green, blue), hues turned (green towards yellow…).
 * 8. Toning: shadows, mid-tones, highlights each moved in Cb / Cr; black and white stay neutral.
 * 9. Skin, always: colours near the skin tone line (123° on a vectorscope, the same for every skin, light or dark —
 *    skins differ in lightness and saturation, not in hue) keep the hue they were shot with through 1, 7 and 8;
 *    [Grade.skin] turns them part of the way to the line (an even skin tone).
 *
 * Input and output are gamma-encoded BT.709 (what media3's effects see for SDR video). Halation, grain and the vignette
 * need neighbours / time / position and live in the shader only.
 */
object FilmMath {
    const val GAMMA = 2.4f
    const val GREY = 0.18f
    /** Extra contrast in stops per stop at film = 1. */
    const val CONTRAST = 0.25f
    /** The shoulder starts here (light); above it values approach 1 and never reach it. */
    const val KNEE = 0.7f
    /** The print's black (light) at full curve: about 13 of 255. */
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
    /** How far skin hues move towards the skin line at skin = 1. */
    const val SKIN_PULL = 0.6f
    /** The skin tone line on a vectorscope: 123° from +Cb towards +Cr. */
    const val SKIN_CB = -0.5446f
    const val SKIN_CR = 0.8387f
    /** Hue centres (vectorscope degrees) of the per-hue colour: red, yellow, green, blue. */
    val HUES = floatArrayOf(103f, 167f, 241f, 347f)
    /** A hue's weight is cos(distance)^HUE_POWER: about ±40° wide. */
    const val HUE_POWER = 4f

    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /**
     * Everything the grade does, from the sliders and a preset ([Looks.grade]); the defaults change nothing.
     * [wb]: light gains (luma kept); [flash]: light added; [faceLift] / [skyDark] / [vignette]: stops; [contrast]: extra
     * contrast per stop and [curve] how much of the print curve (both on top of [film]); [bleach] 0…1; [sat] overall
     * and [hueSat] / [hueShift] (degrees) per hue of [HUES]; [shadowTint] / [midTint] / [highTint]: Cb, Cr.
     */
    class Grade(
        val film: Float = 0f,
        val warmth: Float = 0f,
        val skin: Float = 0f,
        val wb: FloatArray = floatArrayOf(1f, 1f, 1f),
        val flash: Float = 0f,
        val faceLift: Float = 0f,
        val skyDark: Float = 0f,
        val vignette: Float = 0f,
        val contrast: Float = 0f,
        val curve: Float = 0f,
        val bleach: Float = 0f,
        val sat: Float = 1f,
        val hueSat: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        val hueShift: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        val shadowTint: FloatArray = floatArrayOf(0f, 0f),
        val midTint: FloatArray = floatArrayOf(0f, 0f),
        val highTint: FloatArray = floatArrayOf(0f, 0f),
    ) {
        /** Light gains of warmth and [wb] together, brightness kept. */
        fun gains(): FloatArray {
            val r = (1f + WARMTH * warmth) * wb[0]
            val g = wb[1]
            val b = (1f - WARMTH * warmth) * wb[2]
            val n = KR * r + KG * g + KB * b
            return floatArrayOf(r / n, g / n, b / n)
        }

        /** How much of the print curve, and its contrast per stop. */
        fun curveAmount() = max(film, curve)
        fun curveContrast() = 1f + CONTRAST * film + contrast
    }

    /** One channel's light through the print curve: [contrast] per stop, mixed in by [amount]. */
    fun tone(lin: Float, amount: Float, contrast: Float): Float {
        if (amount == 0f) return lin
        val stops = ln(max(lin, 1e-5f) / GREY) / ln(2f) * contrast
        var y = GREY * 2f.pow(stops)
        if (y > KNEE) y = KNEE + (1f - KNEE) * (1f - exp(-(y - KNEE) / (1f - KNEE)))
        y = BLACK * amount + (1f - BLACK * amount) * y
        return lin + (y - lin) * amount
    }

    /** The «Плёнка» curve alone (film = how much). */
    fun tone(lin: Float, film: Float): Float = tone(lin, film, 1f + CONTRAST * film)

    /**
     * How much of skin a colour is (0…1): within about ±30° of the skin line, its colour in proportion to its brightness
     * (so dark and light skins alike — a dark skin has less colour in absolute terms; skins measure 0.2…0.3), not grey,
     * not a saturated orange (about 0.45 and up), not black or white.
     */
    fun skinMask(luma: Float, cb: Float, cr: Float): Float {
        val c = sqrt(cb * cb + cr * cr)
        if (c < 1e-4f) return 0f
        val cos = (cb * SKIN_CB + cr * SKIN_CR) / c
        val rel = c / max(luma, 0.05f)
        return smooth(0.866f, 0.966f, cos) * smooth(0.05f, 0.12f, rel) * (1f - smooth(0.36f, 0.45f, rel)) *
            smooth(0.03f, 0.1f, luma) * (1f - smooth(0.88f, 0.98f, luma))
    }

    /** The weight of each of [HUES] for a colour of hue [angle] (radians). */
    fun hueWeights(angle: Float) = FloatArray(4) { i ->
        max(cos(angle - Math.toRadians(HUES[i].toDouble()).toFloat()), 0f).pow(HUE_POWER)
    }

    fun apply(rgb: FloatArray, film: Float, warmth: Float, skin: Float): FloatArray =
        apply(rgb, Grade(film = film, warmth = warmth, skin = skin))

    fun apply(rgb: FloatArray, grade: Grade): FloatArray {
        val (r0, g0, b0) = rgb
        val y0 = KR * r0 + KG * g0 + KB * b0
        val cb0 = (b0 - y0) / 1.8556f
        val cr0 = (r0 - y0) / 1.5748f
        val mask = skinMask(y0, cb0, cr0)
        val w0 = hueWeights(atan2(cr0, cb0))
        val c0 = sqrt(cb0 * cb0 + cr0 * cr0)

        // 1–5 in light.
        val gains = grade.gains()
        var lin = FloatArray(3) { max(rgb[it], 0f).pow(GAMMA) * gains[it] + grade.flash }
        val lift = 2f.pow(grade.faceLift * mask) *
            2f.pow(-grade.skyDark * w0[3] * smooth(0.02f, 0.06f, c0) * smooth(0.4f, 0.8f, y0))
        val amount = grade.curveAmount()
        val contrast = grade.curveContrast()
        lin = FloatArray(3) { tone(lin[it] * lift, amount, contrast) }
        val top = max(lin[0], max(lin[1], lin[2]))
        val sat = if (top > 1e-5f) (top - min(lin[0], min(lin[1], lin[2]))) / top else 0f
        val dense = 1f - DENSITY * grade.film * sat * sat
        var g = FloatArray(3) { (lin[it] * dense).coerceIn(0f, 1f).pow(1f / GAMMA) }

        // 6. Silver retention: an overlay of the picture's brightness on it.
        if (grade.bleach > 0f) {
            val l = KR * g[0] + KG * g[1] + KB * g[2]
            g = FloatArray(3) { i ->
                val o = if (l < 0.5f) 2f * g[i] * l else 1f - 2f * (1f - g[i]) * (1f - l)
                g[i] + (o - g[i]) * grade.bleach
            }
        }

        // 7–9 in YCbCr.
        val y = KR * g[0] + KG * g[1] + KB * g[2]
        var cb = (g[2] - y) / 1.8556f
        var cr = (g[0] - y) / 1.5748f
        val w = hueWeights(atan2(cr, cb))
        var satK = 1f
        var turn = 0f
        for (i in 0..3) {
            satK += w[i] * (grade.hueSat[i] - 1f)
            turn += w[i] * grade.hueShift[i]
        }
        satK = max(satK, 0f) * grade.sat
        val t = Math.toRadians(turn.toDouble()).toFloat()
        val rcb = (cb * cos(t) - cr * sin(t)) * satK
        val rcr = (cb * sin(t) + cr * cos(t)) * satK
        cb = rcb
        cr = rcr
        // In the shadows, the middle and the highlights, not in black and white: those stay neutral, as on a print.
        val shadows = smooth(0f, 0.12f, y) * (1f - smooth(0.12f, 0.55f, y))
        val mids = 4f * y * (1f - y)
        val highs = smooth(0.45f, 0.8f, y) * (1f - 0.7f * smooth(0.85f, 1f, y))
        val tcb = cb + grade.film * (SHADOW_CB * shadows + HIGH_CB * highs) +
            shadows * grade.shadowTint[0] + mids * grade.midTint[0] + highs * grade.highTint[0]
        val tcr = cr + grade.film * (SHADOW_CR * shadows + HIGH_CR * highs) +
            shadows * grade.shadowTint[1] + mids * grade.midTint[1] + highs * grade.highTint[1]
        // Skin: the shot's hue (before balance, hue turns and toning) turned part of the way to the skin line, at the
        // graded strength.
        val c = sqrt(cb * cb + cr * cr)
        var pcb = tcb
        var pcr = tcr
        if (c0 > 1e-5f) {
            val k = SKIN_PULL * grade.skin
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
        return Math.toDegrees(atan2(((rgb[0] - y) / 1.5748f).toDouble(), ((rgb[2] - y) / 1.8556f).toDouble())).toFloat()
    }

    /**
     * Light gains of a colour-temperature filter: the light of a black body at [toK] over that at [fromK] (CIE xy by
     * Kim et al.'s fit, then XYZ → linear Rec.709), brightness kept. A warming 81EF: 5500 K → 4270 K (+52 mired).
     */
    fun temperatureGains(fromK: Float, toK: Float): FloatArray {
        val a = blackBody(fromK)
        val b = blackBody(toK)
        val g = FloatArray(3) { b[it] / a[it] }
        val n = KR * g[0] + KG * g[1] + KB * g[2]
        return FloatArray(3) { g[it] / n }
    }

    private fun blackBody(k: Float): FloatArray {
        val t = k.toDouble()
        val x = if (t <= 4000) -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        else -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        val bx = x / y
        val bz = (1 - x - y) / y
        return floatArrayOf(
            (3.2404542 * bx - 1.5371385 - 0.4985314 * bz).toFloat(),
            (-0.9692660 * bx + 1.8760108 + 0.0415560 * bz).toFloat(),
            (0.0556434 * bx - 0.2040259 + 1.0572252 * bz).toFloat(),
        )
    }
}
