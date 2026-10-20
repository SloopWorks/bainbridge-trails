package com.bainbridgetrails.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import kotlin.math.max
import kotlin.math.min

/** P1-9: when the celebration shows the whole-island variant. */
object IslandCelebration {
    /**
     * The island is whole: every included metre covered (`islandFraction` reaches 1, product F6) or every trail that
     * counts toward the island Complete. [countedIds] = trails with any island credit (TL-6 non-counted ones excluded).
     */
    fun isWhole(s: ProgressSnapshot, countedIds: Set<String>): Boolean {
        if (s.islandFraction >= 1.0) return true
        val counted = s.trails.filter { it.trailId in countedIds }
        return counted.isNotEmpty() && counted.all { it.status == TrailStatus.COMPLETE }
    }

    /**
     * Show the island variant for this celebration: requested explicitly ([island]: the walk crossed 100%, or a debug
     * preview), or the island is whole now and this walk completed a counted trail (so it wasn't whole before). A later
     * completion of a non-counted trail on a whole island stays the ordinary trail celebration.
     */
    fun show(island: Boolean, s: ProgressSnapshot, completedNow: List<String>, countedIds: Set<String>): Boolean =
        island || (completedNow.any { it in countedIds } && isWhole(s, countedIds))
}

/**
 * Whole-island art (P1-9, ux.md §3.6 motif at island scale): a `complete.container` wash, the coastline drawing itself
 * (100–1300 ms), land fills in, every counted trail lights up in `complete` (700–1500 ms), then three water lines
 * ripple out from the coast and settle as the Topo coastline rings. [t] = celebration clock (ms); reduced motion
 * passes the final frame. Decorative: the screen's heading carries the announcement.
 */
@Composable
internal fun IslandCelebrationArt(geometry: IslandGeometry, t: Float, modifier: Modifier = Modifier) {
    val colors = BtTheme.colors
    val accent = colors.complete.accent
    val line = colors.complete.line
    val container = colors.complete.container
    val ring = colors.waterLine
    val land = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val coast = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val counted = remember(geometry) { geometry.trails.filter { it.counted }.map { it.xy } }
    Canvas(
        // Fixed frame (the island is tall: an aspect-ratio box would outgrow the screen); the drawing fits inside it.
        modifier.widthIn(max = 340.dp).fillMaxWidth().height(320.dp)
            .clearAndSetSemantics {}
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(container.copy(alpha = 0.65f), container.copy(alpha = 0f)), center, size.maxDimension * 0.65f),
                    radius = size.maxDimension * 0.65f,
                )
            },
    ) {
        val ringGap = 6.dp.toPx()
        val ringW = 1.5.dp.toPx()
        val pad = 3 * ringGap + ringW + 4.dp.toPx()
        val scale = min((size.width - 2 * pad) / geometry.aspect, size.height - 2 * pad).coerceAtLeast(1f)
        val ox = (size.width - geometry.aspect * scale) / 2
        val oy = (size.height - scale) / 2
        fun pathOf(xy: FloatArray, closed: Boolean) = Path().apply {
            if (xy.size < 4) return@apply
            moveTo(ox + xy[0] * scale, oy + xy[1] * scale)
            var i = 2
            while (i < xy.size) { lineTo(ox + xy[i] * scale, oy + xy[i + 1] * scale); i += 2 }
            if (closed) close()
        }
        val rings = geometry.land.map { pathOf(it, closed = true) }
        val landPath = Path().apply { rings.forEach { addPath(it) } }
        val trails = Path().apply { counted.forEach { addPath(pathOf(it, closed = false)) } }

        // Water lines: ripple out from the coast after it closes, then settle at 1, 2, 3 × ringGap (final frame).
        val alphas = floatArrayOf(0.85f, 0.55f, 0.3f)
        for (i in 2 downTo 0) {
            val p = BtEasing.emphasizedDecelerate.transform(phase(t, 1300 + 180 * i, 2300 + 100 * i))
            if (p <= 0f) continue
            ringLine(landPath, ringGap * (i + 1) * p, ringW, ring.copy(alpha = alphas[i] * min(1f, p * 1.5f)))
        }
        // Land fills in as the coastline closes.
        val fill = phase(t, 900, 1400)
        if (fill > 0f) drawPath(landPath, land.copy(alpha = fill))

        // Coastline draws itself (all rings in sequence, by length).
        val lengths = rings.map { PathMeasure().apply { setPath(it, false) }.length }
        val total = lengths.sum()
        val drawn = total * BtEasing.emphasizedDecelerate.transform(phase(t, 100, 1300))
        val coastStroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        var start = 0f
        rings.forEachIndexed { i, r ->
            val len = lengths[i]
            when {
                drawn >= start + len -> drawPath(r, coast, style = coastStroke)
                drawn > start -> Path().also { part ->
                    PathMeasure().apply { setPath(r, false) }.getSegment(0f, drawn - start, part, true)
                    drawPath(part, coast, style = coastStroke)
                }
            }
            start += len
        }

        // Every counted trail lights up, with one glow pulse.
        val lit = phase(t, 700, 1500)
        if (lit > 0f) {
            val pulse = kotlin.math.sin(kotlin.math.PI * phase(t, 1300, 1800)).toFloat()
            drawPath(trails, accent.copy(alpha = 0.25f * lit * (0.6f + pulse)), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(trails, line.copy(alpha = lit), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 0..1 progress of [t] through [from, to] ms. */
private fun phase(t: Float, from: Int, to: Int): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** A [w]-wide line [offset] outside (and inside) the coastline: a wide stroke with its middle cleared, in its own layer. */
private fun DrawScope.ringLine(path: Path, offset: Float, w: Float, color: Color) {
    val outer = 2 * offset + w
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        drawPath(path, color, style = Stroke(outer, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.Black, style = Stroke(max(outer - 2 * w, 0f), cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
        canvas.restore()
    }
}
