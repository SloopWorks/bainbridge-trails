package com.bainbridgetrails.play.fx

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.ui.theme.BtTheme

/**
 * Play's figure set (docs/play/ux.md §2.13 #4, effects.md §1.1), **placeholder** line art until the engraved set lands
 * (ux §7.2 #4: layouts do not change when it does). One source per figure for chrome now and map rasters later.
 *
 * Rules kept even by the placeholders: one ink (`play.ink`, passed in), round caps and joins, two weights only
 * (contour 2.0 / detail 1.25 on the 24-unit artboard), no fills except the lamp (lit lantern) and fingertip ink dots
 * (pupils, the △ centre), no type except the riddle "?". Colors are parameters so both themes and every surface
 * (gate icons use `onSurfaceVariant`, the marks glyph uses `play.seaGlass`) draw from the same source.
 */
enum class FxGlyph {
    /** The survey mark △ with a centre dot: the icon for marks everywhere. [FxArt.vector] `ticks` adds rank ticks. */
    Marks,
    SeaGlass, Agate, GlassFloat, ShipsBell,
    EggStamp,
    LanternUnlit, LanternLit,
    Frog, Ferry, KrakenEye, Pennant,
    QuietRing,
    GateSun, GateWave, GateMoon, GateShield,
    ;

    companion object {
        /** The four treasure tiers, common → rare (product §3). */
        val treasureTiers = listOf(SeaGlass, Agate, GlassFloat, ShipsBell)
    }
}

object FxArt {
    /** Artboard units (stamps; effects §1.1). */
    const val ARTBOARD = 24f
    const val CONTOUR = 2.0f
    const val DETAIL = 1.25f

    /**
     * The figure as an [ImageVector] drawn in [ink] (and [lamp] for the lit lantern's flame and glow). [ticks]
     * (0–3) draws rank ticks under [FxGlyph.Marks] (RankBadge: ranks 1–3 one tick, 4–7 two, 8–11 three).
     */
    fun vector(glyph: FxGlyph, ink: Color, lamp: Color = ink, ticks: Int = 0): ImageVector {
        require(ticks in 0..3) { "ticks must be 0..3" }
        return ImageVector.Builder(
            name = "fx_${glyph.name}_$ticks",
            defaultWidth = ARTBOARD.dp,
            defaultHeight = ARTBOARD.dp,
            viewportWidth = ARTBOARD,
            viewportHeight = ARTBOARD,
        ).apply { Art(this, ink, lamp).draw(glyph, ticks) }.build()
    }

    /** Rank ticks for a rank 1–11 (ux §2.7). */
    fun ticksForRank(rank: Int): Int = when {
        rank <= 0 -> 0
        rank <= 3 -> 1
        rank <= 7 -> 2
        else -> 3
    }
}

/**
 * A decorative [FxGlyph] at [size] (art is never announced: ux §2 "Art … clearAndSetSemantics {}"; the row or chip
 * around it carries the words). Defaults are Play ink and lamp from the theme.
 */
@Composable
fun FxGlyphImage(
    glyph: FxGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    ink: Color = BtTheme.colors.play.ink,
    lamp: Color = BtTheme.colors.play.lamp,
    ticks: Int = 0,
) {
    val vector = remember(glyph, ink, lamp, ticks) { FxArt.vector(glyph, ink, lamp, ticks) }
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size).clearAndSetSemantics {})
}

