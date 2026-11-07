package app.goultra.transfer.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One release of this project on GitHub, as the update check sees it. */
data class UpdateInfo(
    val tag: String,
    val name: String,
    val pageUrl: String,
    val apkUrl: String?,
    val prerelease: Boolean,
)

/**
 * The in-app update check: the app asks its own repository on GitHub and offers a newer build of its channel.
 *
 * The branch matters. A version with a dash (`0.17-alpha3`) is a build of the `universal` branch, and GitHub keeps
 * those as **pre-releases**; a version without one (`0.16`) is the stable line. So an alpha build looks at
 * pre-releases *and* at a newer stable, while a stable build only ever sees stable ones. A stable release of the
 * same 0.x line beats its alphas (0.17 > 0.17-alpha3).
 */
object UpdateCheck {
    private const val API = "https://api.github.com/repos/akonstster/go-ultra-transfer/releases?per_page=30"

    /** An alpha (any dash-carrying) build: the `universal` channel. */
    fun isAlpha(version: String): Boolean = version.contains('-')

    /**
     * A version as one comparable number: `0.16` → (0, 16, stable), `0.17-alpha3` → (0, 17, alpha 3), `0.18` → …
     * The stable flag sits above the alpha number, so a stable release wins over an alpha of the same line.
     */
    fun score(version: String): Long {
        // Letters for the channel and digits for its number: `\w+` would swallow the digit and make alpha2 == alpha3.
        val m = Regex("""(\d+)\.(\d+)(?:-([A-Za-z]+)(\d+)?)?""").find(version.removePrefix("v")) ?: return 0L
        val major = (m.groupValues[1].toIntOrNull() ?: 0).toLong()
        val minor = (m.groupValues[2].toIntOrNull() ?: 0).toLong()
        val stable = if (m.groupValues[3].isEmpty()) 1L else 0L // a dash means a pre-release
        val number = (m.groupValues[4].toIntOrNull() ?: 0).toLong()
        return (major shl 40) or (minor shl 24) or (stable shl 16) or number
    }

    fun isNewer(current: String, candidate: String): Boolean = score(candidate) > score(current)

    /** The release worth offering to [current], or null when nothing of its channel is newer. */
    fun best(releases: List<UpdateInfo>, current: String, alpha: Boolean): UpdateInfo? =
        releases.filter { alpha || !it.prerelease }
            .maxByOrNull { score(it.tag) }
            ?.takeIf { isNewer(current, it.tag) }

    /** `GET /releases` — the releases of the repository (no token needed: the repo is public). Null when offline. */
    suspend fun fetch(): List<UpdateInfo>? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "go-ultra-transfer-update-check")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                parse(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** The releases of a `GET /releases` body (an unexpected shape gives an empty list). */
    fun parse(json: String): List<UpdateInfo> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val tag = o.optString("tag_name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it)?.optString("browser_download_url")?.takeIf { u -> u.endsWith(".apk") } }
                .firstOrNull()
            UpdateInfo(
                tag = tag,
                name = o.optString("name"),
                pageUrl = o.optString("html_url"),
                apkUrl = apk,
                prerelease = o.optBoolean("prerelease"),
            )
        }
    }
}