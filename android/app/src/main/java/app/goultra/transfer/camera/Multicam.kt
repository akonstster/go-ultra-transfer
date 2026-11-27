package app.goultra.transfer.camera

import org.json.JSONArray
import org.json.JSONObject

data class MulticamSpan(val fromMs: Long, val toMs: Long, val offsetMs: Long, val alternatives: List<Long> = emptyList()) {
    val timelineStart: Long get() = fromMs + offsetMs
    val timelineEnd: Long get() = toMs + offsetMs

    fun overlaps(other: MulticamSpan): Boolean =
        timelineStart < other.timelineEnd && other.timelineStart < timelineEnd
}

data class MulticamTake(
    val file: String,
    val camera: Int,
    val lengthMs: Long,
    val startMs: Long? = null,
    val needsReview: Boolean = false,
    val sourceFromMs: Long = 0L,
    val proposedSpans: List<MulticamSpan> = emptyList(),
    val driftMs: Long? = null,
    val replacesPlacement: Boolean = false,
    val cutaway: Boolean = false,
) {
    val id: String get() = when {
        replacesPlacement -> "review:$file"
        sourceFromMs == 0L -> file
        else -> "$file@$sourceFromMs"
    }
    val endMs: Long? get() = startMs?.plus(lengthMs)
    val previewStartMs: Long get() = startMs ?: 0L
    fun available(at: Long): Boolean = !cutaway && !needsReview && startMs != null && at >= startMs && at - startMs < lengthMs

    fun confirmedSpans(): List<MulticamSpan> = proposedSpans.filter { span ->
        span.alternatives.isEmpty() && proposedSpans.none { it != span && it.alternatives.isEmpty() && it.overlaps(span) }
    }
}

data class MulticamCutaway(val file: String, val atMs: Long, val fromMs: Long, val lengthMs: Long, val manual: Boolean = false) {
    val endMs: Long get() = atMs + lengthMs
}

