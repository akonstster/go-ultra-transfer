package app.goultra.transfer.camera

import org.json.JSONArray
import org.json.JSONObject

/** What the project is being made for: the preset that shapes the montage (see `memory-bank/projects-plan.md`). */
enum class Scenario { TRAVEL, MUSIC, ACTION, VLOG, SLIDESHOW, TIMELAPSE, CUSTOM }

/** How the montage is paced; every one of them adapts to the track's structure (plan §3). */
enum class Dynamics { EVEN, RHYTHMIC, DYNAMIC, MOOD, SHUFFLE }

/** One piece of the montage: a range of one camera file, in the project's own order. */
data class ProjectClip(
    /** The camera file's name (the last part of its uri): names survive a reconnect, ids don't. */
    val file: String,
    val fromMs: Long,
    val toMs: Long,
    /** Put here by the auto montage: the UI marks it and «отменить авто» restores the previous list. */
    val auto: Boolean = false,
    /**
     * A cutaway: while the base's own time is here, the picture is this clip's and the base's sound goes on without a
     * break (see the plan «Основной файл и перебивки»). null = an ordinary clip, one after another.
     */
    val atMs: Long? = null,
    val role: Role = Role.TAKE,
    /** 0…1, how much motion the clip has (measured in the background from the LRV frames; null until then). */
    val energy: Double? = null,
) {
    /** A marked short piece (the camera's marks or the user's edit), a piece of a long take, or a still. */
    enum class Role { TAKE, INSERT, STILL }

    val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0L)

    fun toJson(): JSONObject = JSONObject().apply {
        put("file", file)
        put("from", fromMs)
        put("to", toMs)
        if (auto) put("auto", true)
        atMs?.let { put("at", it) }
        if (role != Role.TAKE) put("role", role.name)
        energy?.let { put("energy", it) }
    }

    companion object {
        /** null for a broken entry: one bad clip must not lose the whole project. */
        fun from(o: JSONObject): ProjectClip? {
            val file = o.optString("file").takeIf { it.isNotEmpty() } ?: return null
            val from = o.optLong("from", -1L)
            val to = o.optLong("to", -1L)
            if (from < 0L || to <= from) return null
            return ProjectClip(
                file = file,
                fromMs = from,
                toMs = to,
                auto = o.optBoolean("auto", false),
                atMs = if (o.has("at")) o.optLong("at") else null,
                role = runCatching { Role.valueOf(o.optString("role", Role.TAKE.name)) }.getOrDefault(Role.TAKE),
                energy = if (o.has("energy")) o.optDouble("energy").takeIf { !it.isNaN() } else null,
            )
        }
    }
}

/** The music of a project: the picked file and what the analysis found about it. */
data class ProjectMusic(
    val uri: String,
    val name: String = "",
    /** Where the montage starts in the track (trim), ms. */
    val offsetMs: Long = 0L,
    val volume: Double = 1.0,
    /** Keep the video's own sound under the music, lowering it (P2). */
    val duck: Boolean = true,
    /** The beat grid in ms (P3); empty until the track has been analysed. */
    val beats: List<Long> = emptyList(),
    /** The drop the analysis found, movable by hand (P3). */
    val dropMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        if (name.isNotEmpty()) put("name", name)
        if (offsetMs != 0L) put("offset", offsetMs)
        put("volume", volume)
        put("duck", duck)
        if (beats.isNotEmpty()) put("beats", JSONArray(beats))
        dropMs?.let { put("drop", it) }
    }

    companion object {
        fun from(o: JSONObject): ProjectMusic? {
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return null
            val beats = o.optJSONArray("beats")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()
            return ProjectMusic(
                uri = uri,
                name = o.optString("name"),
                offsetMs = o.optLong("offset", 0L),
                volume = o.optDouble("volume", 1.0).takeIf { !it.isNaN() } ?: 1.0,
                duck = o.optBoolean("duck", true),
                beats = beats,
                dropMs = if (o.has("drop")) o.optLong("drop") else null,
            )
        }
    }
}

