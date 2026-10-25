package app.goultra.transfer.ui

fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f КБ".format(bytes / 1024.0)
}

/** 1 файл, 3 файла, 5 файлов */
fun countLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "файлов"
        n % 10 == 1 -> "файл"
        n % 10 in 2..4 -> "файла"
        else -> "файлов"
    }
    return "$n $word"
}

fun formatSpeed(bytesPerSec: Long): String = "%.1f МБ/с".format(bytesPerSec / (1L shl 20).toDouble())

/** "1,2 ГБ из 3,4 ГБ · 25,3 МБ/с" */
fun DownloadProgress.details(): String =
    "${formatSize(done)} из ${formatSize(total)}" + if (bytesPerSec > 0) " · ${formatSpeed(bytesPerSec)}" else ""
