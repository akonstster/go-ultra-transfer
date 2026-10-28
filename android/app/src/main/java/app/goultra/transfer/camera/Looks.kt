package app.goultra.transfer.camera

import app.goultra.transfer.camera.FilmMath.Grade
import kotlin.math.pow

/**
 * «Стиль» presets: the looks of films known for their colour, built from what is published about how they were made
 * (lab process, filters, the grade) — models of those steps, not copies of the grades: no numbers of the grades
 * themselves are published. Each value says where it comes from; «approx.» marks what is judged from the description.
 */
enum class Look(val ru: String, val en: String, val aboutRu: String, val aboutEn: String) {
    NONE("Нет", "None", "", ""),

    /**
     * Saving Private Ryan (1998), Janusz Kamiński, Oscar for cinematography. American Cinematographer, Aug 1998: ENR
     * (silver retention) at 70 % → bleach 0.7; the negative flashed at about 15 % «so that I didn't get totally sharp
     * blacks», with uncoated lenses adding veil → the print's toe squeezes a negative's flash to a small lift of the
     * blacks: +0.0025 in light, about 15–20 of 255 after the silver (approx.); Spielberg chose to desaturate → colour
     * ×0.7 on top of what the silver takes (approx.; a review of the companion book speaks of about 60 % less colour);
     * blood had blue mixed in so that it stayed red under ENR → reds kept (×1.25 against the rest, approx.). Matched by
     * eye to a still (Ryan in the ruined town): deep but not crushed blacks, cool grey-teal shadows and mid-tones,
     * muted olive skin → a little teal toning.
     */
    RYAN(
        "Рядовой Райан", "Private Ryan",
        "Камински, 1998, Оскар: проявка с сохранением серебра (ENR 70 %), засветка негатива ~15 %, объективы без " +
            "просветления, цвет приглушён, красный сохранён.",
        "Kamiński, 1998, Oscar: silver retention (ENR 70 %), the negative flashed ~15 %, uncoated lenses, colour " +
            "muted, reds kept.",
    ),

    /**
     * O Brother, Where Art Thou? (2000), Roger Deakins, Oscar nomination; the first feature graded end to end in a
     * digital intermediate (Cinesite, colourist Julius Friede). ASC: the greens of trees and grass turned to dry browns
     * and yellows → green turned 35° towards yellow (olive), its colour ×0.55 (approx.); an orange-yellow dress that
     * stood out was toned down → yellows ×0.8 (approx.); an overall yellow, almost sepia cast → warm toning of the
     * mid-tones and shadows. Matched by eye to a still (the three on the dirt road): the field dry olive-yellow, far
     * trees still green but olive, the sky kept — pale, cyan-white — so blues ×0.6, not gone; tanned warm skin;
     * cream highlights.
     */
    O_BROTHER(
        "О, где же ты, брат?", "O Brother",
        "Дикинс, 2000: первая полностью цифровая цветокоррекция. Зелень травы и деревьев сделана сухой, жёлто-коричневой, " +
            "общий тон жёлтый, почти сепия.",
        "Deakins, 2000: the first full digital grade. Grass and trees turned dry yellow-brown, an overall yellow, " +
            "almost sepia cast.",
    ),

    /**
     * Amélie (2001), Bruno Delbonnel, Oscar nomination. A warming 81EF filter on the camera for day scenes → its
     * standard shift, +52 mired (5500 K → 4270 K) in light; in the digital grade yellows, greens and reds were pushed and
     * there is hardly any blue → red ×1.25, yellow ×1.15, green ×1.2 turned 20° towards gold, blue ×0.25 (approx.). Matched
     * by eye to a still of the film (the Sacré-Cœur ride): white stone and clouds gone yellow-olive, brown-olive
     * shadows, saturated reds and oranges → a strong olive-yellow toning of the mid-tones and highlights, warm shadows,
     * colour ×1.05. Faces go golden with the rest, as in the still — and keeping their hue against so strong a
     * grade showed its edges on faces — so no skin guard here.
     */
    AMELIE(
        "Амели", "Amélie",
        "Дельбоннель, 2001: тёплый фильтр 81EF на камере, в цифровой цветокоррекции усилены красный, жёлтый и " +
            "зелёный, синего почти нет.",
        "Delbonnel, 2001: a warming 81EF filter on the camera; the digital grade pushed reds, yellows and greens, " +
            "hardly any blue.",
    ),

