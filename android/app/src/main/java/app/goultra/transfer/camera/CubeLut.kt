package app.goultra.transfer.camera

/**
 * A 3D colour table from a .cube file (e.g. Insta360's «GO Ultra I-Log to Rec.709»): [size]³ entries, red changing
 * fastest, values 0…1 (DOMAIN_MIN / DOMAIN_MAX other than 0…1 aren't supported; 1D tables aren't either).
 */
class CubeLut private constructor(val title: String?, val size: Int, private val rgb: FloatArray) {
    /**
     * The table as media3's SingleColorLut takes it — `[r][g][b]` → ARGB_8888 — mixed with no change by [strength]
     * (0 = as shot, 1 = the full table).
     */
    fun cube(strength: Float): Array<Array<IntArray>> {
        val k = strength.coerceIn(0f, 1f)
        val step = 1f / (size - 1)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    val i = 3 * (r + size * (g + size * b))
                    fun mix(identity: Float, table: Float) = identity + (table - identity) * k
                    argb(mix(r * step, rgb[i]), mix(g * step, rgb[i + 1]), mix(b * step, rgb[i + 2]))
                }
            }
        }
    }

    /** [rgb] (0…1) through the table, trilinear, mixed with no change by [strength] — for small pictures on the CPU. */
    fun apply(rgb: FloatArray, strength: Float): FloatArray {
        val n = size - 1
        val f = FloatArray(3) { rgb[it].coerceIn(0f, 1f) * n }
        val i0 = IntArray(3) { minOf(f[it].toInt(), n - 1) }
        val t = FloatArray(3) { f[it] - i0[it] }
        val out = FloatArray(3)
        for (dr in 0..1) for (dg in 0..1) for (db in 0..1) {
            val w = (if (dr == 1) t[0] else 1 - t[0]) * (if (dg == 1) t[1] else 1 - t[1]) * (if (db == 1) t[2] else 1 - t[2])
            val i = 3 * ((i0[0] + dr) + size * ((i0[1] + dg) + size * (i0[2] + db)))
            for (c in 0..2) out[c] += w * this.rgb[i + c]
        }
        return FloatArray(3) { rgb[it] + (out[it] - rgb[it]) * strength }
    }

    /**
     * The table as one RGBA 8-bit image for a 2D texture: [size] slices of [size] × [size] side by side — blue picks the
     * slice, red goes across within it, green down; [size]² wide, [size] high.
     */
    fun atlas(): ByteArray {
        val w = size * size
        val out = ByteArray(w * size * 4)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val i = 3 * (r + size * (g + size * b))
            val o = 4 * (g * w + b * size + r)
            for (c in 0..2) out[o + c] = Math.round(rgb[i + c].coerceIn(0f, 1f) * 255f).toByte()
            out[o + 3] = -1
        }
        return out
    }

    companion object {
        private val SPACES = Regex("\\s+")

        private fun argb(r: Float, g: Float, b: Float): Int {
            fun c(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255f)
            return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }

        /** Parses a .cube file's text; throws IllegalArgumentException for anything it can't use. */
        fun parse(text: String): CubeLut {
            var title: String? = null
            var size = 0
            var values = FloatArray(3 * 33 * 33 * 33)
            var count = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("TITLE") -> title = line.removePrefix("TITLE").trim().trim('"')
                    line.startsWith("LUT_3D_SIZE") -> size = line.removePrefix("LUT_3D_SIZE").trim().toInt()
                    line.startsWith("LUT_1D_SIZE") -> throw IllegalArgumentException("1D LUT")
                    line.startsWith("DOMAIN_MIN") -> require(line.split(SPACES).drop(1).all { it.toFloat() == 0f }) { "DOMAIN_MIN" }
                    line.startsWith("DOMAIN_MAX") -> require(line.split(SPACES).drop(1).all { it.toFloat() == 1f }) { "DOMAIN_MAX" }
                    line[0].isLetter() -> Unit // other keywords
                    else -> {
                        val parts = line.split(SPACES)
                        require(parts.size == 3) { "bad line: $line" }
                        if (count + 3 > values.size) values = values.copyOf(values.size * 2)
                        for (v in parts) values[count++] = v.toFloat()
                    }
                }
            }
            require(size in 2..256) { "LUT_3D_SIZE $size" }
            require(count == size * size * size * 3) { "${count / 3} entries for size $size" }
            return CubeLut(title, size, values.copyOf(count))
        }
    }
}
