package app.goultra.transfer.camera

import java.time.LocalDateTime
import kotlin.comparisons.nullsLast

/** Where a batch of files joins a project that already has clips. */
enum class ProjectPlacement { START, END, BY_DATE }

/**
 * The selected files in the order they join a project: oldest first, so a batch picked from the gallery (which lists
 * the newest on top) reads chronologically. A file whose date is unknown goes last; equal or missing dates are ordered
 * by name, which keeps the result stable. The pieces inside one file are not touched: they already follow their source.
 */
fun filesInProjectOrder(files: List<CameraFile>): List<CameraFile> =
    files.sortedWith(
        compareBy<CameraFile, LocalDateTime?>(nullsLast(naturalOrder())) {
            it.created ?: CameraFile.createdFromName(it.name)
        }.thenBy { it.name },
    )

/**
 * The project's clips after [groups] — each a whole file's pieces, already oldest first — join them at [placement].
 * [createdOf] gives a source file's shooting time; a clip whose date is unknown does not block a chronological insert
 * (not knowing is not a reason to call it newer). BY_DATE puts each file before the first clip of a newer one and
 * expands the project there, while keeping every existing clip and every file's own pieces in order.
 */
fun clipsAdded(
    existing: List<ProjectClip>,
    groups: List<List<ProjectClip>>,
    placement: ProjectPlacement,
    createdOf: (String) -> LocalDateTime?,
): List<ProjectClip> {
    val whole = groups.filter { it.isNotEmpty() }
    val out = when (placement) {
        ProjectPlacement.START -> (whole.flatten() + existing).toMutableList()
        ProjectPlacement.END -> (existing + whole.flatten()).toMutableList()
        ProjectPlacement.BY_DATE -> existing.toMutableList().also { list ->
            for (group in whole) {
                val time = createdOf(group.first().file)
                val before = if (time == null) -1 else list.indexOfFirst { clip ->
                    clip.atMs == null && createdOf(clip.file)?.isAfter(time) == true
                }
                if (before < 0) list.addAll(group) else list.addAll(before, group)
            }
        }
    }
    return out
}
