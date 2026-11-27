package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The project JSON and its store: the montage state must survive restarts, a broken file must not be fatal, and an
 * unknown value written by a newer build must not crash an older one.
 */
class ProjectTest {
    @Test
    fun `drop edits preserve the main accent and survive a project round trip`() {
        val source = ProjectMusic("song", lengthMs = 9_000, drops = listOf(2_000L, 6_000L), activeDropMs = 6_000)
        val added = source.addedDrop(4_000)
        assertEquals(6_000L, added.activeDropMs)
        assertEquals(added, added.movedDrop(6_000, 4_000))
        assertEquals(added, added.movedDrop(6_000, 9_000))
        val edited = added.movedDrop(6_000, 6_010).removedDrop(2_000)
        assertEquals(listOf(4_000L, 6_010L), edited.drops)
        assertEquals(6_010L, edited.activeDropMs)
        assertTrue(edited.dropsEdited)
        val project = Project("drops", "Drop editor", audioClips = listOf(ProjectAudioClip("clip", edited)))
        assertEquals(project, Project.decode(Project.encode(project)))
        assertEquals(4_000L, edited.removedDrop(6_010).activeDropMs)
        assertEquals(4_000L, edited.primaryDrop(4_000).activeDropMs)
        assertNull(edited.removedDrop(6_010).removedDrop(4_000).activeDropMs)
        val cleared = source.clearedDrops()
        assertTrue(cleared.drops.isEmpty())
        assertNull(cleared.activeDropMs)
        assertTrue(cleared.dropsEdited)
        assertEquals(cleared, ProjectMusic.from(cleared.toJson()))
    }
    private fun sample() = Project(
        id = "1762400000000-00ab",
        name = "Тест",
        createdMs = 1_762_400_000_000L,
        scenario = Scenario.MUSIC,
        dynamics = Dynamics.DYNAMIC,
        baseFile = "VID_20261105_193759_162.mp4",
        clips = listOf(
            ProjectClip(
                file = "VID_20261105_211421_165.mp4", fromMs = 1_000, toMs = 4_000,
                auto = true, role = ProjectClip.Role.INSERT, energy = 0.5,
            ),
            ProjectClip(file = "VID_20261105_193759_162.mp4", fromMs = 0, toMs = 12_000),
            ProjectClip(file = "VID_20261105_201500_180.mp4", fromMs = 10_000, toMs = 12_000, atMs = 45_000),
        ),
        audioClips = listOf(
            ProjectAudioClip(
                "audio-1",
                ProjectMusic(
                    uri = "content://media/external/audio/media/1", name = "track.mp3",
                    beats = listOf(0L, 500L, 1_000L), drops = listOf(8_000L, 32_000L),
                    activeDropMs = 32_000L, lengthMs = 180_000, dropVersion = 4,
                ),
                startMs = 15_000, fromMs = 3_000, toMs = 50_000, volume = 0.75,
            ),
            ProjectAudioClip("audio-2", ProjectMusic("content://audio/2", lengthMs = 20_000), startMs = 60_000),
        ),
        clickVolume = 0.3,
        musicVolume = 0.8,
        videoVolume = 0.5,
        previous = listOf(ProjectClip(file = "VID_20261105_211421_165.mp4", fromMs = 1_200, toMs = 4_000)),
    )

    @Test
    fun `a project survives a round trip`() {
        val back = Project.decode(Project.encode(sample()))
        assertNotNull(back)
        assertEquals(sample(), back)
    }

    @Test
    fun `the mix survives removing a track, a restart and adding another`() {
        val mixed = sample().copy(musicVolume = 0.27, videoVolume = 0.83)
        val gone = mixed.copy(audioClips = emptyList())
        assertTrue(gone.audioClips.isEmpty())
        assertEquals(0.27, gone.musicVolume, 0.0)
        assertEquals(0.83, gone.videoVolume, 0.0)
        // A restart is nothing but JSON: the levels are kept apart from the track, so they come back with it.
        val afterRestart = Project.decode(Project.encode(gone))!!
        val added = afterRestart.copy(audioClips = listOf(ProjectAudioClip("new", ProjectMusic("content://audio/3"))))
        assertEquals(0.27, added.musicVolume, 0.0)
        assertEquals(0.83, added.videoVolume, 0.0)
        assertEquals("the new track gets its own grid", emptyList<Long>(), added.audioClips.single().source.beats)
        assertEquals("and its own drops", emptyList<Long>(), added.audioClips.single().source.drops)
        // The mix belongs to one project only: a new project starts from the defaults, not from the neighbour's sliders.
        val other = Project(id = "1-0002", name = "Другой")
        assertEquals(1.0, other.musicVolume, 0.0)
        assertEquals(ProjectMusic.UNDER_MUSIC, other.videoVolume, 0.0)
    }

