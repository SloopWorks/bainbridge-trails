package com.bainbridgetrails.data

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun anonListsHaveExpectedShape() {
        assertEquals(47, AnonNames.ADJECTIVES.size)
        assertEquals(47, AnonNames.NOUNS.size)
        assertEquals(47, AnonNames.ADJECTIVES.toSet().size)
        assertEquals(47, AnonNames.NOUNS.toSet().size)
        assertFalse("Wandering" in AnonNames.ADJECTIVES)
        assertFalse("Thimbleberry" in AnonNames.NOUNS)
        val longest = "${AnonNames.ADJECTIVES.maxBy { it.length }} ${AnonNames.NOUNS.maxBy { it.length }} 99"
        assertTrue(longest.length <= DisplayNameRules.MAX, longest)
    }

    /** Parity with backend/src/moderation/names.ts (host tests run from app/composeApp; skipped where the file isn't visible). */
    @Test
    fun anonListsMatchBackend() {
        val p = listOf("../../backend/src/moderation/names.ts", "../backend/src/moderation/names.ts").map(::Path).firstOrNull { SystemFileSystem.exists(it) }
            ?: return
        val ts = SystemFileSystem.source(p).buffered().use { it.readString() }
        fun list(name: String): List<String> {
            val body = Regex("export const $name = \\[([\\s\\S]*?)];").find(ts)!!.groupValues[1]
            return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        }
        assertEquals(list("ADJECTIVES"), AnonNames.ADJECTIVES)
        assertEquals(list("NOUNS"), AnonNames.NOUNS)
    }

    @Test
    fun generatedNamesAreValid() {
        val r = Random(7)
        repeat(500) {
            val n = AnonNames.generate(r)
            assertTrue(AnonNames.isAnonName(n), n)
            assertIs<NameCheck.Ok>(DisplayNameRules.check(n), n)
        }
        assertFalse(AnonNames.isAnonName("Mossy Heron 9"))
        assertFalse(AnonNames.isAnonName("Wandering Heron 42"))
        assertFalse(AnonNames.isAnonName("Mossy  Heron 42"))
    }

    @Test
    fun displayNameRules() {
        assertEquals(NameCheck.TooShort, DisplayNameRules.check("ab"))
        assertEquals(NameCheck.TooShort, DisplayNameRules.check("   ab   "))
        assertEquals(NameCheck.TooLong, DisplayNameRules.check("a".repeat(25)))
        assertEquals(NameCheck.Ok("a".repeat(24)), DisplayNameRules.check("a".repeat(24)))
        assertEquals(NameCheck.Ok("Trail Fox"), DisplayNameRules.check("  Trail \t  Fox  "))
        assertEquals(NameCheck.Ok("Zoë O'Neil-Ray"), DisplayNameRules.check("Zoë O'Neil-Ray")) // NFC
        assertEquals(NameCheck.InvalidCharacters, DisplayNameRules.check("Trail 🦊"))
        assertEquals(NameCheck.InvalidCharacters, DisplayNameRules.check("Иван Петров"))
        assertEquals(NameCheck.InvalidCharacters, DisplayNameRules.check("a+b team"))
        assertEquals(NameCheck.TooFewLetters, DisplayNameRules.check("a 123"))
        assertEquals(NameCheck.LooksLikeLink, DisplayNameRules.check("visit www site"))
        assertEquals(NameCheck.LooksLikeLink, DisplayNameRules.check("httpfan"))
        assertEquals(NameCheck.LooksLikeLink, DisplayNameRules.check("trails.com"))
        assertIs<NameCheck.Ok>(DisplayNameRules.check("J. R. Walker"))
        assertEquals(9, DisplayNameRules.length(" Trail  Fox "))
    }
}
