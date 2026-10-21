package com.bainbridgetrails.ui.play

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxEasing
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxProjection
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.KrakenTimeline
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_wake_a11y
import com.bainbridgetrails.resources.play_wake_body
import com.bainbridgetrails.resources.play_wake_skip
import com.bainbridgetrails.resources.play_wake_title
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PlayDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.TimeSource

/** `play_wake` times (effects §3.17, ux §2.11): 3 s, skippable to the lift; reduced = a static card. */
object PlayWakeTimeline {
    const val TOTAL_MS = 3_000
    /** The paper lifts here; any tap before it jumps here (never a cut to the end). */
    const val LIFT_MS = 2_200
    const val HAPTIC_MS = 1_900
    /** The Play chip slides in (MapTab's chip; the wake draws nothing there). */
    const val CHIP_MS = 2_600
    /** Reduced: a short3 crossfade in, the static card for 1.2 s, a short3 crossfade out. */
    const val REDUCED_FADE_MS = 150
    const val REDUCED_CARD_MS = 1_200
    const val REDUCED_TOTAL_MS = (REDUCED_FADE_MS * 2 + REDUCED_CARD_MS).toLong()

    /** Most glyphs that pop in the wave; the rest appear at the lift with no pop. */
    const val MAX_POPS = 40
}

/**
 * The wake's clock in ms since it appeared ([elapsed]): full motion plays 0 → 3000 and a tap before the lift
 * skips forward to [PlayWakeTimeline.LIFT_MS]; reduced motion holds the pre-lift art (everything in its final place) as a
 * static card that crossfades in and out. Pure.
 */
class PlayWakeClock(val reduced: Boolean) {
    private var skipMs = 0L

    /** Art time at [elapsed]. Reduced: always the pre-lift frame. */
    fun t(elapsed: Long): Int = if (reduced) PlayWakeTimeline.LIFT_MS else (elapsed + skipMs).coerceAtMost(PlayWakeTimeline.TOTAL_MS.toLong()).toInt()

    /** A tap at [elapsed]: before the lift it skips to the lift (true); after it, nothing (taps pass to the map). */
    fun tap(elapsed: Long): Boolean {
        if (reduced) {
            val e = elapsed + skipMs
            val outAt = (PlayWakeTimeline.REDUCED_FADE_MS + PlayWakeTimeline.REDUCED_CARD_MS).toLong()
            if (e >= outAt) return false
            skipMs += outAt - e
            return true
        }
        val t = t(elapsed)
        if (t >= PlayWakeTimeline.LIFT_MS) return false
        skipMs += PlayWakeTimeline.LIFT_MS - t
        return true
    }

    /** The wake holds input only until the lift (reduced: until the card starts fading out). */
    fun blocksInput(elapsed: Long): Boolean =
        if (reduced) elapsed + skipMs < PlayWakeTimeline.REDUCED_FADE_MS + PlayWakeTimeline.REDUCED_CARD_MS else t(elapsed) < PlayWakeTimeline.LIFT_MS

    /** Alpha of the whole wake: 1 in full motion (the lift is in the art); reduced = the card's crossfades. */
    fun cardAlpha(elapsed: Long): Float {
        if (!reduced) return 1f
        val e = (elapsed + skipMs).toFloat()
        val fade = PlayWakeTimeline.REDUCED_FADE_MS.toFloat()
        return min(min(e / fade, 1f), (PlayWakeTimeline.REDUCED_TOTAL_MS - e) / fade).coerceIn(0f, 1f)
    }

    fun done(elapsed: Long): Boolean =
        if (reduced) elapsed + skipMs >= PlayWakeTimeline.REDUCED_TOTAL_MS else elapsed + skipMs >= PlayWakeTimeline.TOTAL_MS
}

/**
 * What the wake draws, in dp offsets from the map camera's centre (the wake's centre) so its coast lies on the real
 * coast (effects §3.17: the map is moved to the island camera first). [glyphs] are (x, y) pairs ordered by distance from
 * the Winslow terminal (the wave spreads from the ferry).
 */
