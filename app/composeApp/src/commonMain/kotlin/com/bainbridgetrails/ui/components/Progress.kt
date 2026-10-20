package com.bainbridgetrails.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.progress_a11y
import com.bainbridgetrails.resources.progress_bar_a11y
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.of
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Fill animation shared by ring and bar: first show fills from 0 over long2 (after [delayMs], for row stagger), later
 * changes ease over medium1; reduced motion jumps straight to the value.
 */
@Composable
private fun animatedFraction(target: Float, delayMs: Int): Float {
    val motion = BtTheme.motion
    val anim = remember { Animatable(if (motion.reduced) target else 0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(target, motion.reduced) {
        if (motion.reduced) {
            anim.snapTo(target)
        } else if (first[0]) {
            anim.animateTo(target, motion.tweenOrSnap(BtDuration.long2, BtEasing.emphasizedDecelerate, delayMs))
        } else {
            anim.animateTo(target, motion.tweenOrSnap(BtDuration.medium1, BtEasing.standard))
        }
        first[0] = false
    }
    return anim.value
}

/** Stagger delay for the [index]th row's fill (30 ms per row, capped at 8 rows). */
fun fillStaggerMs(index: Int): Int = index.coerceIn(0, BtDuration.staggerRows) * BtDuration.stagger

/**
 * Circular progress (ux.md §4). Dotted `outlineVariant` track (a 0% ring reads "not started" by shape), solid `accent`
 * arc from 12 o'clock; COMPLETE draws a check instead of the %. [gained] = share added this walk: the earlier part is
 * drawn at 40% alpha. Fractions are Double so the label rounds down the real value (a Float 0.42 is 0.4199…). Label = whole % rounded down ([Format.percentWhole]).
 */
@Composable
fun ProgressRing(
    fraction: Double,
    status: TrailStatus,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    stroke: Dp = size / 10,
    gained: Double? = null,
    showLabel: Boolean = size >= 36.dp,
    animationDelayMs: Int = 0,
) {
    val accent = BtTheme.colors.of(status).accent
    val track = MaterialTheme.colorScheme.outlineVariant
    // "Before" share: 40% accent pre-blended over surface so the dotted track doesn't show through.
    val faded = lerp(MaterialTheme.colorScheme.surface, accent, 0.4f)
    val shown = animatedFraction(fraction.toFloat().coerceIn(0f, 1f), animationDelayMs)
    val a11y = stringResource(Res.string.progress_a11y, Format.percent(fraction), stringResource(status.label))
    val complete = status == TrailStatus.COMPLETE
    Box(
        modifier.size(size).clearAndSetSemantics {
            contentDescription = a11y
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat().coerceIn(0f, 1f), 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val r = (this.size.minDimension - sw) / 2
            val tl = Offset(center.x - r, center.y - r)
            val box = Size(2 * r, 2 * r)
            drawCircle(
                track, r, center,
                style = Stroke(sw * 0.8f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, sw * 2f))),
            )
            val before = gained?.let { (shown - it.toFloat()).coerceAtLeast(0f) } ?: shown
            if (before > 0f) {
                drawArc(if (gained != null) faded else accent, -90f, 360f * before, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (gained != null && shown > before) {
                drawArc(accent, -90f + 360f * before, 360f * (shown - before), false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        if (complete) {
            Canvas(Modifier.size(size * 0.42f)) {
                val d = this.size.minDimension
                val p = Path().apply {
                    moveTo(d * 0.12f, d * 0.52f)
                    lineTo(d * 0.4f, d * 0.78f)
                    lineTo(d * 0.9f, d * 0.24f)
                }
                drawPath(p, accent, style = Stroke(d * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else if (showLabel) {
            Text(Format.percentWhole(fraction), style = BtTheme.type.numericLabel, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/**
 * Linear progress (ux.md §4): rounded `surfaceContainerHighest` track, `accent` fill, `outline` ticks at the
 * nearly-done / complete [thresholds] (defaults from [CoverageParams]).
 */
@Composable
fun ProgressBar(
    fraction: Double,
    status: TrailStatus,
    modifier: Modifier = Modifier,
    thresholds: List<Float> = DefaultThresholds,
    height: Dp = 8.dp,
    animationDelayMs: Int = 0,
) {
    val accent = BtTheme.colors.of(status).accent
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline
    val shown = animatedFraction(fraction.toFloat().coerceIn(0f, 1f), animationDelayMs)
    val a11y = stringResource(
        Res.string.progress_bar_a11y,
        Format.percent(fraction),
        ((thresholds.getOrNull(0) ?: 0f) * 100).roundToInt(),
        ((thresholds.getOrNull(1) ?: 0f) * 100).roundToInt(),
    )
    Canvas(
        modifier.fillMaxWidth().height(maxOf(height, 12.dp)).clearAndSetSemantics {
            contentDescription = a11y
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat().coerceIn(0f, 1f), 0f..1f)
        },
    ) {
        val h = height.toPx()
        val top = (size.height - h) / 2
        val cr = CornerRadius(h / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, h), cr)
        if (shown > 0f) drawRoundRect(accent, Offset(0f, top), Size(maxOf(h, size.width * shown), h), cr)
        val tw = 2.dp.toPx()
        for (t in thresholds) {
            val x = size.width * t - tw / 2
            drawRect(tick, Offset(x, 0f), Size(tw, size.height))
        }
    }
}

private val DefaultThresholds = CoverageParams().let { listOf(it.nearlyDoneThreshold.toFloat(), it.completionThreshold.toFloat()) }
