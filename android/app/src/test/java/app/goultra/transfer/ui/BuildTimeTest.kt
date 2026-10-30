package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class BuildTimeTest {
    @Test
    fun formatsMillisecondsInPhoneTimeZone() {
        assertEquals("01.01.1970 03:00:00.123 +03:00", formatBuildTime(123, ZoneId.of("+03:00")))
        assertEquals("31.12.1969 19:00:00.123 -05:00", formatBuildTime(123, ZoneId.of("-05:00")))
    }
}
