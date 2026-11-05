package app.goultra.transfer.camera

import app.goultra.transfer.camera.osc.OscParsing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Parsing of the OSC answers of other Insta360 cameras — the shapes come from the official Insta360 OSC notes
 * (`github.com/Insta360Develop/Insta360_OSC`), so the alpha can be tested without the hardware.
 */
class OscParsingTest {
    private fun json(text: String) = JSONObject(text)

    @Test
    fun infoCarriesTheModelAndMasksTheSerial() {
        val info = OscParsing.info(
            json(
                """{"manufacturer":"Arashi Vision","model":"Insta360 X4","serialNumber":"IXE3619AYS76P6",""" +
                    """"firmwareVersion":"v1.2.3"}""",
            ),
        )
        assertEquals("Insta360 X4", info.model)
        assertEquals("v1.2.3", info.firmware)
        assertEquals("IXE3****P6", info.maskedSerial)
    }

    @Test
    fun stateCarriesBatteryAndStorage() {
        val state = OscParsing.state(
            json(
                """{"fingerprint":"FPR_52923","state":{"_cardState":"pass","batteryLevel":0.75,""" +
                    """"storageUri":"http://192.168.42.1:80/DCIM/Camera01/"}}""",
            ),
        )
        assertEquals(0.75, state.batteryLevel!!, 0.0001)
        assertEquals("pass", state.cardState)
        assertEquals("http://192.168.42.1:80/DCIM/Camera01/", state.storageUri)
    }

    @Test
    fun listFilesParsesEntriesAndTheTotal() {
        val page = json(
            """{"name":"camera.listFiles","state":"done","results":{"entries":[""" +
                """{"name":"IMG_20180106_180200_00_006.jpg",""" +
                """"fileUrl":"http://192.168.42.1:80/DCIM/Camera01/IMG_20180106_180200_00_006.jpg",""" +
                """"_localFileUrl":"/DCIM/Camera01/IMG_20180106_180200_00_006.jpg","size":4494224,""" +
                """"width":6080,"height":3040,"dateTimeZone":"2018:01:06 18:02:14+08:00"},""" +
                """{"name":"VID_20180106_172302_00_005.insv",""" +
                """"fileUrl":"http://192.168.42.1:80/DCIM/Camera01/VID_20180106_172302_00_005.insv",""" +
                """"_localFileUrl":"/DCIM/Camera01/VID_20180106_172302_00_005.insv","size":1000}],""" +
                """"totalEntries":7}}""",
        )
        val files = OscParsing.files(page)
        assertEquals(2, files.size)
        assertEquals(7, OscParsing.totalEntries(page))
        val photo = files[0]
        assertEquals("/DCIM/Camera01/IMG_20180106_180200_00_006.jpg", photo.path)
        assertEquals(4494224L, photo.size)
        assertEquals(6080, photo.width)
        assertEquals(false, photo.isVideo)
        val expected = OffsetDateTime.parse("2018-01-06T18:02:14+08:00").toInstant().toEpochMilli()
        assertEquals(expected, photo.takenAtMillis)
        assertTrue(files[1].isVideo)
        assertEquals("insv", files[1].extension)
    }

    @Test
    fun aFileWithoutAnUrlIsSkipped() {
        val page = json("""{"results":{"entries":[{"name":"weird.bin","size":1}]}}""")
        assertTrue(OscParsing.files(page).isEmpty())
    }

    @Test
    fun theTwoHalvesOfA360VideoBecomeOneShot() {
        val files = OscParsing.groupIntoShots(
            listOf(
                file("IMG_20260101_120000_00_001.jpg"),
                file("VID_20260101_130000_10_007.insv"),
                file("VID_20260101_130000_00_007.insv"),
                file("LRV_20260101_130000_007.lrv"),
                file("VID_20260101_140000_00_008.insv"),
            ),
        )
        assertEquals(3, files.size)
        assertEquals("IMG_20260101_120000_00_001.jpg", files[0].name)
        // The pair stands where its first half does and holds both halves, sorted.
        assertEquals(2, files[1].files.size)
        assertEquals("VID_20260101_130000_00_007.insv", files[1].files[0].name)
        assertEquals("VID_20260101_130000_10_007.insv", files[1].files[1].name)
        assertTrue(files[1].is360)
        // A lone half is a shot of its own.
        assertEquals(1, files[2].files.size)
    }

    @Test
    fun pairKeyOnlyAppliesToInsvHalves() {
        assertNotNull(OscParsing.pairKey(file("VID_20260101_130000_00_007.insv")))
        assertEquals(
            OscParsing.pairKey(file("VID_20260101_130000_00_007.insv")),
            OscParsing.pairKey(file("VID_20260101_130000_10_007.insv")),
        )
        assertNull(OscParsing.pairKey(file("VID_20260101_130000.insv")))
        assertNull(OscParsing.pairKey(file("IMG_20260101_130000_00_007.jpg")))
    }

    @Test
    fun aMissingOrBrokenDateIsSimplyAbsent() {
        assertNull(OscParsing.takenAt(null))
        assertNull(OscParsing.takenAt(""))
        assertNull(OscParsing.takenAt("06/01/2018 18:02"))
    }

    private fun file(name: String) = app.goultra.transfer.camera.osc.OscFile(
        name = name,
        path = "/DCIM/Camera01/$name",
        size = 1_000L,
        width = null,
        height = null,
        takenAtMillis = null,
        url = null,
    )
}
