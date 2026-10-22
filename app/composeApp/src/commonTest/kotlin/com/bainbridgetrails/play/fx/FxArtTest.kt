package com.bainbridgetrails.play.fx

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/play/trails/ux.md § 2.3: `FxGlyph.Summit` is one ink on the 24-unit artboard: a contour hill arc (2.0), an inner
 * detail arc (1.25) and the survey △ at 55 % (its scaled stroke drawn at the detail weight) with its centre dot (the
 * only fill), standing on the crest.
 */
class FxArtTest {
    private val ink = Color(0xFF22323C)

    @Test
    fun `summit is a contour arc plus a detail arc plus the survey mark in one ink`() {
        val v = FxArt.vector(FxGlyph.Summit, ink)
        assertEquals(FxArt.ARTBOARD, v.viewportWidth)
        val paths = (0 until v.root.size).map { v.root[it] }.filterIsInstance<VectorPath>()
        assertEquals(4, paths.size, "hill, inner contour, △, centre dot")
        val strokes = paths.filter { it.stroke != null }
        assertEquals(listOf(FxArt.CONTOUR, FxArt.DETAIL, FxArt.DETAIL), strokes.map { it.strokeLineWidth })
        val fills = paths.filter { it.fill != null }
        assertEquals(1, fills.size, "only the △ centre dot is filled")
        (strokes.map { it.stroke } + fills.map { it.fill }).forEach { assertEquals(SolidColor(ink), it) }
    }

    @Test
    fun `every glyph builds`() {
        FxGlyph.entries.forEach { g -> assertTrue(FxArt.vector(g, ink).root.size > 0, "$g is empty") }
    }
}
