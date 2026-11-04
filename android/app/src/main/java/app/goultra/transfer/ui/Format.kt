package app.goultra.transfer.ui

fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ".format(bytes / (1L shl 30).toDouble()) + tr("ГБ", "GB")
    bytes >= 1L shl 20 -> "%.0f ".format(bytes / (1L shl 20).toDouble()) + tr("МБ", "MB")
    else -> "%.0f ".format(bytes / 1024.0) + tr("КБ", "KB")
}

/** 1 файл, 3 файла, 5 файлов / 1 file, 3 files / 3 Dateien … */
fun countLabel(n: Int): String {
    if (AppPrefs.languageTag == "ru") {
        val word = when {
            n % 100 in 11..14 -> "файлов"
            n % 10 == 1 -> "файл"
            n % 10 in 2..4 -> "файла"
            else -> "файлов"
        }
        return "$n $word"
    }
    // Turkish, Chinese and Japanese don't change the noun after a number; French keeps the singular for 0 and 1.
    val one = tr("файл", "file")
    val many = tr("файл", "files")
    val noun = when {
        AppPrefs.languageTag in setOf("tr", "zh", "ja") -> one
        AppPrefs.languageTag == "fr" -> if (n <= 1) one else many
        else -> if (n == 1) one else many
    }
    return "$n $noun"
}

fun formatSpeed(bytesPerSec: Long): String = "%.1f ".format(bytesPerSec / (1L shl 20).toDouble()) + tr("МБ/с", "MB/s")

/** "1,2 ГБ из 3,4 ГБ · 25,3 МБ/с" */
fun DownloadProgress.details(): String =
    "${formatSize(done)} ${tr("из", "of")} ${formatSize(total)}" + if (bytesPerSec > 0) " · ${formatSpeed(bytesPerSec)}" else ""
