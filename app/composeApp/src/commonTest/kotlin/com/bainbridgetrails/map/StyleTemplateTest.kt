package com.bainbridgetrails.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StyleTemplateTest {
    @Test
    fun substitutesEveryPlaceholder() {
        val template = """{"sources":{"a":{"url":"{{basemap}}"},"b":{"url":"{{trails}}"},"c":{"url":"{{basemap}}"}}}"""
        val out = renderStyleTemplate(template, mapOf("basemap" to "pmtiles://file:///x/b.pmtiles", "trails" to "T"))
        assertEquals(
            """{"sources":{"a":{"url":"pmtiles://file:///x/b.pmtiles"},"b":{"url":"T"},"c":{"url":"pmtiles://file:///x/b.pmtiles"}}}""",
            out,
        )
    }

    @Test
    fun unknownPlaceholderFails() {
        val e = assertFailsWith<IllegalArgumentException> { renderStyleTemplate("{{nope}}", mapOf("trails" to "T")) }
        assertTrue("nope" in e.message!!)
    }

    @Test
    fun unusedValuesAreFine() {
        assertEquals("plain", renderStyleTemplate("plain", mapOf("trails" to "T")))
    }

    @Test
    fun fileUrlEncodesUnsafePathCharacters() {
        assertEquals("pmtiles://file:///a/b%20c/d.pmtiles", pmtilesFileUrl("/a/b c/d.pmtiles"))
        assertEquals("pmtiles://file:///data/user/0/x/files/map/t.pmtiles", pmtilesFileUrl("/data/user/0/x/files/map/t.pmtiles"))
    }

    @Test
    fun parsesMetadataVersion() {
        assertEquals("2026-10-18+1ef3deda", metadataVersion("""{"trails":[],"version":"2026-10-18+1ef3deda"}"""))
    }
}
