package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioHintsTest {
    @Test
    fun vpnIsShownWithoutWaitingForRadios() {
        assertEquals(
            RadioHints(bluetooth = false, wifi = false, vpn = true),
            radioHints(PhoneRadios(bluetooth = true, wifi = true, vpn = true), false, false, false),
        )
    }

    @Test
    fun dismissingRadioHintsDoesNotHideVpn() {
        assertEquals(
            RadioHints(bluetooth = false, wifi = false, vpn = true),
            radioHints(PhoneRadios(bluetooth = false, wifi = false, vpn = true), true, true, false),
        )
    }

    @Test
    fun dismissingVpnDoesNotHideRadioHints() {
        assertEquals(
            RadioHints(bluetooth = true, wifi = true, vpn = false),
            radioHints(PhoneRadios(bluetooth = false, wifi = false, vpn = true), true, false, true),
        )
    }

    @Test
    fun absentVpnAndRadiosNotAwaitedShowNothing() {
        assertEquals(
            RadioHints(bluetooth = false, wifi = false, vpn = false),
            radioHints(PhoneRadios(bluetooth = false, wifi = false, vpn = false), false, false, false),
        )
    }
}
