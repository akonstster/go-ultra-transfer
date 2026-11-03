package app.goultra.transfer.ui

internal data class RadioHints(val bluetooth: Boolean, val wifi: Boolean, val vpn: Boolean)

internal fun radioHints(radios: PhoneRadios, waiting: Boolean, radioHidden: Boolean, vpnHidden: Boolean, linkDown: Boolean): RadioHints =
    RadioHints(
        bluetooth = waiting && !radioHidden && !radios.bluetooth,
        wifi = waiting && !radioHidden && !radios.wifi,
        // Some VPNs let local traffic through, so this is a hint, not a blocker: only while the camera link is down
        // (idle or a failed attempt) — not while it is connecting, connected or working.
        vpn = radios.vpn && !vpnHidden && linkDown,
    )
