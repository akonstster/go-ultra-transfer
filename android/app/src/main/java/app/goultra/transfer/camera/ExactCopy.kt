package app.goultra.transfer.camera

import java.io.EOFException
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

internal fun readFullyAt(input: FileInputStream, at: Long, count: Int): ByteArray {
    val bytes = ByteArray(count)
    input.channel.position(at)
    var done = 0
    while (done < count) {
        val read = input.read(bytes, done, count - done)
        if (read <= 0) throw EOFException("получено $done из $count байт")
        done += read
    }
    return bytes
}

internal fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
    require(length >= 0)
    val buffer = ByteArray(256 * 1024)
    var left = length
    while (left > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (read <= 0) throw EOFException("получено ${length - left} из $length байт")
        output.write(buffer, 0, read)
        left -= read
    }
}
