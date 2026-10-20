package com.bainbridgetrails.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.roads_milestone_a11y
import com.bainbridgetrails.resources.roads_milestone_eyebrow
import com.bainbridgetrails.resources.roads_milestone_more
import com.bainbridgetrails.ui.Milestone
import com.bainbridgetrails.ui.MilestoneState
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Full timeline of the card (docs/roads/ux.md §6.1). */
private const val CARD_MS = 1200

/** The before → after counter of a milestone card: [format] renders a value ("4.6", "9"), [unit] follows ("mi"). */
data class MilestoneCounter(val before: Double, val after: Double, val format: (Double) -> String, val unit: String)

/**
 * Results milestone card (docs/roads/ux.md §6.1): the highest milestone this walk crossed, not a modal. The medallion
 * stamps in (0.6 → 1.08 → 1.0) while its primary ring sweeps, a haptic at 250 ms, the title rises, the counter runs
 * old → new in a fixed-width box. Reduced motion: final frame, haptic once. One merged polite live region.
 * [more] > 0 shows "+N more" → [onMore] (the milestones list).
 */
@Composable
fun MilestoneCard(
    milestone: Milestone,
    counter: MilestoneCounter?,
    body: String?,
    more: Int,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduced = BtTheme.motion.reduced
    val haptics = LocalHapticFeedback.current
    val clock = remember(milestone) { Animatable(if (reduced) CARD_MS.toFloat() else 0f) }
    LaunchedEffect(milestone, reduced) {
        if (reduced) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        } else {
            launch { delay(250); haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
            clock.animateTo(CARD_MS.toFloat(), tween(CARD_MS, easing = LinearEasing))
        }
    }
    val t = clock.value
    fun phase(from: Int, to: Int) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    val title = milestoneTitle(milestone)
    val counterText: (Double) -> String = { v -> counter?.let { "${it.format(v)} ${it.unit}".trim() }.orEmpty() }
    val finalCounter = counter?.let { "${it.format(it.before)} → ${counterText(it.after)}" }
    val a11y = stringResource(Res.string.roads_milestone_a11y, title) + (finalCounter?.let { " $it." } ?: "") + (body?.let { " $it" } ?: "")
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)

    Column(modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.large)) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y; liveRegion = LiveRegionMode.Polite }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Stamp: 0.6 → 1.08 (first 2/3) → 1.0, emphasized decelerate.
            val stamp = phase(0, 300)
            val s = if (stamp < 0.66f) 0.6f + 0.48f * BtEasing.emphasizedDecelerate.transform(stamp / 0.66f) else 1.08f - 0.08f * ((stamp - 0.66f) / 0.34f)
            Box(Modifier.graphicsLayer { scaleX = s; scaleY = s; alpha = if (reduced) 1f else (stamp * 3).coerceAtMost(1f) }) {
                MilestoneMedallion(milestoneMark(milestone), MilestoneState.EARNED, 1.0, sweep = phase(0, 600))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val rise = phase(200, 500)
                Column(Modifier.graphicsLayer { alpha = rise; translationY = (1 - rise) * 6.dp.toPx() }) {
                    Text(stringResource(Res.string.roads_milestone_eyebrow).uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp), color = primary)
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                if (counter != null) {
                    val p = BtEasing.emphasizedDecelerate.transform(phase(300, 1000))
                    val v = counter.before + (counter.after - counter.before) * p
                    Box(contentAlignment = Alignment.CenterStart) {
                        // Fixed width: the widest text it will show, invisible (tnum keeps digits equal).
                        Text("${counter.format(counter.before)} → ${counterText(counter.after)}", style = BtTheme.type.numeric, modifier = Modifier.graphicsLayer { alpha = 0f })
                        Text("${counter.format(counter.before)} → ${counterText(v)}", style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (body != null) Text(body, Modifier.padding(top = 2.dp).graphicsLayer { alpha = phase(500, 900) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (more > 0 && onMore != null) {
            TextButton(onClick = onMore, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) {
                Text(stringResource(Res.string.roads_milestone_more, more))
            }
        }
    }
}
