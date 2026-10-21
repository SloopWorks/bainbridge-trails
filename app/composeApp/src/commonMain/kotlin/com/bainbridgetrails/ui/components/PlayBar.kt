package com.bainbridgetrails.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme

/**
 * Play progress bar (docs/play/ux.md §2.12): 6dp rounded, track `surfaceContainerHighest`, fill `play.seaGlass`,
 * optional single [tick] (`outline`, 2 × 10dp; e.g. the kraken's 60 %). Never the status bar's ticks or hues.
 * First show fills over long2 `emphasizedDecelerate` (as status bars); later changes medium1; reduced motion jumps.
 * [contentDescription] is the spoken value (e.g. `play_challenge_progress` "1 of 3"); range info is set too.
 */
@Composable
fun PlayBar(
    fraction: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tick: Float? = null,
    animationDelayMs: Int = 0,
) {
    val target = fraction.coerceIn(0f, 1f)
    val motion = BtTheme.motion
    val anim = remember { Animatable(if (motion.reduced) target else 0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(target, motion.reduced) {
        when {
            motion.reduced -> anim.snapTo(target)
            first[0] -> anim.animateTo(target, motion.tweenOrSnap(BtDuration.long2, BtEasing.emphasizedDecelerate, animationDelayMs))
            else -> anim.animateTo(target, motion.tweenOrSnap(BtDuration.medium1, BtEasing.standard))
        }
        first[0] = false
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = BtTheme.colors.play.seaGlass
    val tickColor = MaterialTheme.colorScheme.outline
    val barHeight = BtTheme.spacing.playBar
    // With a tick the row is md (12dp) tall so the 10dp tick clears the 6dp bar.
    val rowHeight = if (tick != null) BtTheme.spacing.md else barHeight
    Canvas(
        modifier.fillMaxWidth().height(rowHeight).clearAndSetSemantics {
            this.contentDescription = contentDescription
            progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
        },
    ) {
        val h = barHeight.toPx()
        val top = (size.height - h) / 2
        val cr = CornerRadius(h / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, h), cr)
        val shown = anim.value
        if (shown > 0f) drawRoundRect(fill, Offset(0f, top), Size(maxOf(h, size.width * shown), h), cr)
        if (tick != null) {
            val tw = 2.dp.toPx()
            val th = 10.dp.toPx()
            drawRect(tickColor, Offset(size.width * tick.coerceIn(0f, 1f) - tw / 2, (size.height - th) / 2), Size(tw, th))
        }
    }
}
