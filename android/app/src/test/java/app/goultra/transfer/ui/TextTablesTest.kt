package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every table must translate exactly the same strings as the others. A key that doesn't match the English literal of a
 * `tr(ru, en)` call falls back to English silently, so the tables are compared with each other, the French one being
 * the reference: a missing or misspelled line then fails the build instead of quietly showing English.
 */
class TextTablesTest {
    @Test
    fun everyTableCoversTheSameStrings() {
        val reference = TEXT_FR.keys
        assertTrue("the French table looks empty: ${reference.size} strings", reference.size > 300)
        TABLES.forEach { (tag, table) ->
            val missing = reference - table.keys
            assertTrue("$tag is missing ${missing.size} strings, e.g. ${missing.take(3)}", missing.isEmpty())
            val extra = table.keys - reference
            assertTrue("$tag has ${extra.size} strings the others don't: ${extra.take(3)}", extra.isEmpty())
        }
    }

    @Test
    fun noValueIsLeftEmpty() {
        TABLES.forEach { (tag, table) ->
            val empty = table.filterValues { it.isBlank() }.keys
            assertTrue("$tag has ${empty.size} empty values, e.g. ${empty.take(3)}", empty.isEmpty())
        }
    }

    @Test
    fun everyOfferedLanguageHasATable() {
        // A language in the settings without a table would silently show English.
        val offered = LanguageMode.entries.mapNotNull { it.tag }.filter { it != "ru" && it != "en" }
        assertEquals(offered.sorted(), TABLES.keys.sorted())
    }

    /** These strings go through `String.format`, so a translation that drops or adds a `%s` loses the value (or throws). */
    @Test
    fun placeholdersSurviveTranslation() {
        val placeholder = Regex("%s")
        val stray = Regex("(?<!%)%(?!s|%)")
        TABLES.forEach { (tag, table) ->
            table.forEach { (en, value) ->
                // Only strings that go through String.format have to keep their placeholders; the rest may say «70 %».
                if (!placeholder.containsMatchIn(en)) return@forEach
                assertEquals(
                    "$tag: '$en' has ${placeholder.findAll(en).count()} placeholders, '$value' has ${placeholder.findAll(value).count()}",
                    placeholder.findAll(en).count(), placeholder.findAll(value).count(),
                )
                assertTrue("$tag: '$value' has a bare % that String.format would reject", !stray.containsMatchIn(value))
            }
        }
    }
}
