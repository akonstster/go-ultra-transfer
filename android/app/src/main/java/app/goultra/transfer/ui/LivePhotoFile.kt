package app.goultra.transfer.ui

import app.goultra.transfer.camera.CameraFile

internal fun currentLivePhoto(file: CameraFile, localUri: String?, size: Long?): CameraFile =
    if (localUri != null && size != null) file.copy(localUri = localUri, size = size) else file

internal fun canRestoreLiveCover(
    hasLocalCopy: Boolean,
    originalSize: Long,
    originalPhotoTracked: Boolean,
    previouslyReplaced: Boolean,
): Boolean = hasLocalCopy && (originalSize > 0 || (!originalPhotoTracked && previouslyReplaced))
