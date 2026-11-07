package app.goultra.transfer.camera

/** How a project is put into one video file: which pieces go together, and whether it can be made without re-encoding. */
object ProjectExport {
    /** One file's pieces in a row: read with one extractor, cut and joined without re-reading the file. */
    data class Run(val file: String, val pieces: List<Highlight>)

    /**
     * The project's pieces as runs: a piece joins the run before it when it is of the same file and starts after that
     * run's last piece ends (the cutter needs its pieces sorted and apart); anything else starts a new run.
     */
    fun runs(clips: List<ProjectClip>): List<Run> {
        val out = mutableListOf<Run>()
        for (c in clips) {
            val piece = Highlight(c.fromMs, c.toMs)
            val last = out.lastOrNull()
            if (last != null && last.file == c.file && c.fromMs >= (last.pieces.last().endMs ?: 0L)) {
                out[out.lastIndex] = last.copy(pieces = last.pieces + piece)
            } else {
                out += Run(c.file, listOf(piece))
            }
        }
        return out
    }

    /** As shot (no re-encoding) needs video pieces only: a photo has no frames to copy, it has to be encoded. */
    fun canCopy(clips: List<ProjectClip>): Boolean = clips.isNotEmpty() && clips.none { it.role == ProjectClip.Role.STILL }

    /** A file name made of the project's name: only what every gallery takes, and never empty. */
    fun fileStem(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').take(60).ifEmpty { "Project" }
}
