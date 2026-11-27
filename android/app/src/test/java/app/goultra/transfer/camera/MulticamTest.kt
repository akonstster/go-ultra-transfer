package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticamTest {
    @Test
    fun `stopped cameras leave real gaps and a switch uses the aligned source time`() {
        val group = Multicam("voice.wav", 12_000, listOf(
            MulticamTake("main.mp4", 1, 5_000, -1_000),
            MulticamTake("phone-a.mp4", 2, 3_000, 2_000),
            MulticamTake("phone-b.mp4", 2, 3_000, 8_000),
        ), switches = mapOf(3_000L to 2), manualCuts = setOf(3_000L))
        val clips = group.clips()
        assertEquals(listOf("main.mp4", "phone-a.mp4", Multicam.GAP_FILE, "phone-b.mp4", Multicam.GAP_FILE), clips.map { it.file })
        assertEquals(listOf(3_000L, 2_000L, 3_000L, 3_000L, 1_000L), clips.map { it.durationMs })
        assertEquals(1_000L, clips[0].fromMs)
        assertEquals(1_000L, clips[1].fromMs)
        val project = Project("id", "Concert", clips = clips, multicam = group)
        assertEquals(project, Project.decode(Project.encode(project)))
    }

    @Test
    fun `auto switches only among usable angles and preserves manual cuts`() {
        val group = Multicam("a", 10_000, listOf(
            MulticamTake("a", 1, 10_000, 0),
            MulticamTake("b", 2, 4_000, 2_000),
            MulticamTake("c", 3, 10_000, 0, needsReview = true),
        ), switches = mapOf(5500L to 2), manualCuts = setOf(5500L))
        val result = group.autoSwitch(listOf(Montage.Slot(0, 2000, .5f), Montage.Slot(2000, 5000, .5f),
            Montage.Slot(5000, 8000, .5f)))
        assertEquals(mapOf(0L to 1, 2000L to 2, 5000L to 1, 5500L to 2), result.switches)
        assertFalse(result.clips().any { it.file == "c" })
    }

    @Test
    fun `a late camera synchronises through a clip that overlaps the reference and the finale`() {
        // Music-like events with independent note sets every 320 ms and several bands per event.
        val rate = 8_000
        val random = Random(3)
        val events = List(90) { IntArray(3) { band -> listOf(207, 654, 1471)[band] + random.nextInt(140 + 250 * band) } }
        val signal = ShortArray(rate * 28) { frame ->
            val block = frame / 2560
            val t = (frame % 2560).toDouble() / rate
            (events[block].sumOf { sin(2 * PI * t * it) } * 5_000 * (1 - t)).toInt().toShort()
        }
        fun fingerprint(from: Int, to: Int, quieter: Boolean = false): List<MulticamSync.Landmark> {
            val samples = signal.copyOfRange(from * 8, to * 8)
            if (quieter) for (i in samples.indices) samples[i] = (samples[i] / 3).toShort()
            return MulticamSync.read(object : ClipAudio.PcmSource {
                override val rate = 8_000
                override val channels = 1
                override val durationMs = samples.size / 8L
                override val delayFrames = 0
                override fun read(from: Long, count: Int) = samples.copyOfRange(from.toInt(), from.toInt() + count)
                override fun release() {}
            }) {}
        }
        val master = fingerprint(0, 16_000)
        val bridge = fingerprint(6_400, 23_040, quieter = true)
        val finale = fingerprint(19_200, 27_520)
        assertNull(MulticamSync.Index(master).match(finale))
        val aligned = MulticamSync.align(master, listOf(
            MulticamTake("bridge", 2, 16_640),
            MulticamTake("finale", 3, 8_320),
            MulticamTake("no-sound", 4, 2_000),
        ), mapOf("bridge" to bridge, "finale" to finale))
        assertTrue(aligned.toString(), abs((aligned[0].startMs ?: 0) - 6400) <= 32)
        assertTrue(aligned.toString(), abs((aligned[1].startMs ?: 0) - 19200) <= 48)
        assertFalse(aligned[1].needsReview)
        assertNull(aligned[2].startMs)
    }

    @Test
    fun `measurable clock drift sends an otherwise matched file to review`() {
        val candidate = (0 until 100).map { MulticamSync.Landmark(it, it * 20) }
        val reference = candidate.mapIndexed { i, mark -> mark.copy(frame = mark.frame + 700 + i / 18) }
        val result = MulticamSync.align(reference, listOf(MulticamTake("camera", 1, 32_000)),
            mapOf("camera" to candidate)).single()
        assertTrue(result.toString(), result.needsReview)
        assertTrue(result.toString(), abs((result.startMs ?: 0L) - 11_200) <= 48)
        assertFalse(result.available(20_000))
    }

    @Test
    fun `band trace finds an attenuated reverberant recording without matching hashes`() {
        val random = Random(81)
        val reference = List(850) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        val start = 234
        val take = List(270) { i ->
            FloatArray(5) { band ->
                (reference[start + i][band] * .78f + reference[start + i - 1][band] * .22f) * (0.25f + band * .22f)
            }
        }
        val index = MulticamSync.Recording(emptyList(), reference, FloatArray(0)).index()
        val matched = index.match(MulticamSync.Recording(emptyList(), take, FloatArray(0)))
        assertEquals(start * 32L, matched?.offsetMs)
        val repeated = reference + reference
        assertNull(MulticamSync.Recording(emptyList(), repeated, FloatArray(0)).index()
            .match(MulticamSync.Recording(emptyList(), take, FloatArray(0))))
    }

    @Test
    fun `audition reads the selected unresolved take without changing the edit`() {
        val group = Multicam("master.wav", 9_000, listOf(
            MulticamTake("a", 1, 9_000, 0),
            MulticamTake("b", 2, 9_000, 0),
            MulticamTake("unplaced", 2, 3_000, null),
        ), mapOf(0L to 1))
        assertEquals(listOf("unplaced", "b"), group.audition("unplaced").map { it.file })
        assertEquals(listOf("a"), group.clips().map { it.file })
    }

    @Test
    fun `harmonic sequences still align when attacks are unrelated`() {
        val random = Random(27)
        val chords = List(120) { IntArray(3) { random.nextInt(12) } }
        val reference = List(960) { i -> FloatArray(12) { if (it in chords[i / 8]) 0.6f else -0.15f } }
        val start = 262
        val take = List(420) { i -> FloatArray(12) { bin ->
            reference[start + i][bin] * (0.65f + 0.04f * bin) + random.nextFloat() * .06f
        } }
        val master = MulticamSync.Recording(emptyList(), emptyList(), FloatArray(0), reference)
        val clip = MulticamSync.Recording(emptyList(), emptyList(), FloatArray(0), take)
        assertEquals(start * 32L, master.index().match(clip)?.offsetMs)
    }

    @Test
    fun `sections on either side of a missing bridge need approval and preserve their source times`() {
        val random = Random(32)
        val trace = List(2000) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        val takeTrace = trace.take(720) + trace.drop(1080)
        fun recording(frames: List<FloatArray>) = MulticamSync.Recording(emptyList(), frames, FloatArray(0))
        val proposed = MulticamSync.alignRecordings(recording(trace), listOf(
            MulticamTake("concert.mp4", 2, takeTrace.size * 32L),
        ), mapOf("concert.mp4" to recording(takeTrace))).single()
        assertTrue(proposed.toString(), proposed.needsReview)
        assertEquals(listOf(0L, 360L * 32), proposed.proposedSpans.map { it.offsetMs })
        val group = Multicam("studio.mp3", trace.size * 32L, listOf(proposed))
        assertEquals(listOf(Multicam.GAP_FILE), group.clips().map { it.file })
        assertEquals(group, Multicam.from(group.toJson()))
        val whole = checkNotNull(group.keepWholeTake(proposed.id)).takes.single()
        assertEquals(proposed.lengthMs, whole.lengthMs)
        assertEquals(360L * 32, whole.startMs)
        assertTrue(whole.available(checkNotNull(whole.startMs)))
        assertTrue(whole.proposedSpans.isEmpty())
        val accepted = checkNotNull(group.acceptPartialAlignment(proposed.id))
        assertEquals(accepted, Multicam.from(accepted.toJson()))
        val (first, second) = accepted.takes
        assertTrue(first.endMs!! < second.startMs!!)
        assertEquals(second.sourceFromMs + 360L * 32, second.startMs)
        assertEquals(second.sourceFromMs, accepted.audition(second.id).last { it.file == second.file }.fromMs)
        assertTrue(accepted.clips().any { it.file == Multicam.GAP_FILE })
    }

    @Test
    fun `a multicam card does not depend on source highlights`() {
        val card = ProjectClip(Multicam.GROUP_FILE, 0, 12_000)
        val source = ProjectClip("video.mp4", 0, 12_000)
        val project = Project("test", "Concert", clips = listOf(card, source))
        assertEquals(listOf(source), project.staleClips(mapOf(card.file to emptyList(), source.file to emptyList())))
    }

    @Test
    fun `a noisy passage does not split a take when both ends agree on the offset`() {
        val random = Random(11)
        fun frames(size: Int) = List(size) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val trace = frames(1800)
        val master = recording(trace).index()
        assertNull(master.partialAlignment(recording(trace)) {})
        val take = recording(trace.take(540) + frames(540) + trace.drop(1080))
        assertNull(master.partialAlignment(take) {})
        val aligned = MulticamSync.alignRecordings(recording(trace), listOf(MulticamTake("angle", 1, 57_600)),
            mapOf("angle" to take)).single()
        assertFalse(aligned.needsReview)
        assertEquals(0L, aligned.startMs)
    }

    @Test
    fun `a short matching head is kept before a much longer concert section at a different offset`() {
        val random = Random(65)
        val reference = List(2800) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val candidate = reference.take(76) + reference.drop(500)
        val proposal = checkNotNull(recording(reference).index().partialAlignment(recording(candidate)) {})
        assertEquals(424L * 32, proposal.offsetMs)
        assertEquals(listOf(0L, 424L * 32), proposal.parts.map { it.offsetMs })
        assertEquals(0L, proposal.parts.first().fromMs)
        assertTrue(abs(proposal.parts.first().toMs - 76L * 32) <= 32L)
        assertEquals(proposal.parts.first().toMs, proposal.parts.last().fromMs)
    }

    @Test
    fun `a long matching ending cannot confirm an unrelated head within the reference`() {
        val random = Random(78)
        fun frames(size: Int) = List(size) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val reference = frames(1800)
        val head = frames(384)
        val candidate = head + reference.drop(768)
        val take = MulticamTake("concert", 2, candidate.size * 32L)
        val aligned = MulticamSync.alignRecordings(recording(reference), listOf(take),
            mapOf(take.file to recording(candidate))).single()
        assertTrue(aligned.needsReview)
        val ending = aligned.proposedSpans.single()
        assertTrue(ending.fromMs >= 350L * 32)
        assertEquals(384L * 32, ending.offsetMs)
        val group = Multicam("music", reference.size * 32L, listOf(aligned))
        val accepted = checkNotNull(group.acceptPartialAlignment(take.id)).takes.single()
        assertEquals(ending.fromMs, accepted.sourceFromMs)
        assertEquals(ending.fromMs + ending.offsetMs, accepted.startMs)
        assertFalse(accepted.needsReview)
        assertNull(recording(reference).index().partialAlignment(recording(head + reference)) {})
    }

    @Test
    fun `ten stitched recordings have ten independent timeline positions`() {
        val random = Random(96)
        val reference = List(9600) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        val ranges = List(10) { i -> i * 960 until i * 960 + 464 }
        val candidate = ranges.flatMap { reference.subList(it.first, it.last + 1) }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val result = checkNotNull(recording(reference).index().partialAlignment(recording(candidate)) {})
        assertEquals(List(10) { it * (960L - 464L) * 32 }, result.parts.map { it.offsetMs })
        result.parts.forEachIndexed { i, part ->
            assertTrue(abs(part.fromMs - i * 464L * 32) <= 32L)
        }
    }

    @Test
    fun `an extra concert passage is excluded between two aligned ranges`() {
        val random = Random(86)
        fun frames(size: Int) = List(size) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val original = frames(1800)
        val concert = original.take(760) + frames(420) + original.drop(760)
        val parts = checkNotNull(recording(original).index().partialAlignment(recording(concert)) {}).parts
        assertEquals(2, parts.size)
        assertEquals(listOf(0L, -420L * 32), parts.map { it.offsetMs })
        assertTrue(abs(parts.first().toMs - 760L * 32) <= 32L)
        assertTrue(abs(parts.last().fromMs - 1180L * 32) <= 32L)
    }

    @Test
    fun `an ambiguous refrain keeps both positions until adjacent music identifies the right repeat`() {
        val random = Random(17)
        fun frames(size: Int) = List(size) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val chorus = frames(512)
        val reference = frames(320) + chorus + frames(544) + chorus + frames(400)
        val index = recording(reference).index()
        val ambiguous = checkNotNull(index.partialAlignment(recording(chorus)) {}).parts.single()
        assertEquals(setOf(320L * 32, 1376L * 32), (listOf(ambiguous.offsetMs) + ambiguous.alternatives).toSet())
        val withContext = reference.drop(1376)
        assertNull(index.partialAlignment(recording(withContext)) {})
        assertEquals(1376L * 32, index.match(recording(withContext))?.offsetMs)
    }

    @Test
    fun `an already aligned angle can disambiguate distant identical choruses by shared landmarks`() {
        val random = Random(31)
        fun frames(size: Int) = List(size) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        val chorus = frames(480)
        val reference = frames(320) + chorus + frames(160) + chorus + frames(320)
        val marks = List(130) { MulticamSync.Landmark(it, it * 7) }
        val master = MulticamSync.Recording(emptyList(), reference, FloatArray(0))
        val cameraPrint = MulticamSync.Recording(marks, chorus, FloatArray(0))
        val bridge = MulticamTake("bridge", 1, 480L * 32, 960L * 32)
        val camera = MulticamTake("camera", 2, 480L * 32)
        val placed = MulticamSync.alignRecordings(master, listOf(bridge, camera),
            mapOf("bridge" to cameraPrint, "camera" to cameraPrint)).last()
        val part = placed.proposedSpans.single()
        assertEquals(960L * 32, placed.startMs)
        assertEquals(960L * 32, part.offsetMs)
        assertTrue(part.alternatives.isEmpty())
    }

    @Test
    fun `retakes and out of order ranges are offered without deleting either source range`() {
        val random = Random(86)
        val reference = List(2200) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val source = reference.subList(1300, 1700) + reference.subList(200, 600) + reference.subList(1300, 1700)
        val proposed = checkNotNull(recording(reference).index().partialAlignment(recording(source)) {}).parts
        assertEquals(listOf(1300L, 200L, 1300L), proposed.map { (it.fromMs + it.offsetMs) / 32 })
        val take = MulticamTake("retakes", 2, source.size * 32L, 0L, needsReview = true, proposedSpans = proposed)
        val group = Multicam("audio", reference.size * 32L, listOf(take))
        assertEquals(listOf(proposed[1]), take.confirmedSpans())
        val accepted = checkNotNull(group.acceptPartialAlignment(take.id, proposed.drop(1)))
        assertEquals(2, accepted.takes.size)
        assertNull(group.acceptPartialAlignment(take.id, proposed))
    }

    @Test
    fun `gradual clock drift is reported separately from camera pauses`() {
        val random = Random(55)
        val reference = List(3000) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val candidate = List(2100) { reference[200 + it + it / 240] }
        val result = checkNotNull(recording(reference).index().partialAlignment(recording(candidate)) {})
        assertTrue(result.parts.isEmpty())
        assertTrue(checkNotNull(result.driftMs) in 160L..320L)
    }

    @Test
    fun `preview reports which existing shot would lose coverage before replacing the take`() {
        val old = MulticamTake("wide", 2, 10_000, 0)
        val proposal = old.copy(needsReview = true, replacesPlacement = true,
            proposedSpans = listOf(MulticamSpan(0, 5_000, 0)))
        val group = Multicam("master", 10_000, listOf(old, proposal), switches = mapOf(1_000L to 2))
        assertEquals(group, Multicam.from(group.toJson()))
        val next = checkNotNull(group.acceptPartialAlignment(proposal.id))
        assertEquals(listOf(0L), group.lostCoverage(next, 0L, 10_000L).map { it.start })
        assertEquals(10_000L, group.takes.first().lengthMs)
        assertEquals(5_000L, next.takes.single().lengthMs)
    }

    @Test
    fun `an unsynchronised file can become muted cutaways without replacing manual shots`() {
        val main = MulticamTake("master.mov", 1, 12_000, 0)
        val second = MulticamTake("close.mov", 2, 12_000, 0)
        val unplaced = MulticamTake("crowd.mov", 3, 3_200)
        val group = Multicam(main.file, 12_000, listOf(main, second, unplaced),
            switches = mapOf(3_000L to 2, 6_000L to 1), manualCuts = setOf(3_000L))
        val ready = checkNotNull(group.keepCutaway(unplaced.id))
        val cutaway = ready.takes.single { it.file == unplaced.file }
        assertTrue(cutaway.cutaway)
        assertNull(cutaway.startMs)
        assertFalse(cutaway.available(0L))
        assertFalse(ready.clips().any { it.file == unplaced.file })
        val slots = (0L until 12_000L step 2_000L).map { Montage.Slot(it, it + 2_000L, 0.5f) }
        val edited = ready.autoSwitch(slots)
        assertEquals(edited, Multicam.from(edited.toJson()))
        assertEquals(2, edited.switches[3_000L])
        assertTrue(edited.cutaways.isNotEmpty())
        assertTrue(edited.cutaways.all { it.atMs >= 6_000L || it.endMs <= 3_000L })
        val pieces = edited.clips()
        assertEquals(12_000L, pieces.sumOf { it.durationMs })
        val inserts = pieces.filter { it.file == unplaced.file }
        assertTrue(inserts.all { it.muteSource })
        assertTrue(inserts.sumOf { it.durationMs } <= unplaced.lengthMs)
        assertTrue(edited.audition(main.id).none { it.file == unplaced.file })
        val first = edited.cutaways.first()
        val replaced = checkNotNull(edited.replaceShot(first.atMs, first.endMs, 1))
        assertFalse(replaced.clips(fromMs = first.atMs, untilMs = first.endMs).any { it.file == unplaced.file })
    }

    @Test
    fun `returning a cutaway to sync keeps the file and removes its scheduled inserts`() {
        val ref = MulticamTake("ref", 1, 4_000, 0)
        val cutaway = MulticamTake("crowd", 2, 2_000, cutaway = true)
        val group = Multicam(ref.file, 4_000, listOf(ref, cutaway),
            cutaways = listOf(MulticamCutaway(cutaway.file, 1_000, 0, 2_000)))
        val restored = checkNotNull(group.reopenCutaway(cutaway.id))
        assertEquals(listOf("ref", "crowd"), restored.takes.map { it.file })
        assertTrue(restored.takes.last().needsReview)
        assertFalse(restored.takes.last().cutaway)
        assertTrue(restored.cutaways.isEmpty())
        assertEquals(listOf("ref"), restored.clips().map { it.file })
    }

    @Test
    fun `reset keeps the selected source identities but replaces placements and clears edit decisions`() {
        val ref = MulticamTake("ref", 1, 8_000, 0)
        val placed = MulticamTake("side", 2, 4_000, 1_000)
        val cutaway = MulticamTake("crowd", 3, 2_000, cutaway = true)
        val group = Multicam(ref.file, 8_000, listOf(ref, placed, cutaway),
            switches = mapOf(1_000L to 2), manualCuts = setOf(1_000L),
            cutaways = listOf(MulticamCutaway(cutaway.file, 5_000, 0, 2_000)))
        val selection = group.takes.associate { it.file to it.camera }
        val fresh = listOf(ref, placed.copy(startMs = 2_000), cutaway.copy(cutaway = false, needsReview = true))
        val next = group.withNewAlignment(ref.file, 8_000, fresh, selection, reset = true)
        assertEquals(selection, next.takes.associate { it.file to it.camera })
        assertEquals(fresh, next.takes)
        assertTrue(next.switches.isEmpty() && next.manualCuts.isEmpty() && next.cutaways.isEmpty())
        val preserving = group.withNewAlignment(ref.file, 8_000, listOf(ref, fresh[1], cutaway), selection, reset = false)
        assertEquals(group.cutaways, preserving.cutaways)
        assertEquals(group.manualCuts, preserving.manualCuts)
        assertTrue(preserving.takes.any { it.replacesPlacement && it.file == "side" })
    }

    @Test
    fun `an isolated interior match cannot create an independent placed range`() {
        val random = Random(980)
        fun frames(count: Int) = List(count) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val reference = frames(3000)
        val candidate = frames(432) + reference.subList(2200, 2264) + frames(304) + reference.drop(1200)
        val result = checkNotNull(recording(reference).index().partialAlignment(recording(candidate)) {})
        assertEquals(listOf(400L * 32), result.parts.map { it.offsetMs })
        assertTrue(result.parts.single().fromMs >= 720L * 32)
    }

    @Test
    fun `sparse anchors recover both long halves around a skipped passage in the reference`() {
        val random = Random(731)
        fun frames(count: Int) = List(count) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        fun recording(trace: List<FloatArray>) = MulticamSync.Recording(emptyList(), trace, FloatArray(0))
        val reference = frames(8100)
        val cut = 3500
        val skipped = 650
        val original = reference.take(cut) + reference.drop(cut + skipped)
        val noisy = original.mapIndexed { i, signal ->
            if (i in 128 until 416 || i in 5504 until 5792) return@mapIndexed signal
            val noise = frames(1).single()
            val power = signal.sumOf { (it * it).toDouble() }
            val projection = signal.indices.sumOf { (signal[it] * noise[it]).toDouble() } / power
            val orthogonal = DoubleArray(signal.size) { noise[it] - projection * signal[it] }
            val scale = sqrt(power / orthogonal.sumOf { it * it })
            FloatArray(signal.size) { (signal[it] * 0.45 + orthogonal[it] * scale * 0.893).toFloat() }
        }
        val parts = checkNotNull(recording(reference).index().partialAlignment(recording(noisy)) {}).parts
        assertEquals(listOf(0L, skipped * 32L), parts.map { it.offsetMs })
        assertTrue(parts.toString(), parts.first().fromMs <= 1500L)
        assertTrue(parts.toString(), abs(parts.first().toMs - cut * 32L) < 2000L)
        assertEquals(parts.first().toMs, parts.last().fromMs)
        assertTrue(parts.toString(), parts.last().toMs >= original.size * 32L - 2000L)
    }

    @Test
    fun `identical harmonic refrains retain both positions when there is no surrounding context`() {
        val random = Random(189)
        fun chords(n: Int) = List(n) { FloatArray(12) { random.nextFloat() - 0.5f } }
        val chorus = chords(512)
        fun recording(frames: List<FloatArray>) =
            MulticamSync.Recording(emptyList(), emptyList(), FloatArray(0), frames)
        val index = recording(chords(256) + chorus + chords(128) + chorus).index()
        val part = checkNotNull(index.partialAlignment(recording(chorus)) {}).parts.single()
        assertEquals(setOf(256L * 32, 896L * 32), (part.alternatives + part.offsetMs).toSet())
    }

    @Test
    fun `weak chroma phrases align across two skipped passages without onset or fingerprint hits`() {
        val random = Random(822)
        fun chord() = FloatArray(12) { random.nextFloat() * 2 - 1 }
        val chords = List(800) { chord() }
        val reference = List(3200) { chords[it / 4] }
        val frames = (0 until 960) + (1120 until 2080) + (2368 until 3200)
        val noise = frames.map { chord() }
        val concert = frames.mapIndexed { i, at ->
            FloatArray(12) { k -> reference[at][k] * 0.48f + noise[i][k] }
        }
        fun recording(harmony: List<FloatArray>) =
            MulticamSync.Recording(emptyList(), emptyList(), FloatArray(0), harmony)
        val parts = checkNotNull(recording(reference).index().partialAlignment(recording(concert)) {}).parts
        assertEquals(listOf(0L, 160L * 32, 448L * 32), parts.map { it.offsetMs })
        assertTrue(parts.toString(), parts.sumOf { it.toMs - it.fromMs } > frames.size * 32L * 0.90)
        assertTrue(parts.all { it.alternatives.isEmpty() })
    }

    @Test
    fun `conflicting landmark fragments do not split a complete independently verified take`() {
        val random = Random(118)
        val full = List(5000) { FloatArray(5) { random.nextFloat() * 2 - 1 } }
        val falseAnchors = (3200 until 4990 step 6).mapIndexed { hash, at -> MulticamSync.Landmark(hash, at) }
        val reference = MulticamSync.Recording(falseAnchors.map { it.copy(frame = it.frame - 1200) }, full, FloatArray(0))
        val print = MulticamSync.Recording(falseAnchors, full, FloatArray(0))
        val take = MulticamTake("angle", 1, full.size * 32L)
        val result = MulticamSync.alignRecordings(reference, listOf(take), mapOf(take.file to print)).single()
        assertEquals(0L, result.startMs)
        assertFalse(result.needsReview)
        assertTrue(result.proposedSpans.isEmpty())
    }

    @Test
    fun `cutaway footage covers a later empty slot before replacing an already covered slot`() {
        val main = MulticamTake("main", 1, 8_000, 0)
        val cutaway = MulticamTake("crowd", 2, 2_000, cutaway = true)
        val group = Multicam("music", 10_000, listOf(main, cutaway))
        val result = group.autoSwitch((0L until 10_000L step 2_000L).map { Montage.Slot(it, it + 2_000L, 0.5f) })
        assertEquals(MulticamCutaway("crowd", 8_000, 0, 2_000), result.cutaways.single())
        assertTrue(result.clips().none { it.file == Multicam.GAP_FILE })
    }

    @Test
    fun `a cutaway can be placed at the cursor and moved without a rebuild undoing that placement`() {
        val main = MulticamTake("main", 1, 14_000, 0)
        val crowd = MulticamTake("crowd", 2, 8_000, cutaway = true)
        val initial = Multicam("main", 14_000, listOf(main, crowd))
        val placed = checkNotNull(initial.placeCutaway(crowd.id, 2_000, 14_000))
        val clip = placed.cutaways.single()
        assertEquals(MulticamCutaway("crowd", 2_000, 0, 3_000, manual = true), clip)
        assertNull(placed.placeCutaway(crowd.id, 3_000, 14_000))
        val moved = checkNotNull(placed.moveCutaway(clip, 6_000, 0, 14_000))
        val pin = clip.copy(atMs = 6_000)
        assertEquals(listOf(pin), moved.cutaways)
        assertEquals(moved, Multicam.from(moved.toJson()))
        val slots = (0L until 14_000L step 2_000L).map { Montage.Slot(it, it + 2_000L, 0.5f) }
        val rebuilt = moved.autoSwitch(slots)
        assertTrue(pin in rebuilt.cutaways)
        assertTrue(rebuilt.cutaways.filter { it != pin }.all { it.endMs <= pin.atMs || it.atMs >= pin.endMs })
        assertTrue(rebuilt.cutaways.filter { !it.manual }.all { it.fromMs >= pin.lengthMs })
    }

    @Test
    fun `linked sound follows a moved group and trims the rendered angles in source time`() {
        val group = Multicam("reference", 12_000, listOf(MulticamTake("angle.mp4", 1, 12_000, 0)))
        val intro = ProjectClip("intro.mp4", 0, 2_000)
        val outro = ProjectClip("outro.mp4", 0, 1_000)
        val master = ProjectAudioClip(Multicam.MASTER_ID, ProjectMusic("ref", lengthMs = 12_000), fromMs = 2_500, toMs = 8_000)
        val project = Project("test", "Concert", clips = listOf(intro, ProjectClip(Multicam.GROUP_FILE, 0, 1), outro),
            audioClips = listOf(master), multicam = group).withMulticamAudio()
        assertEquals(2_000L, project.multicamMaster?.startMs)
        assertEquals(listOf(intro, ProjectClip("angle.mp4", 2500, 8000, muteSource = true), outro), project.renderedClips())
        val moved = project.movedSequence(1, 2).withMulticamAudio()
        assertEquals(3_000L, moved.multicamMaster?.startMs)
        assertEquals(5_500L, moved.clips.last().durationMs)
        assertEquals(moved, Project.decode(Project.encode(moved)))
    }

    @Test
    fun `a roll checks both camera ranges and replacing a shot preserves its neighbours`() {
        val group = Multicam("a", 10_000, listOf(
            MulticamTake("a", 1, 10_000, 0),
            MulticamTake("b", 2, 6_000, 2_000),
            MulticamTake("c", 3, 10_000, 0),
        ), switches = mapOf(4000L to 2, 7000L to 1))
        val (left, right) = group.shots(0, 10_000)
        assertNull(group.roll(left, right, 1900))
        val rolled = checkNotNull(group.roll(left, right, 4800))
        assertEquals(listOf(4800L, 7000L, 10_000L), rolled.shots(0, 10_000).map { it.end })
        val replaced = checkNotNull(group.replaceShot(4000, 7000, 3))
        assertEquals(listOf("a", "c", "a"), replaced.shots(0, 10_000).map { it.file })
        assertEquals(listOf(4000L, 7000L, 10_000L), replaced.shots(0, 10_000).map { it.end })
    }

    @Test
    fun `a flattened alpha20 group loads as one clip while keeping its edits`() {
        val group = Multicam("ref", 7000, listOf(
            MulticamTake("a", 1, 7000, 0), MulticamTake("b", 2, 7000, 0),
        ), switches = mapOf(4000L to 2), manualCuts = setOf(4000L))
        val master = ProjectAudioClip(Multicam.MASTER_ID, ProjectMusic("ref", lengthMs = 7000))
        val old = Project("legacy", "Concert", clips = group.clips(), audioClips = listOf(master), multicam = group)
        val migrated = checkNotNull(Project.decode(Project.encode(old)))
        assertEquals(listOf(Multicam.GROUP_FILE), migrated.clips.map { it.file })
        assertEquals(listOf("a", "b"), migrated.renderedClips().map { it.file })
        assertEquals(setOf(4000L), migrated.multicam?.manualCuts)
    }
}
