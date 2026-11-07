package app.goultra.transfer.camera

import java.io.File
import kotlin.random.Random

/**
 * The projects of this phone: one small JSON per project under `files/projects/` (see [Project] — the media itself
 * stays on the camera). A broken file is skipped, not fatal: losing one project must not hide the others.
 */
class Projects(private val dir: File) {
    private companion object {
        const val SUFFIX = ".json"
        const val TEMP = ".tmp"
    }

    init {
        dir.mkdirs()
    }

    /** Newest first, the way the gallery shows things. */
    fun list(): List<Project> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
        .orEmpty()
        .mapNotNull { f -> runCatching { f.readText() }.getOrNull()?.let(Project::decode) }
        .sortedByDescending { it.createdMs }

    /** A new, empty project; [createdMs] is passed in tests to keep the ids readable. */
    fun create(name: String, createdMs: Long = System.currentTimeMillis()): Project {
        val project = Project(id = "$createdMs-%04x".format(Random.nextInt(0x10000)), name = name, createdMs = createdMs)
        save(project)
        return project
    }

    fun save(p: Project) {
        // Write beside and move over: a crash mid-save must not leave a half-written project behind, and the move is
        // atomic where the file system allows it (deleting the old one first left a window with no project at all).
        val tmp = File(dir, p.id + TEMP)
        tmp.writeText(Project.encode(p))
        val out = File(dir, p.id + SUFFIX)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                out.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            // A file system without atomic moves: the plain replace, still never a half-written file.
            runCatching { java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { out.writeText(tmp.readText()) }
            tmp.delete()
        }
    }

    fun rename(p: Project, name: String): Project = p.copy(name = name).also(::save)

    fun delete(p: Project) {
        File(dir, p.id + SUFFIX).delete()
    }
}