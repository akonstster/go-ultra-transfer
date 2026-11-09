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
    /** The beat grid in ms (P3); empty until the track has been analysed. */
    val beats: List<Long> = emptyList(),
    /**
     * Every place the analysis found the track's energy returning after a quiet passage, in order (P3). A project may
     * hold several: the timeline marks them all, and the montage accents the active one.
     */
    val drops: List<Long> = emptyList(),
    /** Which of [drops] the montage accents; null when the track has no drop. */
    val activeDropMs: Long? = null,
    /** The drops were put in by hand: a newer analysis may improve the grid, never these marks. */
    val dropsEdited: Boolean = false,
    /** The track's length, ms, as the analysis read it (0 until then): the wave and its grid are drawn against it. */
    val lengthMs: Long = 0L,
    /** Which detector made this grid: an older one is read again when the project is opened. */
    val gridVersion: Int = 0,
    /** Which detector made [drops]: kept apart from the grid, so a hand-edited grid still gets new drops. */
    val dropVersion: Int = 0,
    /** How sure that detector was (0…1): a low value is a grid to check, not a truth to trust. */
    val gridConfidence: Double = 0.0,
    /** The grid was changed by hand: a newer detector may not overwrite it. */
    val gridEdited: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        if (name.isNotEmpty()) put("name", name)
        if (offsetMs != 0L) put("offset", offsetMs)
        if (beats.isNotEmpty()) put("beats", JSONArray(beats))
        if (drops.isNotEmpty()) put("drops", JSONArray(drops))
        activeDropMs?.let { put("activeDrop", it) }
        if (dropsEdited) put("dropsEdited", true)
        if (lengthMs > 0L) put("length", lengthMs)
        if (gridVersion != 0) put("gridVersion", gridVersion)
        if (dropVersion != 0) put("dropVersion", dropVersion)
        if (gridConfidence > 0.0) put("gridConfidence", gridConfidence)
        if (gridEdited) put("gridEdited", true)
    }

    companion object {
        /** The video's own sound under freshly picked music: the music leads, the place's sound still heard. */
        const val UNDER_MUSIC = 0.35

        fun from(o: JSONObject): ProjectMusic? {
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return null
            val beats = o.optJSONArray("beats")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()
            // The old format held one drop in «drop»; it becomes a list of one, so a project written before the multiple
            // drops were found keeps its mark instead of losing it.
            val drops = o.optJSONArray("drops")?.let { a -> (0 until a.length()).map { a.optLong(it) } }
                ?.filter { it >= 0L }?.distinct()?.sorted()
                ?: listOfNotNull(if (o.has("drop")) o.optLong("drop").takeIf { it >= 0L } else null)
            val active = (if (o.has("activeDrop")) o.optLong("activeDrop") else null)
                ?.takeIf { it >= 0L }
            // An active mark is always one of the marks: an explicit one with no list is a list of one.
            val allDrops = if (active != null && active !in drops) (drops + active).sorted() else drops
            return ProjectMusic(
                uri = uri,
                name = o.optString("name"),
                offsetMs = o.optLong("offset", 0L),
                beats = beats,
                drops = allDrops,
                activeDropMs = active ?: allDrops.firstOrNull(),
                dropsEdited = o.optBoolean("dropsEdited", false),
                lengthMs = o.optLong("length", 0L),
                gridVersion = o.optInt("gridVersion", 0),
                dropVersion = o.optInt("dropVersion", 0),
                gridConfidence = o.optDouble("gridConfidence", 0.0).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0,
                gridEdited = o.optBoolean("gridEdited", false),
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
    /** Clips taken out by hand: an edit of their file must not bring them back (see [resynced]). */
    val hidden: List<ProjectClip> = emptyList(),
    /**
     * The mix of the project itself: kept whether or not a track is picked, so removing music is not resetting the mix.
     * 0 is a real level (music only, or the clips silent), so these are plain values, never null.
     */
    val musicVolume: Double = 1.0,
    /** How loud the video's own sound stays under the music, 0…1 (0 — music only, 1 — as recorded). */
    val videoVolume: Double = ProjectMusic.UNDER_MUSIC,
) {
    /** The project with [uri] as its track, at the mix the project already has; the wave and the grid come later. */
    fun withMusic(uri: String, name: String): Project =
        copy(music = ProjectMusic(uri = uri, name = name))

    /** The project without its track. The mix belongs to the project, so it stays exactly as it is. */
    fun withoutMusic(): Project = if (music == null) this else copy(music = null)
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
        // The removal is remembered: an edit of that file's pieces must not bring the clip back (see [resynced]).
        return copy(
            clips = left,
            hidden = (hidden + clip).distinct(),
            baseFile = baseFile?.takeIf { base -> left.any { it.file == base } },
        )
    }

    /**
     * The project with [next] as the result of an auto montage: the list before it is kept in [previous] for «отменить
     * авто». The mix, the track and the scenario stay exactly as they are; only the pieces and their undo snapshot move.
     */
    fun autoMontaged(next: List<ProjectClip>): Project {
        if (next.isEmpty() || next == clips) return this
        return copy(previous = clips, clips = next)
    }

    /** «Отменить авто»: the pieces as they were before the last auto montage; one undo, as the plan says. */
    fun undoAutoMontage(): Project = previous?.let { copy(clips = it, previous = null) } ?: this

    /**
     * A change made by hand after an auto montage invalidates its undo snapshot: restoring the snapshot later would
     * silently throw that hand work away. The «Авто» badges stay, so the origin of the pieces is still visible.
     */
    fun editedByHand(): Project = if (previous == null) this else copy(previous = null)

    /**
     * The project with the clips of the files edited on the phone brought in line with their pieces (the plan §7): the
     * pieces of an edited file are what the project takes of it, put where that file's clips sit in the order. Files
     * nobody edited stay as they are — the camera's marks are not ours to re-read, and an unreachable camera says
     * nothing. A file edited down to nothing leaves the project; a clip taken out by hand stays out ([hidden]); the
     * base goes with its last clip, as everywhere.
     */
    fun resynced(edited: Map<String, List<Highlight>>): Project {
        var next = this
        for ((name, pieces) in edited) {
            val mine = next.clips.filter { it.file == name }
            if (mine.isEmpty()) continue
            // An auto montage is an editorial snapshot, not a live view of the file: replacing its several musical
            // pieces with the file's few marked ranges would silently undo the whole cut. Source edits instead show as
            // stale ranges, and «Авто» can be run again when the user wants the new material.
            if (mine.any { it.auto }) continue
            val wanted = pieces
                .filterNot { piece -> next.hidden.any { it.file == name && it.fromMs == piece.startMs && it.toMs == (piece.endMs ?: piece.startMs) } }
                .map { ProjectClip(name, it.startMs, it.endMs ?: it.startMs, auto = mine.first().auto, role = mine.first().role) }
            if (mine == wanted) continue
            val at = next.clips.indexOfFirst { it.file == name }
            val left = next.clips.filter { it.file != name }.toMutableList()
            left.addAll(at, wanted)
            next = next.copy(clips = left, baseFile = next.baseFile?.takeIf { base -> left.any { it.file == base } })
        }
        return next
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

    /**
     * The clips whose range their file no longer has at all (the file was re-edited on the phone since the clip was
     * put in): the project screen marks them «кусок убран из файла» and drops them on one tap — see the plan §7.
     * A file [pieces] says nothing about is left alone: not knowing is not a reason to call a clip gone.
     */
    fun staleClips(pieces: Map<String, List<Highlight>>): List<ProjectClip> = clips.filter { clip ->
        // A photo is a still, not a range of a file's pieces: it can never be «gone from the file».
        if (clip.role == ProjectClip.Role.STILL) return@filter false
        pieces[clip.file]?.let { parts ->
            parts.none { part -> part.startMs <= clip.fromMs && clip.toMs <= (part.endMs ?: part.startMs) }
        } ?: false
    }

    companion object {
        /** The JSON format the app writes: a field added later is simply absent in older files. */
        const val VERSION = 2

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
            // The mix is the project's own, so it is written whether a track is picked or not.
            put("musicLevel", p.musicVolume)
            put("videoLevel", p.videoVolume)
            p.previous?.let { prev -> put("previous", JSONArray().apply { prev.forEach { put(it.toJson()) } }) }
            if (p.hidden.isNotEmpty()) put("hidden", JSONArray().apply { p.hidden.forEach { put(it.toJson()) } })
        }.toString()

        /** null when the text isn't a project (a broken file): the caller skips it rather than crashing. */
        fun decode(text: String): Project? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val music = o.optJSONObject("music")?.let(ProjectMusic::from)
            val musicJson = o.optJSONObject("music")
            // In format 1 the mix was split: the sliders lived on the track, while «musicLevel»/«videoLevel» were a copy
            // refreshed only when a track was removed or picked. That copy is stale exactly when the sliders were moved, so
            // an older file takes the track's own levels first. Format 2 (this build) keeps the mix on the project, and
            // those keys are the truth.
            val oldMix = o.optInt("version", 1) < 2
            return Project(
                id = id,
                name = name,
                createdMs = o.optLong("created", 0L),
                scenario = enumOf(o.optString("scenario"), Scenario.CUSTOM),
                dynamics = enumOf(o.optString("dynamics"), Dynamics.EVEN),
                clips = clipsOf(o.optJSONArray("clips")),
                baseFile = o.optString("base").takeIf { it.isNotEmpty() },
                music = music,
                previous = o.optJSONArray("previous")?.let(::clipsOf),
                hidden = clipsOf(o.optJSONArray("hidden")),
                // Before the mix lived on the project it lived on the track (volume/video, and even earlier «duck»).
                musicVolume = if (oldMix && musicJson != null) {
                    levelOf(musicJson, "volume") ?: levelOf(o, "musicLevel") ?: 1.0
                } else {
                    levelOf(o, "musicLevel") ?: levelOf(musicJson, "volume") ?: 1.0
                },
                videoVolume = if (oldMix && musicJson != null) {
                    legacyVideoLevel(musicJson) ?: levelOf(o, "videoLevel") ?: ProjectMusic.UNDER_MUSIC
                } else {
                    levelOf(o, "videoLevel") ?: legacyVideoLevel(musicJson) ?: ProjectMusic.UNDER_MUSIC
                },
            )
        }

        /** A 0…1 level under [key], or null when it is absent or broken (0 is a real level, so it must survive). */
        private fun levelOf(o: JSONObject?, key: String): Double? {
            if (o == null || !o.has(key)) return null
            return o.optDouble(key).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)
        }

        /** The old track's own «video» level, or its even older «duck» flag; null when neither is there. */
        private fun legacyVideoLevel(music: JSONObject?): Double? = when {
            music == null -> null
            music.has("video") -> levelOf(music, "video")
            music.has("duck") -> if (music.optBoolean("duck", true)) ProjectMusic.UNDER_MUSIC else 1.0
            else -> null
        }

        private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback

        private fun clipsOf(a: JSONArray?): List<ProjectClip> =
            (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let(ProjectClip::from) }
    }
}