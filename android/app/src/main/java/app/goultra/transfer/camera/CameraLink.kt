package app.goultra.transfer.camera

import android.net.Network
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * How traffic reaches the camera network. Normally every socket is bound to [network];
 * on phones where that fails with EPERM (seen on Huawei EMUI 15) the whole process is bound instead.
 */
class CameraLink(val network: Network, private val processBound: Boolean) {
    fun openConnection(url: URL): URLConnection =
        if (processBound) url.openConnection() else network.openConnection(url)

    fun createSocket(): Socket =
        if (processBound) Socket() else network.socketFactory.createSocket()
}