    @Test
    fun `zero is a real level and is kept too`() {
        val silent = sample().copy(musicVolume = 0.0, videoVolume = 0.0)
        val back = Project.decode(Project.encode(silent.copy(audioClips = emptyList())))!!
        assertEquals(0.0, back.musicVolume, 0.0)
        assertEquals(0.0, back.videoVolume, 0.0)
        val added = back.copy(audioClips = listOf(ProjectAudioClip("new", ProjectMusic("content://audio/3"))))
        assertEquals(0.0, added.musicVolume, 0.0)
        assertEquals(0.0, added.videoVolume, 0.0)
    }

    @Test
    fun `a partial file comes back with the defaults`() {
        val p = Project.decode("""{"id":"1-0001","name":"Пусто"}""")
        assertNotNull(p)
        assertEquals(Scenario.CUSTOM, p!!.scenario)
        assertEquals(Dynamics.EVEN, p.dynamics)
        assertTrue(p.clips.isEmpty())
        assertTrue(p.audioClips.isEmpty())
        assertNull(p.previous)
    }

    @Test
    fun `garbage is null, a broken clip is skipped`() {
        assertNull(Project.decode("не json"))
        assertNull(Project.decode("{}"))
        val p = Project.decode(
            """{"id":"1-0001","name":"Тест","clips":[{"file":"a.mp4","from":0,"to":100},{"file":"","from":0,"to":100},{"file":"b.mp4","to":100}]}""",
        )
        assertNotNull(p)
        assertEquals(listOf("a.mp4"), p!!.clips.map { it.file })
    }

    @Test
    fun `a file from before the base reads without one, and cutaways stay apart from the sequence`() {
        val json = "{\"id\":\"1-0001\",\"name\":\"По-старому\",\"clips\":[" +
            "{\"file\":\"a.mp4\",\"from\":0,\"to\":100},{\"file\":\"b.mp4\",\"from\":0,\"to\":100,\"at\":5000}]}"
        val p = Project.decode(json)
        assertNotNull(p)
        assertNull(p!!.baseFile)
        assertEquals(listOf("a.mp4"), p.sequence.map { it.file })
        assertEquals(listOf("b.mp4"), p.covers.map { it.file })
    }

    @Test
    fun `a removed clip goes, and the base goes with its last clip`() {
        val p = sample()
        // The cutaway is no part of the timeline's own length: only two clips are left.
        assertEquals(2, p.without(p.clips.last()).clips.size)
        // The base's clip is what holds the timeline: remove it and the base goes too.
        val noBaseClip = p.without(p.clips[1])
        assertNull(noBaseClip.baseFile)
        assertEquals(listOf("VID_20261105_211421_165.mp4", "VID_20261105_201500_180.mp4"), noBaseClip.clips.map { it.file })
    }

    @Test
    fun `the sequence moves and cutaways keep their moment`() {
        val p = sample().copy(
            clips = listOf(
                ProjectClip("a.mp4", 0, 1_000),
                ProjectClip("b.mp4", 0, 1_000),
                ProjectClip("d.mp4", 0, 1_000),
                ProjectClip("c.mp4", 0, 1_000, atMs = 5_000),
            ),
        )
        assertEquals(listOf("b.mp4", "d.mp4", "a.mp4", "c.mp4"), p.movedSequence(0, 2).clips.map { it.file })
        assertEquals(listOf("d.mp4", "a.mp4", "b.mp4", "c.mp4"), p.movedSequence(2, 0).clips.map { it.file })
        assertEquals(p, p.movedSequence(9, 0))          // out of range: untouched
        assertEquals(p, p.movedSequence(1, 1))          // no move
    }