/** Switches use reference time. Each take maps its own source range onto that timeline. */
data class Multicam(
    val referenceFile: String,
    val referenceLengthMs: Long,
    val takes: List<MulticamTake>,
    val switches: Map<Long, Int> = emptyMap(),
    val manualCuts: Set<Long> = emptySet(),
    val cutaways: List<MulticamCutaway> = emptyList(),
) {
    val cameras: List<Int> get() = takes.map { it.camera }.distinct().sorted()
    val durationMs: Long get() = maxOf(referenceLengthMs, takes.filterNot { it.needsReview }.mapNotNull { it.endMs }.maxOrNull() ?: 0)
    val layoutMs: Long get() = maxOf(durationMs, takes.maxOfOrNull { it.previewStartMs + it.lengthMs } ?: 0L)

    fun audition(file: String, fromMs: Long = 0L, untilMs: Long = layoutMs): List<ProjectClip> {
        val selected = takes.firstOrNull { it.id == file } ?: return clips()
        val sameCamera = takes.filter { it.camera == selected.camera && it.id != selected.id && it.startMs != null && !it.needsReview }
        val tentative = selected.previewStartMs
        val start = if ((selected.needsReview || selected.startMs == null) &&
            (tentative >= untilMs || tentative + selected.lengthMs <= fromMs)) fromMs else tentative
        val audible = selected.copy(startMs = start, needsReview = false)
        return copy(referenceFile = selected.file, referenceLengthMs = layoutMs,
            takes = listOf(audible) + sameCamera, switches = emptyMap(), cutaways = emptyList())
            .clips(auditionFile = selected.id, fromMs = fromMs, untilMs = untilMs)
    }

    fun acceptPartialAlignment(id: String, selected: List<MulticamSpan>? = null): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.needsReview } ?: return null
        val spans = (selected ?: take.confirmedSpans()).sortedBy { it.fromMs }
        if (spans.isEmpty() || spans.any { it.fromMs < 0 || it.toMs > take.lengthMs || it.toMs - it.fromMs < 100L } ||
            spans.zipWithNext().any { (a, b) -> a.toMs > b.fromMs } ||
            spans.sortedBy { it.timelineStart }.zipWithNext().any { (a, b) -> a.overlaps(b) }) return null
        val parts = spans.map { span ->
            take.copy(sourceFromMs = take.sourceFromMs + span.fromMs, lengthMs = span.toMs - span.fromMs,
                startMs = span.timelineStart, needsReview = false, proposedSpans = emptyList(), driftMs = null,
                replacesPlacement = false)
        }
        return replaceReview(take, parts)
    }

    fun keepWholeTake(id: String): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.needsReview && it.startMs != null } ?: return null
        return replaceReview(take, listOf(take.copy(needsReview = false, proposedSpans = emptyList(),
            driftMs = null, replacesPlacement = false)))
    }

    fun keepCutaway(id: String): Multicam? {
        val take = takes.firstOrNull { it.id == id && !it.cutaway && it.lengthMs >= 500L && it.file != referenceFile }
            ?: return null
        return replaceReview(take, listOf(take.copy(startMs = null, needsReview = false, proposedSpans = emptyList(),
            driftMs = null, replacesPlacement = false, cutaway = true)))
    }

    fun reopenCutaway(id: String): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.cutaway } ?: return null
        return copy(takes = takes.map {
            if (it.id == id) take.copy(startMs = null, needsReview = true, cutaway = false) else it
        }, cutaways = cutaways.filterNot { it.file == take.file })
    }

    fun placeCutaway(id: String, atMs: Long, untilMs: Long): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.cutaway } ?: return null
        if (cutaways.any { atMs in it.atMs until it.endMs }) return null
        val end = minOf(untilMs, cutaways.filter { it.atMs > atMs }.minOfOrNull { it.atMs } ?: untilMs)
        val length = minOf(3_000L, take.lengthMs, end - atMs)
        if (length < 500L) return null
        return copy(cutaways = (cutaways + MulticamCutaway(take.file, atMs, take.sourceFromMs, length, manual = true))
            .sortedBy { it.atMs })
    }

    fun moveCutaway(cut: MulticamCutaway, atMs: Long, fromMs: Long, untilMs: Long): Multicam? {
        if (cut !in cutaways || untilMs - fromMs < cut.lengthMs) return null
        val moved = cut.copy(atMs = atMs.coerceIn(fromMs, untilMs - cut.lengthMs), manual = true)
        if (cutaways.any { it != cut && it.atMs < moved.endMs && it.endMs > moved.atMs }) return null
        return copy(cutaways = cutaways.map { if (it == cut) moved else it }.sortedBy { it.atMs })
    }

    fun withNewAlignment(reference: String, lengthMs: Long, aligned: List<MulticamTake>,
                         cameras: Map<String, Int>, reset: Boolean): Multicam {
        if (reset || referenceFile != reference) return Multicam(reference, lengthMs, aligned)
        val retainedAngles = takes.filter { cameras[it.file] == it.camera }.map { it.camera }.toSet()
        val retainedSwitches = switches.filterValues { it in retainedAngles }
        val placements = aligned.flatMap { take ->
            val prior = takes.filter {
                it.file == take.file && it.camera == take.camera && !it.needsReview && it.startMs != null
            }
            if (retainedSwitches.isEmpty() || prior.isEmpty()) return@flatMap listOf(take)
            val former = prior.singleOrNull()
            if (former != null && !take.needsReview && take.startMs != null && former.sourceFromMs == 0L &&
                former.lengthMs == take.lengthMs && kotlin.math.abs(checkNotNull(former.startMs) - take.startMs) <= 40L) {
                return@flatMap prior
            }
            val proposal = if (!take.needsReview && take.startMs != null) {
                listOf(MulticamSpan(0L, take.lengthMs, take.startMs))
            } else take.proposedSpans
            prior + take.copy(needsReview = true, proposedSpans = proposal, replacesPlacement = true)
        }
        val inserts = cutaways.filter { item -> placements.any { it.cutaway && it.file == item.file } }
        return Multicam(reference, lengthMs, placements, retainedSwitches, manualCuts.intersect(retainedSwitches.keys), inserts)
    }

    private fun replaceReview(take: MulticamTake, parts: List<MulticamTake>): Multicam =
        copy(takes = takes.flatMap {
            when {
                it.id == take.id -> parts
                take.replacesPlacement && it.file == take.file -> emptyList()
                else -> listOf(it)
            }
        })

    fun alignWholeTake(id: String, startMs: Long): Multicam? {
        val take = takes.firstOrNull { it.id == id && !it.cutaway } ?: return null
        return replaceReview(take, listOf(take.copy(startMs = startMs, needsReview = false,
            proposedSpans = emptyList(), driftMs = null, replacesPlacement = false)))
    }

    fun lostCoverage(next: Multicam, from: Long, until: Long): List<Shot> =
        shots(from, until).filter { shot ->
            val camera = shot.camera ?: return@filter false
            if (cutaways.any { it.file == shot.file && it.atMs <= shot.start && it.endMs >= shot.end } &&
                next.cutaways.any { it.file == shot.file && it.atMs <= shot.start && it.endMs >= shot.end }) return@filter false
            var end = shot.start
            for (take in next.takes.filter { it.camera == camera && !it.needsReview && it.startMs != null }
                .sortedBy { it.startMs }) {
                if (checkNotNull(take.startMs) <= end) end = maxOf(end, checkNotNull(take.endMs))
            }
            end < shot.end
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("reference", referenceFile)
        put("length", referenceLengthMs)
        put("takes", JSONArray().apply {
            takes.forEach { take ->
                put(JSONObject().apply {
                    put("file", take.file)
                    put("camera", take.camera)
                    put("length", take.lengthMs)
                    take.startMs?.let { put("start", it) }
                    if (take.needsReview) put("review", true)
                    if (take.sourceFromMs != 0L) put("sourceFrom", take.sourceFromMs)
                    take.driftMs?.let { put("drift", it) }
                    if (take.replacesPlacement) put("replace", true)
                    if (take.cutaway) put("cutaway", true)
                    if (take.proposedSpans.isNotEmpty()) put("partial", JSONArray().apply {
                        take.proposedSpans.forEach { span ->
                            put(JSONObject().put("from", span.fromMs).put("to", span.toMs).put("offset", span.offsetMs).apply {
                                if (span.alternatives.isNotEmpty()) put("alternatives", JSONArray(span.alternatives))
                            })
                        }
                    })
                })
            }
        })
        put("switches", JSONArray().apply {
            switches.toSortedMap().forEach { (at, camera) ->
                put(JSONObject().put("at", at).put("camera", camera).put("manual", at in manualCuts))
            }
        })
        if (cutaways.isNotEmpty()) put("cutaways", JSONArray().apply {
            cutaways.forEach { cut ->
                put(JSONObject().put("file", cut.file).put("at", cut.atMs).put("from", cut.fromMs).put("length", cut.lengthMs).apply {
                    if (cut.manual) put("manual", true)
                })
            }
        })
    }

    fun clips(automatic: Boolean = false, auditionFile: String? = null, fromMs: Long = 0L,
              untilMs: Long = durationMs): List<ProjectClip> {
        val usable = takes.filter { !it.cutaway && !it.needsReview && it.startMs != null && it.lengthMs > 0 }
        val inserts = cutaways.filter { it.atMs < untilMs && it.endMs > fromMs }
        val boundaries = (listOf(fromMs, untilMs) + switches.keys +
            usable.flatMap { listOfNotNull(it.startMs, it.endMs) } + inserts.flatMap { listOf(it.atMs, it.endMs) })
            .filter { it in fromMs..untilMs }.distinct().sorted()
        val changes = switches.toSortedMap()
        var requested = changes.entries.lastOrNull { it.key <= fromMs }?.value
        var current: MulticamTake? = null
        val out = mutableListOf<ProjectClip>()
        for ((start, stop) in boundaries.zipWithNext()) {
            changes[start]?.let { requested = it }
            val available = usable.filter { it.available(start) }
            current = available.firstOrNull { it.id == auditionFile }
                ?: available.firstOrNull { it.camera == requested && it == current }
                ?: available.filter { it.camera == requested }.maxByOrNull { it.endMs ?: 0L }
                ?: current?.takeIf { it in available }
                ?: available.firstOrNull { it.file == referenceFile }
                ?: available.minByOrNull { it.camera }
            val source = current
            val from = source?.startMs?.let { source.sourceFromMs + start - it } ?: 0L
            val insert = inserts.firstOrNull { start >= it.atMs && start < it.endMs }
            val clip = when {
                insert != null -> {
                    val input = insert.fromMs + start - insert.atMs
                    ProjectClip(insert.file, input, input + stop - start, auto = true, muteSource = true)
                }
                source == null -> ProjectClip(GAP_FILE, 0L, stop - start, auto = automatic, role = ProjectClip.Role.STILL)
                else -> ProjectClip(source.file, from, from + stop - start, auto = automatic)
            }
            val last = out.lastOrNull()
            if (last != null && last.file == clip.file && last.muteSource == clip.muteSource &&
                (last.toMs == clip.fromMs || clip.file == GAP_FILE)) {
                out[out.lastIndex] = last.copy(toMs = last.toMs + clip.durationMs)
            } else {
                out += clip
            }
        }
        return out
    }

    data class Shot(val start: Long, val end: Long, val file: String, val camera: Int?)

    fun shots(from: Long, until: Long): List<Shot> {
        var start = from
        return clips(fromMs = from, untilMs = until).map { clip ->
            Shot(start, start + clip.durationMs, clip.file, takes.firstOrNull { it.file == clip.file }?.camera)
                .also { start = it.end }
        }
    }

    private fun MulticamTake.covers(from: Long, until: Long): Boolean =
        until > from && available(from) && (endMs ?: 0L) >= until

    fun anglesFor(from: Long, until: Long): List<Int> =
        takes.filter { it.covers(from, until) }.map { it.camera }.distinct().sorted()

    fun replaceShot(from: Long, until: Long, camera: Int): Multicam? {
        if (camera !in anglesFor(from, until)) return null
        return rewrite(from, until, mapOf(from to camera))
    }

    fun roll(left: Shot, right: Shot, at: Long): Multicam? {
        val a = left.camera ?: return null
        val b = right.camera ?: return null
        if (a == b || left.end != right.start || at - left.start < 100L || right.end - at < 100L) return null
        if (takes.none { it.file == left.file && it.covers(left.start, at) } ||
            takes.none { it.file == right.file && it.covers(at, right.end) }) return null
        return rewrite(left.start, right.end, mapOf(left.start to a, at to b))
    }

    private fun rewrite(from: Long, until: Long, edits: Map<Long, Int>): Multicam {
        val restore = shots(0L, durationMs).firstOrNull { until in it.start until it.end }?.camera
            ?: switches.filterKeys { it <= until }.maxByOrNull { it.key }?.value
        val remaining = switches.filterKeys { it !in from until until }
        val boundary = if (until !in remaining && restore != null && until < durationMs) mapOf(until to restore)
            else emptyMap()
        return copy(switches = remaining + edits + boundary,
            manualCuts = manualCuts.filterNot { it in from until until }.toSet() + edits.keys + boundary.keys,
            cutaways = cutaways.flatMap { cut ->
                if (cut.atMs >= until || cut.endMs <= from) listOf(cut)
                else listOfNotNull(
                    cut.copy(lengthMs = from - cut.atMs).takeIf { it.lengthMs > 0 },
                    cut.copy(atMs = until, fromMs = cut.fromMs + until - cut.atMs, lengthMs = cut.endMs - until)
                        .takeIf { it.lengthMs > 0 },
                )
            })
    }

    fun autoSwitch(slots: List<Montage.Slot>): Multicam {
        val existingShots = shots(0L, durationMs)
        val manualRanges = manualCuts.mapNotNull { at ->
            existingShots.firstOrNull { at in it.start until it.end }?.let { at until it.end }
        }
        var previous: Int? = null
        val pool = takes.filter { it.cutaway && it.lengthMs >= 500L }
        val manualInserts = cutaways.filter { it.manual }
        val used = pool.associate { take ->
            take.id to (manualInserts.filter { it.file == take.file }.maxOfOrNull {
                (it.fromMs + it.lengthMs - take.sourceFromMs).coerceAtLeast(0L)
            } ?: 0L)
        }.toMutableMap()
        val inserts = manualInserts.toMutableList()
        fun protected(slot: Montage.Slot): Boolean =
            manualRanges.any { it.first < slot.endMs && slot.startMs <= it.last } ||
                manualInserts.any { it.atMs < slot.endMs && it.endMs > slot.startMs }
        var nextCutaway = 0
        fun insert(from: Long, until: Long) {
            if (until - from < 500L) return
            val index = pool.indices.asSequence().map { (it + nextCutaway) % pool.size }
                .firstOrNull { pool[it].lengthMs - used.getOrDefault(pool[it].id, 0L) >= 500L } ?: return
            val item = pool[index]
            val start = used.getOrDefault(item.id, 0L)
            val length = minOf(item.lengthMs - start, until - from)
            inserts += MulticamCutaway(item.file, from, item.sourceFromMs + start, length)
            used[item.id] = start + length
            nextCutaway = (index + 1) % pool.size
            if (from + length < until) insert(from + length, until)
        }
        if (pool.isNotEmpty()) {
            val aligned = takes.filter { !it.cutaway && !it.needsReview && it.startMs != null }.sortedBy { it.startMs }
            val accents = mutableListOf<Montage.Slot>()
            for (slot in slots) {
                if (protected(slot)) continue
                var edge = slot.startMs
                var hasGap = false
                for (take in aligned) {
                    val start = checkNotNull(take.startMs)
                    val end = checkNotNull(take.endMs)
                    if (start >= slot.endMs) break
                    if (end <= edge) continue
                    if (start > edge) {
                        insert(edge, start)
                        hasGap = true
                    }
                    edge = maxOf(edge, end)
                }
                if (edge < slot.endMs) insert(edge, slot.endMs)
                else if (!hasGap && slot.durationMs >= 500L) accents += slot
            }
            val first = if (accents.size >= 3) 2 else 0
            for (i in first until accents.size step 3) insert(accents[i].startMs, accents[i].endMs)
            inserts.sortBy { it.atMs }
        }
        val choices = slots.mapNotNull { slot ->
            if (manualRanges.any { slot.startMs in it }) return@mapNotNull null
            val active = takes.filter { it.available(slot.startMs) }
            val full = active.filter { (it.endMs ?: 0L) >= slot.endMs }.ifEmpty { active }
            val next = full.firstOrNull { it.camera != previous && it.file == referenceFile }
                ?: full.filter { it.camera != previous }.maxByOrNull { it.endMs ?: 0L }
                ?: full.firstOrNull()
            next?.let {
                previous = it.camera
                slot.startMs to it.camera
            }
        }
        return copy(switches = choices.toMap() + switches.filterKeys { it in manualCuts }, cutaways = inserts)
    }

    companion object {
        const val GAP_FILE = "__multicam_gap.jpg"
        const val MASTER_ID = "multicam-master"
        const val GROUP_FILE = "__multicam_group"

        fun from(o: JSONObject): Multicam? {
            val reference = o.optString("reference").takeIf { it.isNotBlank() } ?: return null
            val length = o.optLong("length").takeIf { it > 0L } ?: return null
            val takes = o.optJSONArray("takes") ?: return null
            val clips = (0 until takes.length()).mapNotNull { i ->
                val obj = takes.optJSONObject(i) ?: return@mapNotNull null
                val file = obj.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val camera = obj.optInt("camera").takeIf { it > 0 } ?: return@mapNotNull null
                val lengthMs = obj.optLong("length").coerceAtLeast(0L)
                val partial = obj.optJSONArray("partial")
                val spans = (0 until (partial?.length() ?: 0)).mapNotNull spanAt@{ index ->
                    val span = partial?.optJSONObject(index) ?: return@spanAt null
                    val from = span.optLong("from", -1)
                    val to = span.optLong("to", -1)
                    val alternatives = span.optJSONArray("alternatives")
                    if (from < 0 || to <= from || to > lengthMs) null else MulticamSpan(from, to, span.optLong("offset"),
                        (0 until (alternatives?.length() ?: 0)).map { alternatives!!.getLong(it) })
                }
                MulticamTake(file, camera, lengthMs, obj.optLong("start").takeIf { obj.has("start") }, obj.optBoolean("review"),
                    obj.optLong("sourceFrom"), spans, obj.optLong("drift").takeIf { obj.has("drift") }, obj.optBoolean("replace"),
                    obj.optBoolean("cutaway"))
            }
            val changes = o.optJSONArray("switches")
            val manual = mutableSetOf<Long>()
            val switches = (0 until (changes?.length() ?: 0)).mapNotNull { i ->
                val obj = changes?.optJSONObject(i) ?: return@mapNotNull null
                val at = obj.optLong("at", -1L)
                val camera = obj.optInt("camera")
                if (at >= 0 && camera > 0) {
                    if (obj.optBoolean("manual")) manual += at
                    at to camera
                } else null
            }.toMap()
            val inserts = o.optJSONArray("cutaways")
            val cutaways = (0 until (inserts?.length() ?: 0)).mapNotNull { i ->
                val item = inserts?.optJSONObject(i) ?: return@mapNotNull null
                val file = item.optString("file")
                val at = item.optLong("at", -1L)
                val from = item.optLong("from", -1L)
                val duration = item.optLong("length")
                if (at < 0 || from < 0 || duration <= 0 ||
                    clips.none { it.cutaway && it.file == file && from >= it.sourceFromMs &&
                        from - it.sourceFromMs <= it.lengthMs - duration }) null
                else MulticamCutaway(file, at, from, duration, item.optBoolean("manual"))
            }
            return Multicam(reference, length, clips, switches, manual, cutaways)
        }
    }
}
