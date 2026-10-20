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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.RoadBedColors
import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.map.palette
import com.bainbridgetrails.map.roadBeds
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.ic_layers
import com.bainbridgetrails.resources.map_legend
import com.bainbridgetrails.resources.map_legend_footnote
import com.bainbridgetrails.resources.map_legend_named_not_counted
import com.bainbridgetrails.resources.roads_legend_not_walked
import com.bainbridgetrails.resources.roads_legend_private
import com.bainbridgetrails.resources.roads_legend_road_end
import com.bainbridgetrails.resources.roads_legend_type_ramp
import com.bainbridgetrails.resources.roads_legend_types
import com.bainbridgetrails.resources.roads_legend_unknown
import com.bainbridgetrails.resources.status_not_counted
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Map legend (ux.md §3.2): a [MapIconButton] that expands into a card of the four statuses and what else the map
 * draws. Each swatch draws the **map's** line pattern from [spec] in the active [style]'s colors, so the legend always
 * matches what is on screen. Trails: named trails outside the island total (TL-6) and not-counted paths. Roads
 * (docs/roads/ux.md §2.7): the plain bed that means "not walked yet", the road-type ramp, private and unconfirmed
 * roads and road-end pins; the card scrolls inside 60% of the available height. Anchored bottom-start; grows upward.
 */
@Composable
fun StatusLegend(
    style: MapStyle,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    spec: StatusLayerSpec = StatusLayerSpec.current,
) {
    val p = style.palette
    val beds = style.roadBeds
    val goal = LocalGoal.current
    val dur = if (BtTheme.motion.reduced) 0 else BtDuration.medium1
    val roads = spec.drawRoadEnds
    BoxWithConstraints(modifier) {
        val maxCard = maxHeight * 0.6f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(
                expanded,
                enter = fadeIn(tween(dur)) + scaleIn(tween(dur), initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 1f)),
                exit = fadeOut(tween(dur)) + scaleOut(tween(dur), targetScale = 0.92f, transformOrigin = TransformOrigin(0f, 1f)),
            ) {
                Column(
                    Modifier
                        .widthIn(max = 280.dp)
                        .then(if (roads && maxCard.value > 0) Modifier.heightIn(max = maxCard.coerceAtLeast(200.dp)) else Modifier)
                        .shadow(3.dp, MaterialTheme.shapes.large)
                        .background(BtTheme.colors.mapChrome, MaterialTheme.shapes.large)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(Res.string.map_legend),
                        Modifier.semantics { heading() }.padding(bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = BtTheme.colors.onMapChrome,
                    )
                    val statuses = if (roads) TrailStatus.entries.reversed().filter { it != TrailStatus.NOT_STARTED } else TrailStatus.entries.reversed()
                    for (s in statuses) {
                        LegendRow(stringResource(s.label)) { drawStatusSwatch(s, p, spec) }
                    }
                    if (roads) {
                        LegendRow(stringResource(Res.string.roads_legend_not_walked)) { drawNotWalkedRoadSwatch(p, beds, spec) }
                        LegendRow(stringResource(Res.string.roads_legend_types), stringResource(Res.string.roads_legend_type_ramp)) { drawTypeRamp(beds) }
                        LegendRow(stringResource(Res.string.roads_legend_private)) { drawPrivateSwatch(beds) }
                        LegendRow(stringResource(Res.string.roads_legend_unknown)) { drawHoldoutSwatch(beds) }
                        val pin = MaterialTheme.colorScheme.tertiary
                        val ring = if (style.isDark) Color(0xFF0B1410) else Color(0xFFFBFAF6)
                        LegendRow(stringResource(Res.string.roads_legend_road_end)) { drawRoadEndPin(pin, ring) }
                    } else {
                        LegendRow(stringResource(Res.string.map_legend_named_not_counted)) { drawNamedNotCountedSwatch(p, spec) }
                        LegendRow(stringResource(Res.string.status_not_counted)) { drawHairlineSwatch(p) }
                    }
                    Text(
                        stringResource(goal.s(Res.string.map_legend_footnote)),
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = BtTheme.colors.onMapChrome.copy(alpha = 0.78f),
                    )
                }
            }
            MapIconButton(Res.drawable.ic_layers, stringResource(Res.string.map_legend), onToggle, active = expanded)
        }
    }
}

