package app.goultra.transfer.camera

internal object HttpRanges {
    private val unsatisfied = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
    private val satisfied = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

    fun matchesRange(header: String?, start: Long, size: Long): Boolean {
        val match = header?.trim()?.let { satisfied.matchEntire(it) } ?: return false
        val first = match.groupValues[1].toLongOrNull() ?: return false
        val last = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start >= 0 && first == start && total == size && last >= first && last < total
    }

    fun unsatisfiedSize(header: String?): Long? =
        header?.trim()?.let { unsatisfied.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }

    fun isComplete(localSize: Long, remoteSize: Long?): Boolean =
        localSize >= 0 && remoteSize != null && localSize == remoteSize
}
