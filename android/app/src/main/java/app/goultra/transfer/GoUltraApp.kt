package app.goultra.transfer

import android.app.Application
import android.content.Context
import app.goultra.transfer.ui.CameraController

class GoUltraApp : Application() {
    val controller by lazy { CameraController(this) }
}

val Context.cameraController: CameraController get() = (applicationContext as GoUltraApp).controller
