package com.bainbridgetrails.ui.play.map

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The English `play_*` strings straight from `values/strings.xml` (read by path from the Gradle test working
 * directory, like PlayFixtures), so the text checks read exactly what the screens show.
 */
internal object EnglishPlayStrings {
    private val xml: String by lazy {
        SystemFileSystem.source(Path("src/commonMain/composeResources/values/strings.xml")).buffered().use { it.readString() }
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    private val strings: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private val plurals: Map<String, Map<String, String>> by lazy {
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m ->
            m.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }
    }

    operator fun get(key: String): String = strings[key] ?: error("no string $key in values/strings.xml")

    fun plural(key: String, quantity: String): String = plurals[key]?.get(quantity) ?: error("no plural $key/$quantity")

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}