    /**
     * The Revenant (2015), Emmanuel Lubezki, Oscar for cinematography; graded at Technicolor (Steven J. Scott).
     * Lubezki (Codex): natural light only; in the grade backgrounds darkened, faces lightened, skies changed → faces
     * +0.4 stop, light skies −0.4 stop, corners −0.6 stop (approx.); colourists describe muted blues and greys → colour
     * ×0.55, a cool balance (5500 → 7000 K), teal in the shadows, greens a little towards cyan (approx., matched by eye
     * to a still of the film).
     */
    REVENANT(
        "Выживший", "The Revenant",
        "Любецки, 2015, Оскар: только естественный свет; при цветокоррекции лица высветлены, фоны и небо затемнены, " +
            "палитра приглушённая сине-серая.",
        "Lubezki, 2015, Oscar: natural light only; in the grade faces lightened, backgrounds and skies darkened, a muted " +
            "blue-grey palette.",
    );

    companion object {
        fun of(id: Int) = entries.getOrElse(id) { NONE }

        /** The grade of [look] at [strength] (0…1, mixed with no change), with the sliders on top. */
        fun grade(look: Look, strength: Float, film: Float, warmth: Float, skin: Float): Grade {
            val s = if (look == NONE) 0f else strength.coerceIn(0f, 1f)
            val p = preset(look)
            fun mix(neutral: Float, v: Float) = neutral + (v - neutral) * s
            fun mix(neutral: FloatArray, v: FloatArray) = FloatArray(v.size) { mix(neutral[it], v[it]) }
            return Grade(
                film = film,
                warmth = warmth,
                skin = skin,
                wb = FloatArray(3) { p.wb[it].pow(s) },
                flash = p.flash * s,
                faceLift = p.faceLift * s,
                skyDark = p.skyDark * s,
                vignette = p.vignette * s,
                contrast = p.contrast * s,
                curve = p.curve * s,
                bleach = p.bleach * s,
                sat = mix(1f, p.sat),
                hueSat = mix(floatArrayOf(1f, 1f, 1f, 1f), p.hueSat),
                hueShift = mix(floatArrayOf(0f, 0f, 0f, 0f), p.hueShift),
                shadowTint = mix(floatArrayOf(0f, 0f), p.shadowTint),
                midTint = mix(floatArrayOf(0f, 0f), p.midTint),
                highTint = mix(floatArrayOf(0f, 0f), p.highTint),
                skinGuard = mix(1f, p.skinGuard),
            )
        }

        /** Hues: red, yellow, green, blue (FilmMath.HUES). Tints: Cb, Cr (−Cb +Cr = yellow-orange, +Cb −Cr = teal). */
        private fun preset(look: Look): Grade = when (look) {
            NONE -> Grade()
            RYAN -> Grade(
                flash = 0.0025f,
                bleach = 0.7f,
                sat = 0.7f,
                hueSat = floatArrayOf(1.25f, 1f, 1f, 1f),
                shadowTint = floatArrayOf(0.01f, -0.01f),
                midTint = floatArrayOf(0.006f, -0.008f),
            )
            O_BROTHER -> Grade(
                contrast = 0.1f, curve = 1f,
                sat = 0.9f,
                hueSat = floatArrayOf(1f, 0.8f, 0.55f, 0.6f),
                hueShift = floatArrayOf(0f, 0f, -35f, 0f),
                highTint = floatArrayOf(-0.012f, 0.004f),
                shadowTint = floatArrayOf(-0.01f, 0.008f),
                midTint = floatArrayOf(-0.025f, 0.012f),
            )
            AMELIE -> Grade(
                wb = FilmMath.temperatureGains(5500f, 4270f),
                contrast = 0.15f, curve = 1f,
                sat = 1.05f,
                hueSat = floatArrayOf(1.15f, 1.15f, 1.2f, 0.25f),
                hueShift = floatArrayOf(0f, 0f, -20f, 0f),
                shadowTint = floatArrayOf(-0.025f, 0.008f),
                midTint = floatArrayOf(-0.055f, -0.005f),
                highTint = floatArrayOf(-0.06f, -0.008f),
                skinGuard = 0f,
            )
            REVENANT -> Grade(
                wb = FilmMath.temperatureGains(5500f, 7000f),
                faceLift = 0.4f, skyDark = 0.4f, vignette = 0.6f,
                contrast = 0.15f, curve = 1f,
                sat = 0.55f,
                hueShift = floatArrayOf(0f, 0f, 10f, 0f),
                shadowTint = floatArrayOf(0.012f, -0.012f),
                highTint = floatArrayOf(0.006f, -0.004f),
            )
        }
    }
}
