package app.goultra.transfer.ui

/**
 * The text tables of the interface languages, by language code. The key is the **English** string exactly as it stands
 * in the `tr(ru, en)` call, so a translation can be added or corrected without touching the call sites; a key that is
 * missing falls back to English. Russian and English need no table — they are the two literals in the calls themselves.
 */
internal val TABLES: Map<String, Map<String, String>> = mapOf(
    "fr" to TEXT_FR,
    "it" to TEXT_IT,
    "es" to TEXT_ES,
    "de" to TEXT_DE,
    "pt" to TEXT_PT,
    "tr" to TEXT_TR,
    "zh" to TEXT_ZH,
    "ja" to TEXT_JA,
)
