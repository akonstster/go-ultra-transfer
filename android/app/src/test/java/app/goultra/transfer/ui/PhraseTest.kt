package app.goultra.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A phrase kept in state (a connection step, an error message) must read in whatever language is chosen **when it is
 * shown**, not when it was built: the «disconnected to save the camera's battery» message used to stay Russian after
 * the interface language was switched, because a ready-made `tr(...)` result was kept in the state.
 */
class PhraseTest {
    /** Runs [body] in [mode], leaving the chosen language as it was. */
    private fun withLanguage(mode: LanguageMode, body: () -> Unit) {
        val was = AppPrefs.language
        try {
            AppPrefs.chooseLanguage(mode)
            body()
        } finally {
            AppPrefs.chooseLanguage(was)
        }
    }

    @Test
    fun textFollowsTheLanguageChosenAfterItWasBuilt() {
        val phrase = Phrase("Подготовка…", "Preparing…")
        withLanguage(LanguageMode.RUSSIAN) { assertEquals("Подготовка…", phrase.text) }
        withLanguage(LanguageMode.ENGLISH) { assertEquals("Preparing…", phrase.text) }
        // A language that has a table of its own (neither of the two literals): the phrase goes through the table.
        withLanguage(LanguageMode.SPANISH) {
            assertEquals(TABLES.getValue("es").getValue("Preparing…"), phrase.text)
        }
    }

    @Test
    fun formatAndPlusKeepTheirValuesInTheCurrentLanguage() {
        val step = Phrase("Камера%s не найдена", "Camera%s not found").format(" AB12CD")
        withLanguage(LanguageMode.RUSSIAN) { assertEquals("Камера AB12CD не найдена", step.text) }
        withLanguage(LanguageMode.ENGLISH) { assertEquals("Camera AB12CD not found", step.text) }

        val failure = Phrase("Ошибка: ", "Error: ") + "boom"
        withLanguage(LanguageMode.RUSSIAN) { assertEquals("Ошибка: boom", failure.text) }
        withLanguage(LanguageMode.ENGLISH) { assertEquals("Error: boom", failure.text) }
    }
}