/** A project: a montage of pieces of the camera's files. Media never moves — only these references do. */
data class Project(
    val id: String,
    val name: String,
    val createdMs: Long = System.currentTimeMillis(),
    val scenario: Scenario = Scenario.CUSTOM,
    val dynamics: Dynamics = Dynamics.EVEN,
    val clips: List<ProjectClip> = emptyList(),
    /**
     * The base file (see the plan «Основной файл и перебивки»): its picture and sound are the timeline itself, its
     * clips are never shifted, and the pieces of other files lie on it as cutaways ([ProjectClip.atMs]).
     * null = an ordinary montage, one clip after another.
     */
    val baseFile: String? = null,
    val music: ProjectMusic? = null,
    /** The clip list before the last auto montage («отменить авто» in the UI); null when there was none. */
    val previous: List<ProjectClip>? = null,
) {
    /**
     * The clips whose range their file no longer has at all (the file was re-edited on the phone since the clip was
     * put in): the project screen marks them «кусок убран из файла» and drops them on one tap — see the plan §7.
     * A file [pieces] says nothing about is left alone: not knowing is not a reason to call a clip gone.
     */
    /** The cutaways: clips pinned to a moment of the base's own time ([ProjectClip.atMs]). */
    val covers: List<ProjectClip> get() = clips.filter { it.atMs != null }

    /** The clips that run one after another: the timeline of a project without a base. */
    val sequence: List<ProjectClip> get() = clips.filter { it.atMs == null }

    /**
     * The project without that clip (the ✕ on its card). The base goes too when its last clip is gone: nothing would
     * hold the timeline then, and a base without clips is only a name.
     */
    fun without(clip: ProjectClip): Project {
        val left = clips.toMutableList().also { it.remove(clip) }
        return copy(clips = left, baseFile = baseFile?.takeIf { base -> left.any { it.file == base } })
    }

    /**
     * The sequence with the clip at [from] moved to [to] (dragging a card along the strip); cutaways keep their
     * moment, so they stay where they are in [clips].
     */
    fun movedSequence(from: Int, to: Int): Project {
        val seq = sequence.toMutableList()
        if (from !in seq.indices || to !in seq.indices || from == to) return this
        seq.add(to, seq.removeAt(from))
        var i = 0
        return copy(clips = clips.map { if (it.atMs == null) seq[i++] else it })
    }

    fun staleClips(pieces: Map<String, List<Highlight>>): List<ProjectClip> = clips.filter { clip ->
        pieces[clip.file]?.let { parts ->
            parts.none { part -> part.startMs <= clip.fromMs && clip.toMs <= (part.endMs ?: part.startMs) }
        } ?: false
    }

    companion object {
        /** The JSON format the app writes: a field added later is simply absent in older files. */
        const val VERSION = 1

        fun encode(p: Project): String = JSONObject().apply {
            put("version", VERSION)
            put("id", p.id)
            put("name", p.name)
            put("created", p.createdMs)
            if (p.scenario != Scenario.CUSTOM) put("scenario", p.scenario.name)
            if (p.dynamics != Dynamics.EVEN) put("dynamics", p.dynamics.name)
            p.baseFile?.let { put("base", it) }
            put("clips", JSONArray().apply { p.clips.forEach { put(it.toJson()) } })
            p.music?.let { put("music", it.toJson()) }
            p.previous?.let { prev -> put("previous", JSONArray().apply { prev.forEach { put(it.toJson()) } }) }
        }.toString()

        /** null when the text isn't a project (a broken file): the caller skips it rather than crashing. */
        fun decode(text: String): Project? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return null
            return Project(
                id = id,
                name = name,
                createdMs = o.optLong("created", 0L),
                scenario = enumOf(o.optString("scenario"), Scenario.CUSTOM),
                dynamics = enumOf(o.optString("dynamics"), Dynamics.EVEN),
                clips = clipsOf(o.optJSONArray("clips")),
                baseFile = o.optString("base").takeIf { it.isNotEmpty() },
                music = o.optJSONObject("music")?.let(ProjectMusic::from),
                previous = o.optJSONArray("previous")?.let(::clipsOf),
            )
        }

        private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback

        private fun clipsOf(a: JSONArray?): List<ProjectClip> =
            (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let(ProjectClip::from) }
    }
}