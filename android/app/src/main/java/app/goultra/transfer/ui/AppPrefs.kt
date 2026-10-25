package app.goultra.transfer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LanguageMode { SYSTEM, RU, EN }

/**
 * Appearance settings (menu ⋮ → «Настройки»). Snapshot state: changing them redraws the open screens at once,
 * no activity restart.
 */
object AppPrefs {
    private const val FILE = "ui"
    private var context: Context? = null

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

    /** Russian for RU, and in «system» mode for phones set to a language of the Russian-speaking region. */
    val russian: Boolean
        get() = when (language) {
            LanguageMode.RU -> true
            LanguageMode.EN -> false
            LanguageMode.SYSTEM -> Locale.getDefault().language in setOf("ru", "be", "uk", "kk")
        }
}

/** Menu ⋮ → Settings: theme and language, each a one-tap choice applied at once. */
@Composable
fun AppSettingsDialog(onDismiss: () -> Unit) {
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
                ChoiceRow(
                    listOf(
                        LanguageMode.SYSTEM to tr("Как в телефоне", "System"),
                        LanguageMode.RU to "Русский",
                        LanguageMode.EN to "English",
                    ),
                    AppPrefs.language,
                    AppPrefs::chooseLanguage,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Готово", "Done")) } },
    )
}

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

/** The interface text in the chosen language. Read from composables, a language change recomposes them. */
fun tr(ru: String, en: String): String = if (AppPrefs.russian) ru else en