    @Test
    fun `manual clips of an edited file follow its new pieces`() {
        // No piece here came from the auto montage: these are ordinary live links.
        val p = sample().copy(clips = sample().clips.map { it.copy(auto = false) })
        // The first file was edited in the film strip: one piece, in other words.
        val edited = mapOf("VID_20261105_211421_165.mp4" to listOf(Highlight(2_000, 5_000)))
        val next = p.resynced(edited)
        assertEquals(listOf(2_000L, 5_000L), listOf(next.clips[0].fromMs, next.clips[0].toMs))
        assertFalse(next.clips[0].auto)
        assertEquals(p.clips[1], next.clips[1])                 // the other file's clips keep their place
        assertEquals(3, next.clips.size)
        // The pieces are what the project takes of that file now: nothing reads as «gone from the file».
        assertTrue(next.staleClips(edited).isEmpty())
        // A file edited down to nothing leaves the project, and the base goes with its last clip.
        val emptied = p.resynced(mapOf("VID_20261105_193759_162.mp4" to emptyList()))
        assertNull(emptied.baseFile)
        assertEquals(listOf("VID_20261105_211421_165.mp4", "VID_20261105_201500_180.mp4"), emptied.clips.map { it.file })
    }

    @Test
    fun `an auto montage is not silently collapsed by a source edit`() {
        val p = sample()
        val edited = mapOf("VID_20261105_211421_165.mp4" to listOf(Highlight(2_000, 5_000)))
        val next = p.resynced(edited)
        // The cut stays as it was made; the range the source lost is reported as stale, not rewritten behind the cut.
        assertEquals(p.clips, next.clips)
        assertTrue(next.clips[0].auto)
        assertEquals(listOf("VID_20261105_211421_165.mp4"), next.staleClips(edited).map { it.file })
    }

    @Test
    fun `the auto montage keeps one undo snapshot`() {
        val p = sample()
        val cut = listOf(
            ProjectClip("a.mp4", 0, 2_000, auto = true),
            ProjectClip("a.mp4", 2_000, 4_000, auto = true),
        )
        val auto = p.autoMontaged(cut)
        assertEquals(p.clips, auto.previous)
        assertEquals(cut, auto.clips)
        // A hand edit after the montage invalidates the snapshot: undo must not throw that work away.
        assertEquals(null, auto.editedByHand().previous)
        assertEquals(auto.clips, auto.editedByHand().clips)
        // The snapshot survives JSON, and undo restores exactly the pieces before the cut.
        val back = Project.decode(Project.encode(auto))!!
        assertEquals(auto.previous, back.previous)
        assertEquals(p.clips, back.undoAutoMontage().clips)
        assertNull(back.undoAutoMontage().previous)
        // Nothing to undo is the project itself, not an empty timeline.
        val noUndo = p.copy(previous = null)
        assertEquals(noUndo, noUndo.undoAutoMontage())
    }

    @Test
    fun `a rebuilt auto montage keeps the original pieces as its one undo`() {
        val original = Project(
            id = "1", name = "Повтор",
            clips = listOf(
                ProjectClip("a.mp4", 0, 30_000),
                ProjectClip("b.mp4", 0, 30_000),
            ),
        )
        val auto1 = original.autoMontaged(
            listOf(ProjectClip("a.mp4", 0, 5_000, auto = true), ProjectClip("b.mp4", 0, 5_000, auto = true)),
        )
        // The second run is built from the original takes, not from the first run's small pieces.
        assertEquals(original.clips, auto1.montageBase())
        val auto2 = auto1.autoMontaged(
            listOf(ProjectClip("b.mp4", 0, 8_000, auto = true), ProjectClip("a.mp4", 10_000, 18_000, auto = true)),
        )
        assertEquals(original.clips, auto2.montageBase())
        assertEquals(original.clips, auto2.previous)
        assertEquals(original.clips, auto2.undoAutoMontage().clips)
        assertNull(auto2.undoAutoMontage().previous)
        // And the snapshot survives a save and load just as it did before.
        val back = Project.decode(Project.encode(auto2))!!
        assertEquals(original.clips, back.montageBase())
        assertEquals(original.clips, back.undoAutoMontage().clips)
    }