private class Art(val b: ImageVector.Builder, val ink: Color, val lamp: Color) {
    fun stroke(width: Float = FxArt.CONTOUR, color: Color = ink, block: PathBuilder.() -> Unit) {
        b.path(stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }

    fun detail(block: PathBuilder.() -> Unit) = stroke(FxArt.DETAIL, ink, block)

    fun fill(color: Color, alpha: Float = 1f, block: PathBuilder.() -> Unit) {
        b.path(fill = SolidColor(color), fillAlpha = alpha, pathBuilder = block)
    }

    fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx - rx, cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + rx, y1 = cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - rx, y1 = cy)
        close()
    }

    fun draw(glyph: FxGlyph, ticks: Int) = when (glyph) {
        FxGlyph.Marks -> marks(ticks)
        FxGlyph.SeaGlass -> seaGlass()
        FxGlyph.Agate -> agate()
        FxGlyph.GlassFloat -> glassFloat()
        FxGlyph.ShipsBell -> shipsBell()
        FxGlyph.EggStamp -> eggStamp()
        FxGlyph.LanternUnlit -> lantern(lit = false)
        FxGlyph.LanternLit -> lantern(lit = true)
        FxGlyph.Frog -> frog()
        FxGlyph.Ferry -> ferry()
        FxGlyph.KrakenEye -> krakenEye()
        FxGlyph.Pennant -> pennant()
        FxGlyph.QuietRing -> quietRing()
        FxGlyph.GateSun -> sun()
        FxGlyph.GateWave -> wave()
        FxGlyph.GateMoon -> moon()
        FxGlyph.GateShield -> shield()
    }

    /** Triangulation-station symbol: △ with a centre dot; rank ticks under it. */
    private fun marks(ticks: Int) {
        val base = if (ticks == 0) 19.5f else 16.5f
        val top = if (ticks == 0) 3.5f else 2.5f
        stroke { moveTo(12f, top); lineTo(20.5f, base); lineTo(3.5f, base); close() }
        fill(ink) { circle(12f, base - (base - top) / 3f, 1.5f) }
        if (ticks > 0) {
            val gap = 3.5f
            val x0 = 12f - gap * (ticks - 1) / 2f
            stroke { repeat(ticks) { i -> moveTo(x0 + gap * i, 19.5f); lineTo(x0 + gap * i, 22.5f) } }
        }
    }

    /** Tier 1: a frosted pebble, a highlight arc and a frost line. */
    private fun seaGlass() {
        stroke {
            moveTo(6f, 9f)
            curveTo(8f, 5.5f, 15f, 5f, 18.5f, 8f)
            curveTo(21f, 10.5f, 20f, 16f, 16f, 18f)
            curveTo(12f, 20f, 6f, 19.5f, 4.5f, 16f)
            curveTo(3.5f, 13.5f, 4.5f, 11f, 6f, 9f)
            close()
        }
        detail { moveTo(8f, 10.5f); curveTo(9f, 9f, 10.5f, 8.4f, 12f, 8.3f); moveTo(9.5f, 15.5f); lineTo(14.5f, 15.5f) }
    }

    /** Tier 2: a banded agate. */
    private fun agate() {
        stroke { ellipse(12f, 12.5f, 8.5f, 6.5f) }
        detail { ellipse(12f, 12.5f, 5.2f, 3.8f) }
        detail { ellipse(12f, 12.5f, 2.2f, 1.5f) }
    }

    /** Tier 3: a glass fishing float in its net. */
    private fun glassFloat() {
        stroke { circle(12f, 13f, 7f) }
        stroke { moveTo(10.8f, 6.1f); lineTo(11f, 4.2f); lineTo(13f, 4.2f); lineTo(13.2f, 6.1f) }
        detail {
            moveTo(5.3f, 11f); lineTo(18.7f, 11f)
            moveTo(5.3f, 15f); lineTo(18.7f, 15f)
            moveTo(9f, 6.7f); lineTo(9f, 19.3f)
            moveTo(15f, 6.7f); lineTo(15f, 19.3f)
        }
    }

    /** Tier 4: a ship's bell. */
    private fun shipsBell() {
        stroke {
            moveTo(6.5f, 17.5f)
            curveTo(7.2f, 12f, 7.8f, 7f, 12f, 7f)
            curveTo(16.2f, 7f, 16.8f, 12f, 17.5f, 17.5f)
            moveTo(5f, 17.5f); lineTo(19f, 17.5f)
            moveTo(10.5f, 7f); lineTo(10.5f, 4.5f); lineTo(13.5f, 4.5f); lineTo(13.5f, 7f)
        }
        detail { moveTo(8f, 13.5f); lineTo(16f, 13.5f); circle(12f, 19.6f, 1.1f) }
    }

    /** An easter egg in a stamp ring (stamps are printed flat; never tappable). */
    private fun eggStamp() {
        detail { circle(12f, 12f, 10f) }
        stroke {
            moveTo(12f, 5.5f)
            curveTo(15.5f, 5.5f, 17f, 10.5f, 17f, 13f)
            curveTo(17f, 16.2f, 14.8f, 18.5f, 12f, 18.5f)
            curveTo(9.2f, 18.5f, 7f, 16.2f, 7f, 13f)
            curveTo(7f, 10.5f, 8.5f, 5.5f, 12f, 5.5f)
            close()
        }
        detail { moveTo(7.4f, 12f); lineTo(9.3f, 10.6f); lineTo(11.1f, 12f); lineTo(12.9f, 10.6f); lineTo(14.7f, 12f); lineTo(16.6f, 10.6f) }
    }

    /** The riddle lantern: unlit carries the "?", lit carries a lamp flame and glow. */
    private fun lantern(lit: Boolean) {
        if (lit) fill(lamp, alpha = 0.45f) { circle(12f, 12f, 8.5f) }
        stroke {
            moveTo(9.5f, 5f); curveTo(9.5f, 2.6f, 14.5f, 2.6f, 14.5f, 5f)
            moveTo(8f, 5f); lineTo(16f, 5f); lineTo(15f, 7f); lineTo(9f, 7f); close()
            moveTo(9f, 7f); lineTo(8.2f, 17f)
            moveTo(15f, 7f); lineTo(15.8f, 17f)
            moveTo(7f, 17f); lineTo(17f, 17f); lineTo(16.2f, 19.5f); lineTo(7.8f, 19.5f); close()
        }
        if (lit) {
            fill(lamp) { moveTo(12f, 8.8f); curveTo(13.8f, 10.9f, 14f, 12.6f, 12f, 15f); curveTo(10f, 12.6f, 10.2f, 10.9f, 12f, 8.8f); close() }
            detail { moveTo(12f, 8.8f); curveTo(13.8f, 10.9f, 14f, 12.6f, 12f, 15f); curveTo(10f, 12.6f, 10.2f, 10.9f, 12f, 8.8f); close() }
        } else {
            detail { moveTo(10.5f, 10.2f); curveTo(10.5f, 8.6f, 13.5f, 8.6f, 13.5f, 10.3f); curveTo(13.5f, 11.6f, 12f, 11.7f, 12f, 13.1f) }
            fill(ink) { circle(12f, 15f, 0.75f) }
        }
    }

    /** Frog Rock: eyes, a single mouth curve (effects §1.1 faces). */
    private fun frog() {
        stroke {
            moveTo(4f, 16f)
            curveTo(4f, 11.5f, 7.5f, 9.5f, 12f, 9.5f)
            curveTo(16.5f, 9.5f, 20f, 11.5f, 20f, 16f)
            curveTo(20f, 18.2f, 17f, 19.5f, 12f, 19.5f)
            curveTo(7f, 19.5f, 4f, 18.2f, 4f, 16f)
            close()
        }
        stroke { circle(8.3f, 8.8f, 2.4f) }
        stroke { circle(15.7f, 8.8f, 2.4f) }
        fill(ink) { circle(8.3f, 8.8f, 0.9f); circle(15.7f, 8.8f, 0.9f) }
        detail { moveTo(8f, 14.5f); curveTo(10f, 16.2f, 14f, 16.2f, 16f, 14.5f) }
    }

    /** The ferry, with two water lines. */
    private fun ferry() {
        stroke {
            moveTo(3f, 14.5f); lineTo(21f, 14.5f); lineTo(19f, 18.5f); lineTo(5f, 18.5f); close()
            moveTo(6f, 14.5f); lineTo(6f, 11f); lineTo(17.5f, 11f); lineTo(17.5f, 14.5f)
            moveTo(9f, 11f); lineTo(9f, 8.5f); lineTo(14f, 8.5f); lineTo(14f, 11f)
            moveTo(15.8f, 11f); lineTo(15.8f, 6.5f)
        }
        detail {
            moveTo(8f, 12.8f); lineTo(15.5f, 12.8f)
            moveTo(3f, 21.3f); curveTo(5f, 20.3f, 7f, 22.3f, 9f, 21.3f)
            moveTo(15f, 21.3f); curveTo(17f, 20.3f, 19f, 22.3f, 21f, 21.3f)
        }
    }

    /** The kraken's lurk eye: almond, ink pupil, lid stroke. */
    private fun krakenEye() {
        stroke { moveTo(3f, 12.5f); curveTo(7f, 6.5f, 17f, 6.5f, 21f, 12.5f); curveTo(17f, 18.5f, 7f, 18.5f, 3f, 12.5f); close() }
        detail { circle(12f, 12.5f, 3.4f) }
        fill(ink) { moveTo(12f, 9.7f); curveTo(12.9f, 11f, 12.9f, 14f, 12f, 15.3f); curveTo(11.1f, 14f, 11.1f, 11f, 12f, 9.7f); close() }
        detail { moveTo(4.5f, 9.5f); curveTo(8f, 4.6f, 16f, 4.6f, 19.5f, 9.5f) }
    }

    /** Challenge pennant (Challenges chip, (v2) area flag pin). */
    private fun pennant() {
        stroke { moveTo(6f, 3.5f); lineTo(6f, 21f) }
        stroke { moveTo(6f, 4.5f); lineTo(19f, 8.5f); lineTo(6f, 12.5f) }
    }

    /** Remembrance / quiet zone: an ink ring with one horizontal rule. No fill, ever. */
    private fun quietRing() {
        detail { circle(12f, 12f, 8f) }
        detail { moveTo(8f, 12f); lineTo(16f, 12f) }
    }

    /** Gate: daylight only. */
    private fun sun() {
        stroke { circle(12f, 12f, 4f) }
        detail {
            for ((dx, dy) in listOf(0f to -1f, 0.707f to -0.707f, 1f to 0f, 0.707f to 0.707f, 0f to 1f, -0.707f to 0.707f, -1f to 0f, -0.707f to -0.707f)) {
                moveTo(12f + dx * 6.5f, 12f + dy * 6.5f); lineTo(12f + dx * 9f, 12f + dy * 9f)
            }
        }
    }

    /** Gate: waits for the tide. */
    private fun wave() {
        stroke {
            moveTo(3f, 9.5f); curveTo(5f, 7.5f, 7f, 7.5f, 9f, 9.5f); curveTo(11f, 11.5f, 13f, 11.5f, 15f, 9.5f); curveTo(17f, 7.5f, 19f, 7.5f, 21f, 9.5f)
            moveTo(3f, 15f); curveTo(5f, 13f, 7f, 13f, 9f, 15f); curveTo(11f, 17f, 13f, 17f, 15f, 15f); curveTo(17f, 13f, 19f, 13f, 21f, 15f)
        }
    }

    /** Gate: asleep until daylight. */
    private fun moon() {
        stroke {
            moveTo(14.5f, 4f)
            arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20f, y1 = 15.5f)
            arcTo(6.8f, 6.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14.5f, y1 = 4f)
            close()
        }
    }

    /** Gate: a safety line (shore, (v2) wildlife). */
    private fun shield() {
        stroke {
            moveTo(12f, 3.5f); lineTo(19f, 6f); lineTo(19f, 11.5f)
            curveTo(19f, 16f, 16f, 19f, 12f, 20.5f)
            curveTo(8f, 19f, 5f, 16f, 5f, 11.5f)
            lineTo(5f, 6f); close()
        }
        detail { moveTo(12f, 7f); lineTo(12f, 17f) }
    }
}
