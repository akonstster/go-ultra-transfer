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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

    /** Phones set to a language of the Russian-speaking region get Russian, as they always did. */
    private val RUSSIAN_REGION = setOf("ru", "be", "uk", "kk")

    var theme by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var language by mutableStateOf(LanguageMode.SYSTEM)
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        val prefs = context.getSharedPreferences(FILE, 0)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        language = runCatching { LanguageMode.valueOf(prefs.getString("language", null)!!) }.getOrDefault(LanguageMode.SYSTEM)
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        context?.getSharedPreferences(FILE, 0)?.edit()?.putString("theme", mode.name)?.apply()
    }

    fun chooseLanguage(mode: LanguageMode) {
        language = mode
        context?.getSharedPreferences(FILE, 0)?.edit()?.putString("language", mode.name)?.apply()
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

/** Menu ⋮ → Settings: the theme as one-tap choices, the language as a dropdown list. */
@Composable
fun AppSettingsDialog(onDismiss: () -> Unit) {
    var languagesOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Настройки", "Settings")) },
        text = {
            Column {
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
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Готово", "Done")) } },
    )
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
