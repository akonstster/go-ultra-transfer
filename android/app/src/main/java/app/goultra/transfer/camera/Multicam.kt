package app.goultra.transfer.camera

import org.json.JSONArray
import org.json.JSONObject

data class MulticamSpan(val fromMs: Long, val toMs: Long, val offsetMs: Long)

data class MulticamTake(
    val file: String,
    val camera: Int,
    val lengthMs: Long,
    val startMs: Long? = null,
    val needsReview: Boolean = false,
    val sourceFromMs: Long = 0L,
    val proposedSpans: List<MulticamSpan> = emptyList(),
) {
    val id: String get() = if (sourceFromMs == 0L) file else "$file@$sourceFromMs"
    val endMs: Long? get() = startMs?.plus(lengthMs)
    val previewStartMs: Long get() = startMs ?: 0L
    fun available(at: Long): Boolean = !needsReview && startMs != null && at >= startMs && at - startMs < lengthMs
}

/** Switches use reference time. Each take maps its own source range onto that timeline. */
data class Multicam(
    val referenceFile: String,
    val referenceLengthMs: Long,
    val takes: List<MulticamTake>,
    val switches: Map<Long, Int> = emptyMap(),
    val manualCuts: Set<Long> = emptySet(),
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
            takes = listOf(audible) + sameCamera, switches = emptyMap())
            .clips(auditionFile = selected.id, fromMs = fromMs, untilMs = untilMs)
    }

    fun acceptPartialAlignment(id: String): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.proposedSpans.size >= 2 } ?: return null
        val parts = take.proposedSpans.map { span ->
            take.copy(sourceFromMs = span.fromMs, lengthMs = span.toMs - span.fromMs, startMs = span.fromMs + span.offsetMs,
                needsReview = false, proposedSpans = emptyList())
        }
        return copy(takes = takes.flatMap { if (it.id == id) parts else listOf(it) })
    }

    fun keepWholeTake(id: String): Multicam? {
        val take = takes.firstOrNull { it.id == id && it.needsReview && it.startMs != null } ?: return null
        return copy(takes = takes.map {
            if (it.id == id) take.copy(needsReview = false, proposedSpans = emptyList()) else it
        })
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
                    if (take.proposedSpans.isNotEmpty()) put("partial", JSONArray().apply {
                        take.proposedSpans.forEach { span ->
                            put(JSONObject().put("from", span.fromMs).put("to", span.toMs).put("offset", span.offsetMs))
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
    }

    fun clips(automatic: Boolean = false, auditionFile: String? = null, fromMs: Long = 0L,
              untilMs: Long = durationMs): List<ProjectClip> {
        val usable = takes.filter { !it.needsReview && it.startMs != null && it.lengthMs > 0 }
        val boundaries = (listOf(fromMs, untilMs) + switches.keys +
            usable.flatMap { listOfNotNull(it.startMs, it.endMs) })
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
            val clip = if (source == null) {
                ProjectClip(GAP_FILE, 0L, stop - start, auto = automatic, role = ProjectClip.Role.STILL)
            } else ProjectClip(source.file, from, from + stop - start, auto = automatic)
            val last = out.lastOrNull()
            if (last != null && last.file == clip.file && (last.toMs == clip.fromMs || clip.file == GAP_FILE)) {
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
            manualCuts = manualCuts.filterNot { it in from until until }.toSet() + edits.keys + boundary.keys)
    }

    fun autoSwitch(slots: List<Montage.Slot>): Multicam {
        val existingShots = shots(0L, durationMs)
        val manualRanges = manualCuts.mapNotNull { at ->
            existingShots.firstOrNull { at in it.start until it.end }?.let { at until it.end }
        }
        var previous: Int? = null
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
        return copy(switches = choices.toMap() + switches.filterKeys { it in manualCuts })
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
                    if (from < 0 || to <= from || to > lengthMs) null else MulticamSpan(from, to, span.optLong("offset"))
                }
                MulticamTake(file, camera, lengthMs, obj.optLong("start").takeIf { obj.has("start") }, obj.optBoolean("review"),
                    obj.optLong("sourceFrom"), spans)
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
            return Multicam(reference, length, clips, switches, manual)
        }
    }
}
