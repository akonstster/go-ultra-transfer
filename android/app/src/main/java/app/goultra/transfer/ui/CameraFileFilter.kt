package app.goultra.transfer.ui

import app.goultra.transfer.camera.CameraFile

internal fun cameraFiles(files: List<CameraFile>, connected: Boolean): List<CameraFile> =
    if (connected) files.filter { it.uri.startsWith("/DCIM/") } else emptyList()