@Composable
private fun LegendRow(label: String, caption: String? = null, swatch: DrawScope.() -> Unit) {
    val a11y = if (caption != null) "$label, $caption" else label
    Row(
        Modifier.heightIn(min = 28.dp).clearAndSetSemantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(36.dp, 12.dp), onDraw = swatch)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = BtTheme.colors.onMapChrome)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = BtTheme.colors.onMapChrome.copy(alpha = 0.78f))
        }
    }
}

/** Line width at z15 for [s], in px of this density. */
private fun DrawScope.z15Width(spec: StatusLayerSpec, s: TrailStatus, extra: Float = 0f): Float =
    (spec.widthAt(s, 15) + extra).dp.toPx()

private fun DrawScope.line(color: Color, width: Float, cap: StrokeCap, dash: List<Float>? = null, alpha: Float = 1f, absoluteDash: Boolean = false) {
    val y = size.height / 2
    val inset = width / 2 + 1f
    drawLine(
        color, Offset(inset, y), Offset(size.width - inset, y), width, cap,
        pathEffect = dash?.let { d -> PathEffect.dashPathEffect(d.map { if (absoluteDash) it.dp.toPx() else it * width }.toFloatArray()) },
        alpha = alpha,
    )
}

/** The map encoding of [s] (styles.md): halo-less, so it reads on the legend card. */
internal fun DrawScope.drawStatusSwatch(s: TrailStatus, p: MapPalette, spec: StatusLayerSpec = StatusLayerSpec.TRAILS) {
    val w = z15Width(spec, s)
    if (spec.hasCasing(s)) line(p.casing, z15Width(spec, s, spec.casingExtra), StrokeCap.Butt)
    val cap = if (s == TrailStatus.PARTWAY) StrokeCap.Butt else StrokeCap.Round
    line(p.of(s), w, cap, spec.dash(s))
}

private fun DrawScope.drawNamedNotCountedSwatch(p: MapPalette, spec: StatusLayerSpec) {
    val w = z15Width(spec, TrailStatus.NOT_STARTED) * StatusLayerSpec.NOT_COUNTED_NAMED_SCALE
    line(p.notStarted, w, StrokeCap.Butt, StatusLayerSpec.notCountedNamedDash)
}

private fun DrawScope.drawHairlineSwatch(p: MapPalette) {
    line(p.notCounted, 1.dp.toPx(), StrokeCap.Round, alpha = p.notCountedOpacity)
}

/** A residential bed (as at z15) with the not-started dots on it: "plain road = not walked yet". */
private fun DrawScope.drawNotWalkedRoadSwatch(p: MapPalette, beds: RoadBedColors, spec: StatusLayerSpec) {
    line(beds.residential, 8.dp.toPx(), StrokeCap.Round)
    line(p.notStarted, z15Width(spec, TrailStatus.NOT_STARTED), StrokeCap.Round, spec.dash(TrailStatus.NOT_STARTED), alpha = spec.notStartedOpacity)
}

/** Four 6dp beds, heaviest class first (state route → residential). */
private fun DrawScope.drawTypeRamp(beds: RoadBedColors) {
    val colors = listOf(beds.stateRoute, beds.arterial, beds.collector, beds.residential)
    val gap = 2.dp.toPx()
    val w = (size.width - gap * 3) / 4
    val h = 6.dp.toPx()
    colors.forEachIndexed { i, c ->
        drawRoundRect(c, Offset(i * (w + gap), (size.height - h) / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    }
}

/** Uncounted private roads: the style's muted dash [2.2, 1.8] (× width). */
private fun DrawScope.drawPrivateSwatch(beds: RoadBedColors) {
    line(beds.private, 2.4.dp.toPx(), StrokeCap.Round, listOf(2.2f, 1.8f), alpha = beds.mutedOpacity)
}

/** Ownership holdouts: the style's faint dots [0.01, 2.4] (× width). */
private fun DrawScope.drawHoldoutSwatch(beds: RoadBedColors) {
    line(beds.holdout, 2.4.dp.toPx(), StrokeCap.Round, listOf(0.01f, 2.4f), alpha = beds.mutedOpacity * 0.75f)
}

private fun DrawScope.drawRoadEndPin(fill: Color, ring: Color) {
    val r = 5.dp.toPx()
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(ring, r + 2.dp.toPx(), c)
    drawCircle(fill, r, c)
}
