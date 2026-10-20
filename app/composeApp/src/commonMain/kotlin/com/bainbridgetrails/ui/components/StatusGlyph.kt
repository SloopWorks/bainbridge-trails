package com.bainbridgetrails.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.of
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Status icon mirroring the map line patterns (ux.md §4): NOT_STARTED ring of 8 dots, PARTWAY dashed ring with the
 * left half filled, NEARLY_DONE ¾ pie with a casing outline, COMPLETE filled disc with a check cut out.
 * Decorative: a text label always sits next to it.
 */
@Composable
fun StatusGlyph(status: TrailStatus, modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = BtTheme.colors.of(status).accent) {
    val casing = BtTheme.colors.nearlyDoneCasing
    Canvas(
        modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen) // check cut-out clears pixels
            .clearAndSetSemantics {},
    ) { drawStatusGlyph(status, color, casing) }
}

internal fun DrawScope.drawStatusGlyph(status: TrailStatus, color: Color, casing: Color) {
    val d = size.minDimension
    val c = center
    when (status) {
        TrailStatus.NOT_STARTED -> {
            val ringR = d * 0.38f
            val dotR = d * 0.085f
            repeat(8) { i ->
                val a = (i * 45.0 - 90.0) * PI / 180.0
                drawCircle(color, dotR, Offset(c.x + ringR * cos(a).toFloat(), c.y + ringR * sin(a).toFloat()))
            }
        }
        TrailStatus.PARTWAY -> {
            val stroke = d * 0.11f
            val r = d / 2 - stroke / 2
            // 4 dashes, gaps centered on the diagonals.
            for (i in 0 until 4) {
                drawArc(color, -90f + i * 90f + 14f, 62f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val inner = r - stroke * 1.4f
            drawArc(color, 90f, 180f, true, Offset(c.x - inner, c.y - inner), Size(2 * inner, 2 * inner))
        }
        TrailStatus.NEARLY_DONE -> {
            val r = d / 2 - d * 0.06f
            val tl = Offset(c.x - r, c.y - r)
            drawArc(color, -90f, 270f, true, tl, Size(2 * r, 2 * r))
            drawArc(casing, -90f, 270f, true, tl, Size(2 * r, 2 * r), style = Stroke(d * 0.07f, join = StrokeJoin.Round))
            // Empty quarter keeps a hairline so the full circle still reads.
            drawArc(color, 180f, 90f, false, tl, Size(2 * r, 2 * r), style = Stroke(d * 0.06f))
        }
        TrailStatus.COMPLETE -> {
            drawCircle(color, d / 2)
            val check = Path().apply {
                moveTo(c.x - d * 0.22f, c.y + d * 0.01f)
                lineTo(c.x - d * 0.06f, c.y + d * 0.17f)
                lineTo(c.x + d * 0.24f, c.y - d * 0.15f)
            }
            drawPath(check, Color.Black, style = Stroke(d * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
        }
    }
}
