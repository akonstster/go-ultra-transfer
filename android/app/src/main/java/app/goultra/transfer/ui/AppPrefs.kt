package app.goultra.transfer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The interface languages. [SYSTEM] follows the phone; every other one carries its code ([tag], for the text table
 * and for date/number formats) and its own name in that language ([label], so a user can find it in the list).
 */
enum class LanguageMode(val tag: String?, val label: String) {
    SYSTEM(null, ""),
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    SPANISH("es", "Español"),
    GERMAN("de", "Deutsch"),
    PORTUGUESE("pt", "Português"),
    TURKISH("tr", "Türkçe"),
    CHINESE("zh", "中文（简体）"),
    JAPANESE("ja", "日本語"),
}

/**
 * Appearance settings (menu ⋮ → «Настройки»). Snapshot state: changing them redraws the open screens at once,
 * no activity restart.
 */
object AppPrefs {
    private const val FILE = "ui"
    private var context: Context? = null

    /** Languages with a text table of their own (the rest fall back to English). */
    private val SUPPORTED = setOf("ru", "en", "fr", "it", "es", "de", "pt", "tr", "zh", "ja")

    /** Proxy cache sizes offered in Settings, MB. Stored in MB so a half-gigabyte choice stays exact. */
    val PREVIEW_CACHE_MB = listOf(512, 1024, 2048, 4096, 8192)
    const val PREVIEW_CACHE_DEFAULT_MB = 2048

    /** Phones set to a language of the Russian-speaking region get Russian, as they always did. */
    private val RUSSIAN_REGION = setOf("ru", "be", "uk", "kk")

    var theme by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var language by mutableStateOf(LanguageMode.SYSTEM)
        private set

    /** The click on every beat in a project's preview: on or off, kept between launches. */
    var metronome by mutableStateOf(false)
        private set

    /** The most disk space the low-resolution preview copies may take, MB (kept between launches). */
    var previewCacheMb by mutableStateOf(PREVIEW_CACHE_DEFAULT_MB)
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        val prefs = context.getSharedPreferences(FILE, 0)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        language = runCatching { LanguageMode.valueOf(prefs.getString("language", null)!!) }.getOrDefault(LanguageMode.SYSTEM)
        metronome = prefs.getBoolean("metronome", false)
        previewCacheMb = prefs.getInt("previewCacheMb", PREVIEW_CACHE_DEFAULT_MB)
            .takeIf { it in PREVIEW_CACHE_MB } ?: PREVIEW_CACHE_DEFAULT_MB
    }

    fun chooseMetronome(on: Boolean) {
        metronome = on
        context?.getSharedPreferences(FILE, 0)?.edit()?.putBoolean("metronome", on)?.apply()
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        context?.getSharedPreferences(FILE, 0)?.edit()?.putString("theme", mode.name)?.apply()
    }

    fun chooseLanguage(mode: LanguageMode) {
        language = mode
        context?.getSharedPreferences(FILE, 0)?.edit()?.putString("language", mode.name)?.apply()
    }

    /** One of [PREVIEW_CACHE_MB]; anything else keeps the current choice. */
    fun choosePreviewCacheMb(mb: Int) {
        if (mb !in PREVIEW_CACHE_MB) return
        previewCacheMb = mb
        context?.getSharedPreferences(FILE, 0)?.edit()?.putInt("previewCacheMb", mb)?.apply()
    }

    /** The language in use: the chosen one, or the phone's (Russian for the Russian-speaking region, else English). */
    val languageTag: String
        get() {
            val chosen = language
            if (chosen != LanguageMode.SYSTEM) return chosen.tag!!
            val phone = Locale.getDefault().language.lowercase()
            return when {
                phone in SUPPORTED -> phone
                phone in RUSSIAN_REGION -> "ru"
                else -> "en"
            }
        }

    /** The interface text in the current language, by the English string as its key (English itself when unknown). */
    fun text(en: String): String = TABLES[languageTag]?.get(en) ?: en
}

