package com.bainbridgetrails.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.TrailLineSpec
import com.bainbridgetrails.map.palette
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.ic_layers
import com.bainbridgetrails.resources.map_legend
import com.bainbridgetrails.resources.map_legend_footnote
import com.bainbridgetrails.resources.map_legend_named_not_counted
import com.bainbridgetrails.resources.status_not_counted
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Map legend (ux.md §3.2): a [MapIconButton] that expands into a card of the four statuses, named trails outside the
 * island total (TL-6) and not-counted paths. Each swatch draws the **map's** line pattern in the active [style]'s
 * colors, so the legend always matches what is on screen. Anchored bottom-start; the card grows upward.
 */
@Composable
fun StatusLegend(style: MapStyle, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val p = style.palette
    val dur = if (BtTheme.motion.reduced) 0 else BtDuration.medium1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            expanded,
            enter = fadeIn(tween(dur)) + scaleIn(tween(dur), initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(dur)) + scaleOut(tween(dur), targetScale = 0.92f, transformOrigin = TransformOrigin(0f, 1f)),
        ) {
            Column(
                Modifier
                    .widthIn(max = 280.dp)
                    .shadow(3.dp, MaterialTheme.shapes.large)
                    .background(BtTheme.colors.mapChrome, MaterialTheme.shapes.large)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(Res.string.map_legend),
                    Modifier.semantics { heading() }.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = BtTheme.colors.onMapChrome,
                )
                for (s in TrailStatus.entries.reversed()) {
                    LegendRow(stringResource(s.label)) { drawStatusSwatch(s, p) }
                }
                LegendRow(stringResource(Res.string.map_legend_named_not_counted)) { drawNamedNotCountedSwatch(p) }
                LegendRow(stringResource(Res.string.status_not_counted)) { drawHairlineSwatch(p) }
                Text(
                    stringResource(Res.string.map_legend_footnote),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = BtTheme.colors.onMapChrome.copy(alpha = 0.78f),
                )
            }
        }
        MapIconButton(Res.drawable.ic_layers, stringResource(Res.string.map_legend), onToggle, active = expanded)
    }
}

@Composable
private fun LegendRow(label: String, swatch: DrawScope.() -> Unit) {
    Row(
        Modifier.heightIn(min = 28.dp).clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(36.dp, 12.dp), onDraw = swatch)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BtTheme.colors.onMapChrome)
    }
}

/** Line width at z15 for [s], in px of this density. */
private fun DrawScope.z15Width(s: TrailStatus, extra: Float = 0f): Float =
    (TrailLineSpec.widths.getValue(s).first { it.first == 15 }.second + extra).dp.toPx()

private fun DrawScope.line(color: Color, width: Float, cap: StrokeCap, dash: List<Float>? = null, alpha: Float = 1f) {
    val y = size.height / 2
    val inset = width / 2 + 1f
    drawLine(
        color, Offset(inset, y), Offset(size.width - inset, y), width, cap,
        pathEffect = dash?.let { d -> PathEffect.dashPathEffect(d.map { it * width }.toFloatArray()) },
        alpha = alpha,
    )
}

/** The map encoding of [s] (styles.md): halo-less, so it reads on the legend card. */
internal fun DrawScope.drawStatusSwatch(s: TrailStatus, p: MapPalette) {
    val w = z15Width(s)
    if (TrailLineSpec.hasCasing(s)) line(p.casing, z15Width(s, TrailLineSpec.CASING_EXTRA), StrokeCap.Butt)
    val cap = if (s == TrailStatus.PARTWAY) StrokeCap.Butt else StrokeCap.Round
    line(p.of(s), w, cap, TrailLineSpec.dash(s))
}

private fun DrawScope.drawNamedNotCountedSwatch(p: MapPalette) {
    val w = z15Width(TrailStatus.NOT_STARTED) * TrailLineSpec.NOT_COUNTED_NAMED_SCALE
    line(p.notStarted, w, StrokeCap.Butt, TrailLineSpec.notCountedNamedDash)
}

private fun DrawScope.drawHairlineSwatch(p: MapPalette) {
    line(p.notCounted, 1.dp.toPx(), StrokeCap.Round, alpha = p.notCountedOpacity)
}
