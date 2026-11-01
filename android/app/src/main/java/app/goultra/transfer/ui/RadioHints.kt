package app.goultra.transfer.ui

internal data class RadioHints(val bluetooth: Boolean, val wifi: Boolean, val vpn: Boolean)

internal fun radioHints(radios: PhoneRadios, waiting: Boolean, radioHidden: Boolean, vpnHidden: Boolean): RadioHints =
    RadioHints(
        bluetooth = waiting && !radioHidden && !radios.bluetooth,
        wifi = waiting && !radioHidden && !radios.wifi,
        vpn = radios.vpn && !vpnHidden,
    )
