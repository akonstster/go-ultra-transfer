package app.goultra.transfer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tables are keyed by the **English literal** of a `tr(ru, en)` or `Phrase(ru, en)` call, so a key that doesn't match the source
 * silently falls back to English. This test reads the UI sources and checks both directions: every translatable
 * literal has a line in every table, and no table line is left over after a string is reworded. Strings built at run
 * time (`"… $value"`) can never be a key and are skipped.
 */
class TextTablesMatchSourcesTest {
    @Test
    fun everyTranslatableLiteralIsInEveryTable() {
        val literals = literals()
        assertTrue("no tr() strings found in the sources — the scan is broken", literals.size > 300)
        TABLES.forEach { (tag, table) ->
            val missing = literals - table.keys
            assertTrue("$tag is missing ${missing.size} strings, e.g. ${missing.take(3)}", missing.isEmpty())
        }
    }

    @Test
    fun noTableKeyIsLeftOver() {
        val literals = literals()
        TABLES.forEach { (tag, table) ->
            val dead = table.keys - literals
            assertTrue("$tag translates ${dead.size} strings the sources don't use: ${dead.take(3)}", dead.isEmpty())
        }
    }

    private fun literals(): Set<String> {
        val root = File("src/main/java")
        assertTrue("sources not found at ${root.absolutePath}", root.isDirectory)
        val sources = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .sortedBy { it.path }.joinToString("\n") { it.readText() }
        val found = mutableSetOf<String>()
        for (name in listOf("tr(", "Phrase(")) {
            var at = sources.indexOf(name)
            while (at >= 0) {
                val before = sources.getOrNull(at - 1)
                if (before == null || !(before.isLetterOrDigit() || before == '_')) {
                    val args = argumentsOf(sources, at + name.length)
                    if (args.size >= 2) literalOf(args[1])?.let { found += it }
                }
                at = sources.indexOf(name, at + name.length)
            }
        }
        // The «Стиль» presets reach tr() as enum values (Look.en / Look.aboutEn), so the tr() scan can't see them: they
        // come from Looks.kt instead. Only the English arguments — the Russian ones are the source text, not keys.
        File(root, "app/goultra/transfer/camera/Looks.kt").takeIf { it.isFile }?.let {
            found += lookLiterals(it.readText())
        }
        // The camera menu's tiles reach tr() as constructor arguments — SettingSpec(param, ru, en, …) and
        // SettingGroup(ru, en, …) — and the «i» rows as add(ru, en, …); the tr() scan above can't see them.
        found += argumentLiterals(sources, "SettingSpec(", 2)
        found += argumentLiterals(sources, "SettingGroup(", 1)
        File(root, "app/goultra/transfer/ui/CameraController.kt").takeIf { it.isFile }?.let { file ->
            val text = file.readText()
            if ("fun add(ru: String, en: String" in text) found += argumentLiterals(text, "add(", 1)
        }
        return found
    }

    /** The English argument of every call to [name] in [source] — for the labels tr() gets as arguments. */
    private fun argumentLiterals(source: String, name: String, index: Int): Set<String> {
        val out = mutableSetOf<String>()
        var at = source.indexOf(name)
        while (at >= 0) {
            val before = source.getOrNull(at - 1)
            if (before == null || !(before.isLetterOrDigit() || before == '_')) {
                val args = argumentsOf(source, at + name.length)
                if (args.size > index) literalOf(args[index])?.let { out += it }
            }
            at = source.indexOf(name, at + name.length)
        }
        return out
    }

    /** The English strings of the «Стиль» presets: the `en` and `aboutEn` arguments of the Look enum's entries. */
    private fun lookLiterals(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val at = text.indexOf("enum class Look(")
        if (at < 0) return out
        val body = joinConcatenations(text.substring(at))
        var i = 0
        while (i < body.length) {
            if (body[i] != '(') { i++; continue }
            var k = i - 1
            while (k >= 0 && (body[k].isLetterOrDigit() || body[k] == '_')) k--
            val name = body.substring(k + 1, i)
            if (name.isNotEmpty() && name.all { it.isUpperCase() || it == '_' }) {
                val args = argumentsOf(body, i + 1)
                if (args.size >= 4) {
                    literalOf(args[1])?.takeIf { it.isNotBlank() }?.let { out += it }
                    literalOf(args[3])?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
            i++
        }
        return out
    }

    /** Merges "a" + "b" into "ab", so a string split over lines matches what the code builds at run time. */
    private fun joinConcatenations(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] != '"') { out.append(text[i]); i++; continue }
            val end = endOfLiteral(text, i)
            out.append(text, i, end)
            var j = end
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '+' && j + 1 < text.length) {
                j++
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && text[j] == '"') {
                    val next = endOfLiteral(text, j)
                    out.setLength(out.length - 1)          // drop the closing quote just written
                    out.append(text, j + 1, next - 1)      // and the next literal's contents
                    out.append('"')
                    i = next
                    continue
                }
            }
            i = end
        }
        return out.toString()
    }

    /** The argument texts of the call whose `(` sits just before [opening], split on its top-level commas. */
    private fun argumentsOf(source: String, from: Int): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = from
        while (i < source.length) {
            val c = source[i]
            if (c == '"') {
                val end = endOfLiteral(source, i)
                current.append(source, i, end); i = end; continue
            }
            when {
                c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                c == ')' && depth == 0 -> { args += current.toString(); return args }
                c == ')' || c == ']' || c == '}' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        return args
    }

    /** The text of a argument made of literals only, or null when it is built at run time or is not a literal. */
    private fun literalOf(argument: String): String? {
        val text = StringBuilder()
        var i = 0
        var any = false
        while (i < argument.length) {
            if (argument[i] == '"') {
                val end = endOfLiteral(argument, i)
                text.append(unescape(argument.substring(i + 1, end - 1)))
                any = true
                i = end
            } else i++
        }
        if (!any) return null
        val value = text.toString()
        return if ('$' in value) null else value        // built at run time — it can never be a table key
    }

    private fun endOfLiteral(source: String, quoteAt: Int): Int {
        var i = quoteAt + 1
        while (i < source.length) {
            if (source[i] == '\\') { i += 2; continue }
            if (source[i] == '"') return i + 1
            i++
        }
        return source.length
    }

    private fun unescape(literal: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c == '\\' && i + 1 < literal.length) {
                out.append(
                    when (literal[i + 1]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'
                        else -> literal[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}