@Immutable
class PlayWakeArt(
    val coast: List<FloatArray>,
    val glyphs: FloatArray,
    val winslow: Offset,
    val eye: Offset,
    val rose: Offset,
) {
    companion object {
        /** Bainbridge ferry terminal (the wave's origin and the horn). */
        val WINSLOW = GeoPoint(lon = -122.5106, lat = 47.6227)
        /** Where the lurk eye surfaces: the Sound off the east shore (Manitou). */
        val EYE = GeoPoint(lon = -122.4830, lat = 47.6600)
        /** The compass rose: NE water. */
        val ROSE = GeoPoint(lon = -122.4600, lat = 47.7050)

        /** Projects [coastline] rings ([lon, lat]) and [items] for a camera at [center] / [zoom]; coast ≤ 600 vertices. */
        fun project(coastline: List<List<DoubleArray>>, items: List<GeoPoint>, center: GeoPoint, zoom: Double): PlayWakeArt {
            fun x(lon: Double) = FxProjection.offsetXDp(center.lon, lon, zoom).toFloat()
            fun y(lat: Double) = FxProjection.offsetYDp(center.lat, lat, zoom).toFloat()
            var tol = 1.5f
            var rings: List<FloatArray>
            do {
                rings = coastline.map { ring ->
                    val out = ArrayList<Float>(ring.size * 2)
                    var lx = Float.NaN
                    var ly = Float.NaN
                    for (c in ring) {
                        val px = x(c[0])
                        val py = y(c[1])
                        if (lx.isNaN() || abs(px - lx) + abs(py - ly) >= tol) {
                            out += px; out += py; lx = px; ly = py
                        }
                    }
                    out.toFloatArray()
                }.filter { it.size >= 6 }
                tol *= 1.5f
            } while (rings.sumOf { it.size / 2 } > 600)
            val w = Offset(x(WINSLOW.lon), y(WINSLOW.lat))
            val sorted = items.map { Offset(x(it.lon), y(it.lat)) }.sortedBy { hypot(it.x - w.x, it.y - w.y) }
            val glyphs = FloatArray(sorted.size * 2)
            sorted.forEachIndexed { i, o -> glyphs[2 * i] = o.x; glyphs[2 * i + 1] = o.y }
            return PlayWakeArt(rings, glyphs, w, Offset(x(EYE.lon), y(EYE.lat)), Offset(x(ROSE.lon), y(ROSE.lat)))
        }

        /** Reads the bundled coastline (IO) and projects it (Default). */
        suspend fun load(items: List<GeoPoint>, center: GeoPoint, zoom: Double): PlayWakeArt {
            val json = withContext(Dispatchers.IO) { runCatching { Res.readBytes("files/coastline.json").decodeToString() }.getOrNull() }
            return withContext(Dispatchers.Default) { project(json?.let { IslandGeometry.parseCoastline(it) }.orEmpty(), items, center, zoom) }
        }
    }
}

/**
 * "The island wakes up" (effects §3.17, ux §2.11; v1's one MOMENT): full screen above the live map at the island
 * camera. A paper wash, the coast inking itself over the real coast, the compass rose, the title, water lines
 * converging onto the coast, the kraken's eye surfacing, every present item's glyph popping in a wave from Winslow,
 * the ferry's horn, then (2200–2700) the paper lifts in a radial dissolve from Winslow onto the live map. Any tap
 * before 2200 jumps to the lift; input passes to the map after it. `confirm` at 1900; the assertive line speaks on
 * appear, with a Skip custom action. Reduced: the pre-lift art as a static card for 1.2 s with short3 crossfades.
 * [frozenAtMs] (showcase only) pins the elapsed time and never calls [onDone].
 */
