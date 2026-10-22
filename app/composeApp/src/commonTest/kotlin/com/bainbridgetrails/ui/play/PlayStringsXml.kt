package com.bainbridgetrails.ui.play

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The `values/strings*.xml` texts by key (read by path from the Gradle test working directory, like PlayFixtures), so
 * the L2-7 text checks read what the screens show, in both goals (`roads_` twins live in `strings_roads.xml`).
 */
internal object PlayStringsXml {
    private const val DIR = "src/commonMain/composeResources/values"

    private fun xml(file: String): String =
        SystemFileSystem.source(Path("$DIR/$file")).buffered().use { it.readString() }
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val all: String by lazy { xml("strings.xml") + xml("strings_roads.xml") }

    private val strings: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(all)
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private val plurals: Map<String, Map<String, String>> by lazy {
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(all).associate { m ->
            m.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }
    }

    operator fun get(key: String): String = strings[key] ?: error("no string $key")

    /** English plural rule: one for 1, other otherwise; `%1$d` filled with [n]. */
    fun plural(key: String, n: Int): String = pluralForm(key, n).replace("%1\$d", n.toString())

    /** The unfilled English plural form of [key] for [quantity] (one for 1, other otherwise). */
    fun pluralForm(key: String, quantity: Int): String {
        val forms = plurals[key] ?: error("no plural $key")
        return (if (quantity == 1) forms["one"] else null) ?: forms.getValue("other")
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}
