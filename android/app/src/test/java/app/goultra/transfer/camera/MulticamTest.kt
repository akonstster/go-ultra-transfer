package app.goultra.transfer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
