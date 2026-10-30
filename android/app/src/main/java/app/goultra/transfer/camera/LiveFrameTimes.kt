package app.goultra.transfer.camera

import java.io.IOException

internal fun verifyLiveFrameTimes(original: Sequence<Long>, preview: Sequence<Long>) {
    val high = original.iterator()
    val low = preview.iterator()
    var count = 0
    while (high.hasNext() && low.hasNext()) {
        if (high.next() != low.next()) throw IOException("метки кадров оригинала и превью не совпадают")
        count++
    }
    if (count == 0 || high.hasNext() || low.hasNext())
        throw IOException("количество кадров оригинала и превью не совпадает")
}
