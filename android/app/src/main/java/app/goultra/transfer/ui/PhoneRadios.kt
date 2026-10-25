package app.goultra.transfer.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Phone-side conditions for the camera link: Bluetooth (always), Wi-Fi (files, live view), no VPN (camera Wi-Fi). */
data class PhoneRadios(val bluetooth: Boolean, val wifi: Boolean, val vpn: Boolean) {
    companion object {
        fun check(context: Context): PhoneRadios {
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            val wifi = context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            val cm = context.getSystemService(ConnectivityManager::class.java)
            // activeNetwork is per app: a VPN that excludes this app (split tunneling) does not show up here.
            val vpn = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            return PhoneRadios(bt, wifi, vpn)
        }

        /** Calls [onChange] whenever Bluetooth or Wi-Fi is switched or a VPN comes up / goes away. */
        fun watch(context: Context, onChange: () -> Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onChange()
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                vpnRequest,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onChange()
                    override fun onLost(network: Network) = onChange()
                },
            )
        }

        /** System dialog «Разрешить включить Bluetooth?» (an app may ask, but not switch it on silently). */
        fun bluetoothIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

        /** Wi-Fi panel over the app (Android 10+ apps cannot switch Wi-Fi on themselves). */
        fun wifiIntent() = Intent(Settings.Panel.ACTION_WIFI)

        fun wifiSettingsIntent() = Intent(Settings.ACTION_WIFI_SETTINGS)

        fun vpnIntent() = Intent(Settings.ACTION_VPN_SETTINGS)
    }
}

/**
 * What blocks the camera link on the phone side, with one-tap fixes: Bluetooth / Wi-Fi off (shown while a
 * connection waits for them — it continues by itself once they are on) and an active VPN.
 */
@Composable
fun PhoneRadiosBanner(vm: CameraController) {
    val context = LocalContext.current
    fun open(vararg intents: Intent) {
        // Some firmwares lack the Wi-Fi panel: fall back to the next intent.
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
    val radios = vm.radios
    val waiting = vm.waitingForRadios
    // Some VPNs let local traffic through, so this is a hint, not a blocker: only while not connected.
    val vpn = radios.vpn && (vm.conn == ConnState.Idle || vm.conn is ConnState.Failed)
    if (!waiting && !vpn) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (waiting && !radios.bluetooth) {
            RadioCard(
                icon = Icons.Filled.BluetoothDisabled,
                text = tr("Bluetooth выключен — без него камеру не найти и не разбудить.", "Bluetooth is off — without it the camera can't be found or woken up."),
                action = tr("Включить", "Turn on"),
            ) { open(PhoneRadios.bluetoothIntent()) }
        }
        if (waiting && !radios.wifi) {
            RadioCard(
                icon = Icons.Filled.WifiOff,
                text = tr(
                    "Wi-Fi выключен — он нужен для файлов и трансляции (интернет при этом не пропадёт).",
                    "Wi-Fi is off — it's needed for files and the live view (your internet keeps working).",
                ),
                action = tr("Включить", "Turn on"),
            ) { open(PhoneRadios.wifiIntent(), PhoneRadios.wifiSettingsIntent()) }
        }
        if (vpn) {
            RadioCard(
                icon = Icons.Filled.VpnLock,
                text = tr(
                    "Включён VPN. Если камера не подключается по Wi-Fi — отключите его на время работы " +
                        "с камерой или добавьте это приложение в исключения VPN.",
                    "A VPN is on. If the camera doesn't connect over Wi-Fi, turn the VPN off while using the camera " +
                        "or exclude this app in the VPN settings.",
                ),
                action = "VPN",
            ) { open(PhoneRadios.vpnIntent(), Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }
}

@Composable
private fun RadioCard(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick) { Text(action) }
        }
    }
}
