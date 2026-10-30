package app.goultra.transfer.ui

import app.goultra.transfer.camera.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFileFilterTest {
    private fun file(path: String, localUri: String? = null) =
        CameraFile(path, null, null, null, null, null, null, localUri = localUri)

    @Test
    fun showsCameraFilesIncludingOnesWithPhoneCopies() {
        val camera = file("/DCIM/Camera01/VID_1.mp4")
        val downloadedCamera = file("/DCIM/Camera01/VID_2.mp4", "content://downloaded")
        val phoneOnly = file("/phone/VID_3.mp4", "content://phone")
        val added = file("/added/VID_4.mp4", "content://added")
        assertEquals(listOf(camera, downloadedCamera), cameraFiles(listOf(camera, phoneOnly, downloadedCamera, added), true))
    }

    @Test
    fun doesNotShowStaleCameraFilesWhenDisconnected() {
        assertEquals(emptyList<CameraFile>(), cameraFiles(listOf(file("/DCIM/Camera01/VID_1.mp4")), false))
    }
}
