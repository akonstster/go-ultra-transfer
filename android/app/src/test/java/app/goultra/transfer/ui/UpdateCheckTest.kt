package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check: version ordering (a stable release beats an alpha of the same line), the channel filter (an
 * alpha build sees pre-releases, a stable build only stables) and the GitHub JSON parse.
 */
class UpdateCheckTest {
    private fun rel(tag: String, prerelease: Boolean) =
        UpdateInfo(tag, "", "https://github.com/x/y/releases/tag/$tag", null, prerelease)

    @Test
    fun `a stable release beats an alpha of the same line`() {
        assertTrue(UpdateCheck.isNewer("0.17-alpha2", "0.17-alpha3"))
        assertTrue(UpdateCheck.isNewer("0.17-alpha3", "0.17"))
        assertTrue(UpdateCheck.isNewer("0.16", "0.17-alpha3"))
        assertTrue(UpdateCheck.isNewer("0.17-alpha3", "0.18"))
        assertFalse(UpdateCheck.isNewer("0.17-alpha3", "0.17-alpha3"))
        assertFalse(UpdateCheck.isNewer("0.18", "0.17-alpha3"))
        assertFalse(UpdateCheck.isNewer("0.17", "0.17-alpha3"))
    }

    @Test
    fun `an alpha sees pre-releases, a stable build only stables`() {
        val releases = listOf(rel("v0.17-alpha2", true), rel("v0.17-alpha3", true), rel("v0.16", false))
        // The alpha build is offered the next alpha of its line…
        assertEquals("v0.17-alpha3", UpdateCheck.best(releases, "0.17-alpha2", alpha = true)?.tag)
        // …while a stable build ignores pre-releases entirely (0.16 is its own latest).
        assertNull(UpdateCheck.best(releases, "0.16", alpha = false))
        // A newer stable also reaches an alpha build.
        assertEquals("v0.18", UpdateCheck.best(releases + rel("v0.18", false), "0.17-alpha3", alpha = true)?.tag)
        // Nothing newer anywhere: no dialog.
        assertNull(UpdateCheck.best(releases, "0.17-alpha3", alpha = true))
    }

    @Test
    fun `the release list parses into the fields the dialog needs`() {
        val json = """
            [
              {"tag_name":"v0.17-alpha3","name":"v0.17-alpha3 — alpha","html_url":"https://github.com/x/y/releases/tag/v0.17-alpha3",
               "prerelease":true,"assets":[{"name":"GoUltraTransfer-0.17-alpha3.apk","browser_download_url":"https://github.com/x/y/releases/download/v0.17-alpha3/GoUltraTransfer-0.17-alpha3.apk"}]},
              {"tag_name":"v0.16","name":"v0.16","html_url":"https://github.com/x/y/releases/tag/v0.16","prerelease":false,"assets":[]}
            ]
        """.trimIndent()
        val list = UpdateCheck.parse(json)
        assertEquals(2, list.size)
        assertEquals("v0.17-alpha3", list[0].tag)
        assertTrue(list[0].prerelease)
        assertEquals("https://github.com/x/y/releases/download/v0.17-alpha3/GoUltraTransfer-0.17-alpha3.apk", list[0].apkUrl)
        assertNull(list[1].apkUrl)
        assertTrue(UpdateCheck.parse("[{]").isEmpty())
    }
}