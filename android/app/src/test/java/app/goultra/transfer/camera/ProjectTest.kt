package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
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
        music = ProjectMusic(
            uri = "content://media/external/audio/media/1", name = "track.mp3",
            offsetMs = 3_000, volume = 0.8, duck = false, beats = listOf(0L, 500L, 1_000L), dropMs = 32_000,
        ),
        previous = listOf(ProjectClip(file = "VID_20261105_211421_165.mp4", fromMs = 1_200, toMs = 4_000)),
    )

    @Test
    fun `a project survives a round trip`() {
        val back = Project.decode(Project.encode(sample()))
        assertNotNull(back)
        assertEquals(sample(), back)
    }

    @Test
    fun `a partial file comes back with the defaults`() {
        val p = Project.decode("""{"id":"1-0001","name":"Пусто"}""")
        assertNotNull(p)
        assertEquals(Scenario.CUSTOM, p!!.scenario)
        assertEquals(Dynamics.EVEN, p.dynamics)
        assertTrue(p.clips.isEmpty())
        assertNull(p.music)
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
    fun `the clips of an edited file follow its new pieces`() {
        val p = sample()
        // The first file was edited in the film strip: one piece, in other words.
        val edited = mapOf("VID_20261105_211421_165.mp4" to listOf(Highlight(2_000, 5_000)))
        val next = p.resynced(edited)
        assertEquals(listOf(2_000L, 5_000L), listOf(next.clips[0].fromMs, next.clips[0].toMs))
        assertTrue(next.clips[0].auto)                          // still the auto montage's piece
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
    fun `a clip taken out by hand does not come back with an edit`() {
        val p = sample().copy(clips = sample().clips + ProjectClip("VID_20261105_211421_165.mp4", 6_000, 7_000))
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