@Composable
fun PlayWake(art: PlayWakeArt, onDone: () -> Unit, modifier: Modifier = Modifier, frozenAtMs: Long? = null) {
    val reduced = BtTheme.motion.reduced
    val clock = remember(reduced) { PlayWakeClock(reduced) }
    val elapsed = remember { mutableLongStateOf(frozenAtMs ?: 0L) }
    val done by rememberUpdatedState(onDone)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(clock, frozenAtMs) {
        if (frozenAtMs != null) {
            elapsed.longValue = frozenAtMs
            return@LaunchedEffect
        }
        val start = TimeSource.Monotonic.markNow()
        while (!clock.done(elapsed.longValue)) withFrameMillis { elapsed.longValue = start.elapsedNow().inWholeMilliseconds }
        done()
    }
    LaunchedEffect(clock) {
        snapshotFlow { clock.t(elapsed.longValue) >= PlayWakeTimeline.HAPTIC_MS || reduced }.first { it }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val blocking by remember(clock) { derivedStateOf { clock.blocksInput(elapsed.longValue) } }
    val a11y = stringResource(Res.string.play_wake_a11y)
    val skip = stringResource(Res.string.play_wake_skip)
    val colors = BtTheme.colors
    val dark = colors.play == PlayDark
    val surface = MaterialTheme.colorScheme.surface
    val ink = colors.play.ink
    val lamp = colors.play.lamp
    val glass = colors.play.seaGlass
    val ring = colors.waterLine
    val eye = rememberVectorPainter(remember(ink) { FxArt.vector(FxGlyph.KrakenEye, ink) })
    val paper = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceBright
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = clock.cardAlpha(elapsed.longValue) }
            .then(
                if (blocking) {
                    Modifier.pointerInput(clock) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (clock.tap(elapsed.longValue)) down.consume()
                        }
                    }
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = a11y
                if (blocking) customActions = listOf(CustomAccessibilityAction(skip) { clock.tap(elapsed.longValue) })
            }
            .drawWithCache {
                val c = Offset(size.width / 2f, size.height / 2f)
                val dp = density
                val coast = art.coast.map { r ->
                    Path().apply {
                        moveTo(c.x + r[0] * dp, c.y + r[1] * dp)
                        var i = 2
                        while (i < r.size) { lineTo(c.x + r[i] * dp, c.y + r[i + 1] * dp); i += 2 }
                        close()
                    }
                }
                val coastAll = Path().apply { coast.forEach { addPath(it) } }
                val measure = PathMeasure()
                val lengths = coast.map { measure.setPath(it, false); measure.length }
                val totalLen = lengths.sum()
                val part = Path()
                val rose = rosePaths(56f * dp)
                val winslow = Offset(c.x + art.winslow.x * dp, c.y + art.winslow.y * dp)
                val diag = hypot(size.width, size.height)
                val stroke = Stroke(1.6f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    val t = clock.t(elapsed.longValue)
                    val lift = phase(t, 2_200, 2_700, FxEasing.EmphasizedAccelerate)
                    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint()) }
                    // 1 paper wash over the map (dimmed, still visible).
                    val wash = (if (dark) 0.85f else 0.82f) * phase(t, 0, 300, FxEasing.Standard)
                    drawRect(surface.copy(alpha = wash))
                    // 2 coastline inks itself.
                    val drawn = totalLen * phase(t, 100, 900, FxEasing.EmphasizedDecelerate)
                    var start = 0f
                    coast.forEachIndexed { i, r ->
                        val len = lengths[i]
                        when {
                            drawn >= start + len -> drawPath(r, ink, style = stroke)
                            drawn > start -> {
                                part.rewind()
                                measure.setPath(r, false)
                                measure.getSegment(0f, drawn - start, part, true)
                                drawPath(part, ink, style = stroke)
                            }
                        }
                        start += len
                    }
                    // 3 water lines converge onto the coast (36/24/12 dp → 0), α .8 → .3, stagger 120.
                    for (i in 0 until 3) {
                        val p = phase(t, 900 + 120 * i, 1_460 + 120 * i, FxEasing.EmphasizedDecelerate)
                        if (p <= 0f) continue
                        val from = 36f - 12f * i
                        val off = lerp(from, from / 4f, p) * dp
                        ringLine(coastAll, off, 1.5f * dp, ring.copy(alpha = 0.8f - 0.5f * p))
                    }
                    // 4 compass rose inks in at the NE water, −20° → 0°.
                    val rp = phase(t, 300, 1_100, FxEasing.EmphasizedDecelerate)
                    if (rp > 0f) {
                        val at = Offset(c.x + art.rose.x * dp, c.y + art.rose.y * dp)
                        if (dark) drawCircle(Brush.radialGradient(listOf(lamp.copy(alpha = 0.25f * rp), Color.Transparent), at, 48f * dp), 48f * dp, at)
                        translate(at.x, at.y) { rotate(-20f * (1f - rp), Offset.Zero) { drawRose(rose, ink, paper, rp, dp) } }
                    }
                    // 5 the kraken's eye surfaces off the east shore, blinks, sinks.
                    val up = phase(t, 1_200, 1_300, FxEasing.EmphasizedDecelerate) * (1f - phase(t, 1_400, 1_500, FxEasing.EmphasizedAccelerate))
                    if (up > 0f) {
                        val at = Offset(c.x + art.eye.x * dp, c.y + art.eye.y * dp)
                        val blink = if (t in 1_330..1_390) 0.15f else 1f
                        drawCircle(paper.copy(alpha = up), 11f * dp, at)
                        drawCircle(ring.copy(alpha = up), 11f * dp, at, style = Stroke(1.25f * dp))
                        scale(up, up * blink, at) { translate(at.x - 9f * dp, at.y - 9f * dp) { with(eye) { draw(Size(18f * dp, 18f * dp), alpha = up) } } }
                    }
                    // 6 glyph wave from Winslow (overshootSettle each, short4), the rest at the lift.
                    val n = art.glyphs.size / 2
                    val pops = min(n, PlayWakeTimeline.MAX_POPS)
                    for (i in 0 until n) {
                        val s0 = if (i < pops) 1_300 + 600 * i / max(1, pops) else PlayWakeTimeline.LIFT_MS
                        val s = if (i < pops) KrakenTimeline.overshootSettle(phase(t, s0, s0 + 200), 1.15f) else if (t >= s0) 1f else 0f
                        if (s <= 0f) continue
                        val at = Offset(c.x + art.glyphs[2 * i] * dp, c.y + art.glyphs[2 * i + 1] * dp)
                        drawCircle(glass, 3f * dp * s, at)
                        drawCircle(ink, 3f * dp * s, at, style = Stroke(1.5f * dp))
                    }
                    // 7 the ferry's horn: three arcs at Winslow.
                    for (i in 0 until 3) {
                        val hp = phase(t, 1_700 + 80 * i, 2_100, FxEasing.EmphasizedDecelerate)
                        if (hp <= 0f || hp >= 1f) continue
                        val r = (6f + 6f * i + 8f * hp) * dp
                        drawArc(
                            ink.copy(alpha = 1f - hp), -35f, 70f, false, Offset(winslow.x - r, winslow.y - r), Size(2 * r, 2 * r),
                            style = Stroke(1.25f * dp, cap = StrokeCap.Round),
                        )
                    }
                    // 8 the lift: the paper fades and a radial hole grows from Winslow onto the live map.
                    if (lift > 0f) {
                        drawCircle(Color.Black, diag * lift, winslow, blendMode = BlendMode.DstOut)
                        drawRect(Color.Black.copy(alpha = lift), blendMode = BlendMode.DstOut)
                    }
                    drawIntoCanvas { it.restore() }
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp).graphicsLayer {
                val t = clock.t(elapsed.longValue)
                val rise = phase(t, 500, 900, FxEasing.EmphasizedDecelerate)
                translationY = 8f * (1f - rise) * density
                alpha = rise * (1f - phase(t, 2_200, 2_500, FxEasing.Standard))
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.fillMaxHeight(0.22f))
            Text(
                stringResource(Res.string.play_wake_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.play_wake_body),
                Modifier.padding(top = 8.dp).graphicsLayer {
                    alpha = phase(clock.t(elapsed.longValue), 700, 1_000, FxEasing.EmphasizedDecelerate)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f


private fun phase(t: Int, from: Int, to: Int, easing: FxEasing = FxEasing.Linear): Float =
    easing.transform(((t - from).toFloat() / (to - from)).coerceIn(0f, 1f))

/** The 8-point engraved rose: 4 long points (alternate hatched), 4 short, a ring. [r] = radius px. */
private class Rose(val long: Path, val short: Path, val hatch: Path, val r: Float)

private fun rosePaths(size: Float): Rose {
    val r = size / 2f
    val long = Path()
    val short = Path()
    val hatch = Path()
    for (i in 0 until 8) {
        val a = -PI / 2 + i * PI / 4
        val len = if (i % 2 == 0) r else r * 0.55f
        val w = r * 0.16f
        val tip = Offset((cos(a) * len).toFloat(), (sin(a) * len).toFloat())
        val l = Offset((cos(a - PI / 2) * w).toFloat(), (sin(a - PI / 2) * w).toFloat())
        val rr = Offset((cos(a + PI / 2) * w).toFloat(), (sin(a + PI / 2) * w).toFloat())
        val p = if (i % 2 == 0) long else short
        p.moveTo(l.x, l.y); p.lineTo(tip.x, tip.y); p.lineTo(rr.x, rr.y); p.close()
        if (i % 4 == 0) {
            // Hatch the east half of the N and S points (shade on the SE side).
            for (k in 1..3) {
                val f = k / 4f
                hatch.moveTo(tip.x * f, tip.y * f); hatch.lineTo(rr.x * (1 - f), rr.y * (1 - f))
            }
        }
    }
    return Rose(long, short, hatch, r)
}

private fun DrawScope.drawRose(rose: Rose, ink: Color, paper: Color, p: Float, dp: Float) {
    val a = p
    scale(0.8f + 0.2f * p, 0.8f + 0.2f * p, Offset.Zero) {
        drawCircle(ink.copy(alpha = a), rose.r * 0.62f, Offset.Zero, style = Stroke(1.25f * dp))
        drawPath(rose.short, paper.copy(alpha = a))
        drawPath(rose.short, ink.copy(alpha = a), style = Stroke(1.25f * dp, join = StrokeJoin.Round))
        drawPath(rose.long, paper.copy(alpha = a))
        drawPath(rose.long, ink.copy(alpha = a), style = Stroke(2f * dp, join = StrokeJoin.Round))
        drawPath(rose.hatch, ink.copy(alpha = a), style = Stroke(1f * dp))
        drawCircle(ink.copy(alpha = a), 2f * dp, Offset.Zero)
    }
}

/** A [w]-wide water line [offset] outside (and inside) the coast: a wide stroke with its middle cleared (IslandCelebration's method). */
private fun DrawScope.ringLine(path: Path, offset: Float, w: Float, color: Color) {
    val outer = 2 * offset + w
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        drawPath(path, color, style = Stroke(outer, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.Black, style = Stroke(max(outer - 2 * w, 0f), cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
        canvas.restore()
    }
}
