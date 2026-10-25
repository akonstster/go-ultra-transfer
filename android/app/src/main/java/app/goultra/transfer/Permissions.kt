package app.goultra.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions for BLE (scan/connect) and joining the camera Wi-Fi. */
fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION) // BLE scan before Android 12, Wi-Fi specifier before Android 13
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

/** Required ones plus the download progress notification (optional). */
fun requestedPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) requiredPermissions() + Manifest.permission.POST_NOTIFICATIONS
    else requiredPermissions()

fun Context.hasPermissions(): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}