    @Test
    fun `a clip taken out by hand does not come back with an edit`() {
        val base = sample().copy(clips = sample().clips.map { it.copy(auto = false) })
        val p = base.copy(clips = base.clips + ProjectClip("VID_20261105_211421_165.mp4", 6_000, 7_000))
        val one = p.without(p.clips[3])                         // the second piece of the first file, dropped
        assertEquals(3, one.clips.size)
        val next = one.resynced(
            mapOf(
                "VID_20261105_211421_165.mp4" to
                    listOf(Highlight(1_000, 4_000), Highlight(2_000, 5_000), Highlight(6_000, 7_000)),
            ),
        )
        // The dropped piece stays dropped; the piece the edit added arrives; the others keep their place.
        assertEquals(
            listOf(
                Triple("VID_20261105_211421_165.mp4", 1_000L, 4_000L),
                Triple("VID_20261105_211421_165.mp4", 2_000L, 5_000L),
                Triple("VID_20261105_193759_162.mp4", 0L, 12_000L),
                Triple("VID_20261105_201500_180.mp4", 10_000L, 12_000L),
            ),
            next.clips.map { Triple(it.file, it.fromMs, it.toMs) },
        )
    }

    @Test
    fun `an unknown enum or role written by a newer build falls back`() {
        val p = Project.decode("""{"id":"1-0001","name":"Тест","scenario":"NEXT_BIG_THING","dynamics":"WARP","clips":[{"file":"a.mp4","from":0,"to":100,"role":"HOLOGRAM"}]}""")
        assertNotNull(p)
        assertEquals(Scenario.CUSTOM, p!!.scenario)
        assertEquals(Dynamics.EVEN, p.dynamics)
        assertEquals(ProjectClip.Role.TAKE, p.clips.single().role)
    }

    @Test
    fun `a clip whose file was re-edited shows as stale`() {
        val p = sample()
        // The first file's piece was cut back on the phone: its clip no longer fits; the second file is untouched.
        val pieces = mapOf(
            "VID_20261105_211421_165.mp4" to listOf(Highlight(0, 1_500)),
            "VID_20261105_193759_162.mp4" to listOf(Highlight(0, 12_000)),
        )
        assertEquals(listOf("VID_20261105_211421_165.mp4"), p.staleClips(pieces).map { it.file })
        // A file nothing is known about (not on the card any more, say): not stale, just unknown.
        assertTrue(p.staleClips(emptyMap()).isEmpty())
    }

    @Test
    fun `a still is never called gone`() {
        // A photo has no pieces to go from: its card must not wear «кусок убран из файла» (the pool of a photo-less
        // file is empty, which is not the same as a video whose pieces were all edited away).
        val p = Project(
            id = "1", name = "Фото",
            clips = listOf(
                ProjectClip("IMG_0001.jpg", 0, 3_000, role = ProjectClip.Role.STILL),
                ProjectClip("a.mp4", 0, 1_000),
            ),
        )
        assertTrue(p.staleClips(mapOf("IMG_0001.jpg" to emptyList(), "a.mp4" to listOf(Highlight(0, 1_000)))).isEmpty())
    }

    @Test
    fun `the store saves, lists newest first, renames and deletes`() {
        val dir = Files.createTempDirectory("projects").toFile()
        val store = Projects(dir)
        assertTrue(store.list().isEmpty())

        val older = store.create("Первая", createdMs = 1_000_000)
        val newer = store.create("Вторая", createdMs = 2_000_000)
        assertEquals(listOf("Вторая", "Первая"), store.list().map { it.name })

        store.save(newer.copy(clips = listOf(ProjectClip("a.mp4", 0, 500))))
        assertEquals(500L, store.list().first().clips.single().toMs)

        val renamed = store.rename(older, "Переименована")
        assertEquals("Переименована", store.list().last().name)
        assertEquals(renamed.id, store.list().last().id)

        store.delete(newer)
        assertEquals(listOf("Переименована"), store.list().map { it.name })
    }
}
