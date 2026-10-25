package app.goultra.transfer.camera

import java.io.ByteArrayOutputStream

/** Minimal protobuf writer: only what the camera protocol needs. */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, value: Long) = apply {
        rawVarint((field shl 3).toLong())
        rawVarint(value)
    }

    fun bytes(field: Int, data: ByteArray) = apply {
        rawVarint(((field shl 3) or 2).toLong())
        rawVarint(data.size.toLong())
        out.write(data)
    }

    fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

    /** double (wire type 1, little-endian IEEE 754). */
    fun double(field: Int, value: Double) = apply {
        rawVarint(((field shl 3) or 1).toLong())
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 0 until 8) out.write((bits ushr (8 * i)).toInt() and 0xFF)
    }

    /** float (wire type 5, little-endian IEEE 754). */
    fun float(field: Int, value: Float) = apply {
        rawVarint(((field shl 3) or 5).toLong())
        val bits = java.lang.Float.floatToIntBits(value)
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
    }

    /** Packed repeated varints (proto3 default for repeated enums). */
    fun packed(field: Int, values: List<Long>) =
        bytes(field, PbWriter().also { w -> values.forEach { w.rawVarint(it) } }.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class PbField(val number: Int, val wireType: Int, val varint: Long, val data: ByteArray?) {
    val string: String? get() = data?.toString(Charsets.UTF_8)
}

/** Schema-less protobuf parse: top-level fields only, nested messages stay in [PbField.data]. */
fun ByteArray.pbFields(): List<PbField> {
    val res = mutableListOf<PbField>()
    var pos = 0
    fun readVarint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val b = this[pos++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }
    while (pos < size) {
        val tag = readVarint()
        val number = (tag ushr 3).toInt()
        when (val wire = (tag and 7).toInt()) {
            0 -> res += PbField(number, wire, readVarint(), null)
            1 -> { res += PbField(number, wire, 0, copyOfRange(pos, pos + 8)); pos += 8 }
            2 -> {
                val n = readVarint().toInt()
                res += PbField(number, wire, 0, copyOfRange(pos, pos + n))
                pos += n
            }
            5 -> { res += PbField(number, wire, 0, copyOfRange(pos, pos + 4)); pos += 4 }
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
    }
    return res
}

fun List<PbField>.field(number: Int): PbField? = firstOrNull { it.number == number }
