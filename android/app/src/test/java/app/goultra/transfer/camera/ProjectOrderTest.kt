package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** The order files join a project: a gallery batch is newest first, while the project must read oldest first. */
class ProjectOrderTest {
    private fun file(name: String, at: String? = null) = CameraFile(
        uri = "/DCIM/Camera01/$name",
        size = null,
        durationSec = null,
        width = null,
        height = null,
        fps = null,
        created = at?.let(LocalDateTime::parse),
    )

    private fun clip(name: String, from: Long = 0L) = ProjectClip(name, from, from + 1_000L)

    @Test
    fun `a selected batch goes oldest first and an undated file goes last`() {
        val files = listOf(
            file("new.mp4", "2026-11-09T12:00:00"),
            file("undated.mp4"),
            file("middle.mp4", "2026-11-08T12:00:00"),
            file("old.mp4", "2026-11-07T12:00:00"),
        )

        assertEquals(
            listOf("old.mp4", "middle.mp4", "new.mp4", "undated.mp4"),
            filesInProjectOrder(files).map { it.name },
        )
    }

    @Test
    fun `the start and the end keep the batch order`() {
        val existing = listOf(clip("middle.mp4"))
        val groups = listOf(listOf(clip("old.mp4")), listOf(clip("new.mp4")))
        val date = { name: String ->
            when (name) {
                "old.mp4" -> LocalDateTime.parse("2026-11-07T12:00:00")
                "middle.mp4" -> LocalDateTime.parse("2026-11-08T12:00:00")
                else -> LocalDateTime.parse("2026-11-09T12:00:00")
            }
        }

        assertEquals(
            listOf("old.mp4", "new.mp4", "middle.mp4"),
            clipsAdded(existing, groups, ProjectPlacement.START, date).map { it.file },
        )
        assertEquals(
            listOf("middle.mp4", "old.mp4", "new.mp4"),
            clipsAdded(existing, groups, ProjectPlacement.END, date).map { it.file },
        )
    }

    @Test
    fun `by date expands the project and keeps every file's pieces together`() {
        val existing = listOf(clip("middle.mp4", 0L), clip("middle.mp4", 2_000L), clip("latest.mp4"))
        val groups = listOf(
            listOf(clip("older.mp4", 0L), clip("older.mp4", 2_000L)),
            listOf(clip("middle-late.mp4")),
        )
        val date = { name: String ->
            when (name) {
                "older.mp4" -> LocalDateTime.parse("2026-11-06T12:00:00")
                "middle.mp4" -> LocalDateTime.parse("2026-11-08T12:00:00")
                "middle-late.mp4" -> LocalDateTime.parse("2026-11-09T12:00:00")
                else -> LocalDateTime.parse("2026-11-10T12:00:00")
            }
        }

        assertEquals(
            listOf("older.mp4", "older.mp4", "middle.mp4", "middle.mp4", "middle-late.mp4", "latest.mp4"),
            clipsAdded(existing, groups, ProjectPlacement.BY_DATE, date).map { it.file },
        )
    }

    @Test
    fun `an unknown date does not block a chronological insert`() {
        val existing = listOf(clip("unknown.mp4"), clip("new.mp4"))
        val groups = listOf(listOf(clip("old.mp4")))
        val date = { name: String ->
            when (name) {
                "old.mp4" -> LocalDateTime.parse("2026-11-07T12:00:00")
                "new.mp4" -> LocalDateTime.parse("2026-11-09T12:00:00")
                else -> null
            }
        }

        assertEquals(
            listOf("unknown.mp4", "old.mp4", "new.mp4"),
            clipsAdded(existing, groups, ProjectPlacement.BY_DATE, date).map { it.file },
        )
    }
}
