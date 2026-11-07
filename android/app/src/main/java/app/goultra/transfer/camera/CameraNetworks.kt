package app.goultra.transfer.camera

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket

/**
 * Finds an Insta360 camera's access point among the phone's networks: every Insta360 AP hands out addresses in
 * `192.168.42.0/24` (the camera answers at `192.168.42.1`), so no per-model knowledge is needed to reach it. Used by
 * the «other cameras» screen and by the GO-family manual Wi-Fi fallback (the user joined the camera's network by hand).
 */
object CameraNetworks {
    /** The subnet of every Insta360 access point (the camera itself is `192.168.42.1`). */
    const val CAMERA_SUBNET = "192.168.42."

    /** The phone's Wi-Fi network that looks like a camera access point, or null. */
    fun find(cm: ConnectivityManager): Network? {
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return all
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .firstOrNull { isCameraSubnet(cm.getLinkProperties(it)) }
    }

    fun isCameraSubnet(lp: LinkProperties?): Boolean =
        lp?.linkAddresses?.any { it.address.hostAddress?.startsWith(CAMERA_SUBNET) == true } == true

    /**
     * Wraps an already-joined [network] into a [CameraLink]. Right after connecting some firmwares (Huawei EMUI)
     * refuse to bind sockets, so the whole process is bound instead — [boundProcess] says which happened, and
     * [unbind] must be called when the link is dropped.
     */
    fun adopt(cm: ConnectivityManager, network: Network): Adoption {
        val bindable = runCatching { DatagramSocket().use { network.bindSocket(it) } }.isSuccess
        val bound = if (bindable) false else cm.bindProcessToNetwork(network)
        return Adoption(CameraLink(network, processBound = !bindable), bound, bindable)
    }

    /** Undoes [adopt]'s process binding. */
    fun unbind(cm: ConnectivityManager) {
        runCatching { cm.bindProcessToNetwork(null) }
    }

    data class Adoption(val link: CameraLink, val boundProcess: Boolean, val socketsBindable: Boolean)
}