/** Menu ⋮ → Settings: the theme as one-tap choices, the language as a dropdown list, the preview cache. */
@Composable
fun AppSettingsDialog(vm: CameraController, onDismiss: () -> Unit) {
    var languagesOpen by remember { mutableStateOf(false) }
    var cacheSizesOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshPreviewCacheUsage() }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Очистить кэш превью?", "Clear the preview cache?")) },
            text = {
                Text(
                    tr(
                        "Удалить облегчённые копии и загруженные фрагменты? Оригиналы и проекты не пострадают.",
                        "Delete the light copies and fetched fragments? Originals and projects stay untouched.",
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    vm.clearPreviewCaches()
                }) { Text(tr("Очистить", "Clear")) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Настройки", "Settings")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("Тема", "Theme"), style = MaterialTheme.typography.titleSmall)
                ChoiceRow(
                    listOf(
                        ThemeMode.SYSTEM to tr("Как в телефоне", "System"),
                        ThemeMode.LIGHT to tr("Светлая", "Light"),
                        ThemeMode.DARK to tr("Тёмная", "Dark"),
                    ),
                    AppPrefs.theme,
                    AppPrefs::chooseTheme,
                )
                Spacer(Modifier.height(16.dp))
                Text(tr("Язык", "Language"), style = MaterialTheme.typography.titleSmall)
                Box(Modifier.padding(top = 6.dp)) {
                    OutlinedButton({ languagesOpen = true }, Modifier.fillMaxWidth()) {
                        Text(languageLabel(AppPrefs.language))
                    }
                    DropdownMenu(expanded = languagesOpen, onDismissRequest = { languagesOpen = false }) {
                        LanguageMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(languageLabel(mode)) },
                                onClick = { AppPrefs.chooseLanguage(mode); languagesOpen = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(tr("Кэш предпросмотра", "Preview cache"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(
                        "Для плавного воспроизведения больших видео.",
                        "For smooth playback of large videos.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.padding(top = 6.dp)) {
                    OutlinedButton({ cacheSizesOpen = true }, Modifier.fillMaxWidth()) {
                        Text(cacheSize(AppPrefs.previewCacheMb))
                    }
                    DropdownMenu(expanded = cacheSizesOpen, onDismissRequest = { cacheSizesOpen = false }) {
                        AppPrefs.PREVIEW_CACHE_MB.forEach { mb ->
                            DropdownMenuItem(
                                text = { Text(cacheSize(mb)) },
                                onClick = {
                                    AppPrefs.choosePreviewCacheMb(mb)
                                    vm.trimPreviewCache()
                                    cacheSizesOpen = false
                                },
                            )
                        }
                    }
                }
                Text(
                    tr("Облегчённые копии: %s из %s", "Light copies: %s of %s")
                        .format(bytesText(vm.previewCacheUsedBytes), cacheSize(AppPrefs.previewCacheMb)),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = !vm.previewCacheClearing && vm.export == null && vm.download == null,
                ) {
                    Text(
                        if (vm.previewCacheClearing) tr("Очищаю…", "Clearing…")
                        else tr("Очистить кэш превью", "Clear the preview cache"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Готово", "Done")) } },
    )
}

/** A cache size as the user picked it: half a gigabyte, or whole gigabytes. */
private fun cacheSize(mb: Int): String {
    val value = when {
        mb % 1024 == 0 -> "${mb / 1024}"
        AppPrefs.languageTag == "ru" -> "0,5"
        else -> "0.5"
    }
    return "$value " + tr("ГБ", "GB")
}

/** Bytes as megabytes below a gigabyte, else gigabytes with one decimal, in the language's decimal separator. */
private fun bytesText(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val (value, unit) = if (mb < 1024.0) {
        String.format(Locale.ROOT, "%.1f", mb) to tr("МБ", "MB")
    } else {
        String.format(Locale.ROOT, "%.1f", mb / 1024.0) to tr("ГБ", "GB")
    }
    return (if (AppPrefs.languageTag == "ru") value.replace('.', ',') else value) + " " + unit
}

/** «Как в телефоне» for [LanguageMode.SYSTEM], else the language's own name (never translated). */
private fun languageLabel(mode: LanguageMode) =
    if (mode == LanguageMode.SYSTEM) tr("Как в телефоне", "System") else mode.label

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = { onPick(value) })
                Text(label)
            }
        }
    }
}

/**
 * The interface text in the chosen language. Russian and English are the two literals right here; every other language
 * is looked up by the English one in its table (`TextXx.kt`), falling back to English while a string isn't translated
 * yet. Read from composables, a language change recomposes them.
 */
fun tr(ru: String, en: String): String = when (AppPrefs.languageTag) {
    "ru" -> ru
    "en" -> en
    else -> AppPrefs.text(en)
}

/**
 * An interface phrase kept as its two source literals: [text] is read **where the phrase is shown**, so a phrase put
 * into state (a connection step, an error message) follows a language change. A ready-made `tr(...)` result kept in
 * state does not — that is how the camera's idle-disconnect message stayed Russian after the language was switched.
 *
 * [format] and [plus] are for values that need no translation (`%s` placeholders, an exception's own text).
 */
class Phrase(val ru: String, val en: String, private val args: Array<out Any?>? = null) {
    /** The phrase in the current language, with [args] filled into its `%s` placeholders. */
    val text: String get() = if (args == null) tr(ru, en) else tr(ru, en).format(*args)

    /** The phrase with the values for its `%s` placeholders, read later, in whatever language is current then. */
    fun format(vararg values: Any?): Phrase = Phrase(ru, en, values)

    /** The phrase with an untranslatable tail (an exception message, a number with a unit). */
    operator fun plus(suffix: String?): Phrase = Phrase(ru + suffix.orEmpty(), en + suffix.orEmpty())

    override fun toString(): String = text
}

