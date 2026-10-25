package app.goultra.transfer.camera

fun ByteArray.hex(max: Int = 64): String {
    val shown = take(max).joinToString(" ") { "%02x".format(it) }
    return if (size > max) "$shown … (+${size - max} B)" else shown
}

fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

fun u32(b: ByteArray, at: Int): Int = u16(b, at) or (u16(b, at + 2) shl 16)

fun le32(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
