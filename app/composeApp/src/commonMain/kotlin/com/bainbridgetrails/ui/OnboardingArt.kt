package com.bainbridgetrails.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.RoadBedColors
import com.bainbridgetrails.ui.components.StatusChip
import com.bainbridgetrails.ui.theme.BtTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** Roads onboarding art (docs/roads/ux.md §7), pure parts kept testable. */
internal object OnboardingArt {
    /** Bainbridge ferry terminal, Winslow. */
    private const val FERRY_LON = -122.5106
    private const val FERRY_LAT = 47.6227
    private const val RADIUS_M = 1200.0

    /**
     * Demo statuses for the P1 island: counted segments within [RADIUS_M] of the ferry terminal (any vertex), cycled
     * complete / nearly done / partway in segment order — deterministic, so every launch draws the same picture.
     */
    fun winslowDemo(dataset: Dataset): Map<String, TrailStatus> {
        val k = cos(FERRY_LAT * PI / 180)
        val cycle = listOf(TrailStatus.COMPLETE, TrailStatus.COMPLETE, TrailStatus.NEARLY_DONE, TrailStatus.PARTWAY)
        val out = HashMap<String, TrailStatus>()
        var i = 0
        for (s in dataset.segments) {
            if (!s.included) continue
            val near = s.coords.any { c -> hypot((c[0] - FERRY_LON) * 111_320.0 * k, (c[1] - FERRY_LAT) * 110_574.0) <= RADIUS_M }
            if (near) out[s.id] = cycle[i++ % cycle.size]
        }
        return out
    }
}

/**
 * P2 "How a road fills in" (ux.md §7): a 3×3 road-grid fragment — plain steel beds, one wider diagonal arterial —
 * where one street steps once through the map encoding: bed only → gray dots → partway dash → nearly done (casing) →
 * complete violet, like a centreline being painted. Reduced motion: four parallel streets, one per state, static.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoadGridFillIllustration(visible: Boolean, modifier: Modifier) {
    val colors = BtTheme.colors
    val reduced = BtTheme.motion.reduced
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val beds = if (dark) RoadBedColors.Dark else RoadBedColors.Light
    val land = MaterialTheme.colorScheme.background
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(visible) { if (visible && !reduced) { progress.snapTo(0f); progress.animateTo(1f, tween(1800)) } }
    Column(modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xl)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width
            val h = size.height
            val bed = 9.dp.toPx()
            fun street(a: Offset, b: Offset, color: Color = beds.residential, width: Float = bed) = drawLine(color, a, b, width, StrokeCap.Round)
            if (reduced) {
                // Four parallel streets, one per state.
                val states = listOf(TrailStatus.NOT_STARTED, TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE, TrailStatus.COMPLETE)
                states.forEachIndexed { i, s ->
                    val y = h * (0.14f + 0.24f * i)
                    val a = Offset(w * 0.08f, y)
                    val b = Offset(w * 0.92f, y)
                    street(a, b)
                    paint(s, a, b, colors, land)
                }
                return@Canvas
            }
            // Grid: two avenues, two streets, one diagonal arterial (wider, darker steel bed).
            val xs = listOf(w * 0.30f, w * 0.70f)
            val ys = listOf(h * 0.30f, h * 0.72f)
            street(Offset(w * 0.04f, h * 0.95f), Offset(w * 0.96f, h * 0.05f), beds.arterial, bed * 1.7f)
            for (x in xs) street(Offset(x, h * 0.06f), Offset(x, h * 0.94f))
            for (y in ys) street(Offset(w * 0.04f, y), Offset(w * 0.96f, y))
            // The street being walked: the lower cross street.
            val a = Offset(w * 0.04f, ys[1])
            val b = Offset(w * 0.96f, ys[1])
            val p = progress.value
            val s = when {
                p < 0.2f -> null
                p < 0.4f -> TrailStatus.NOT_STARTED
                p < 0.6f -> TrailStatus.PARTWAY
                p < 0.8f -> TrailStatus.NEARLY_DONE
                else -> TrailStatus.COMPLETE
            }
            if (s != null) paint(s, a, b, colors, land)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            TrailStatus.entries.forEach { StatusChip(it) }
        }
    }
}

/** One street's status in the map's encoding (dots / dash + casing / solid + casing / thick), over a halo gap. */
private fun DrawScope.paint(s: TrailStatus, a: Offset, b: Offset, colors: com.bainbridgetrails.ui.theme.BtExtraColors, land: Color) {
    val dp = 1.dp.toPx()
    when (s) {
        TrailStatus.NOT_STARTED -> drawLine(colors.notStarted.line, a, b, 3 * dp, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, 8 * dp)))
        TrailStatus.PARTWAY -> {
            drawLine(land, a, b, 6 * dp, StrokeCap.Round)
            drawLine(colors.partway.line, a, b, 4 * dp, StrokeCap.Butt, PathEffect.dashPathEffect(floatArrayOf(11 * dp, 6 * dp)))
        }
        TrailStatus.NEARLY_DONE -> {
            drawLine(land, a, b, 8 * dp, StrokeCap.Round)
            drawLine(colors.nearlyDoneCasing, a, b, 6.5f * dp, StrokeCap.Round)
            drawLine(colors.nearlyDone.line, a, b, 4.2f * dp, StrokeCap.Round)
        }
        TrailStatus.COMPLETE -> {
            drawLine(land, a, b, 8 * dp, StrokeCap.Round)
            drawLine(colors.complete.line, a, b, 6 * dp, StrokeCap.Round)
        }
    }
}
