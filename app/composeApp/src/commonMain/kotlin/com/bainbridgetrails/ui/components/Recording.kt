package com.bainbridgetrails.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.nav_record_a11y
import com.bainbridgetrails.resources.nav_record_active_a11y
import com.bainbridgetrails.resources.recording_pill
import com.bainbridgetrails.resources.recording_pill_a11y
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.stringResource

/** `recording` dot; pulses alpha 1 → .35 → 1 every 1.2 s unless reduced motion. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val a = if (BtTheme.motion.reduced) 1f else {
        val t = rememberInfiniteTransition()
        val v by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(BtDuration.pulse / 2, easing = BtEasing.standard), RepeatMode.Reverse))
        v
    }
    Box(modifier.size(size).alpha(a).background(BtTheme.colors.recording, PillShape).clearAndSetSemantics {})
}

/**
 * Floating pill while a walk records and the Record flow is minimized (ux.md §2.5): "Recording · 1:08:22 · 2.4 mi".
 * The spoken summary changes at most once a minute (no TalkBack spam).
 */
@Composable
fun RecordingPill(elapsedMs: Long, distanceM: Double, units: Units, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val distance = Format.distance(distanceM, units)
    val minute = elapsedMs / 60_000
    val spokenDistance = remember(minute, units) {
        distance.replace(" mi", " miles").replace(" km", " kilometres")
    }
    val a11y = stringResource(Res.string.recording_pill_a11y, remember(minute) { Format.durationSpoken(elapsedMs) }, spokenDistance)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .shadow(6.dp, PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button }
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecordingDot()
        Text(
            stringResource(Res.string.recording_pill, Format.duration(elapsedMs), distance),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The raised Record action (ux.md §2.2): 64dp `primary` disc, walking figure. While [recording]: 3dp `recording`
 * ring and a pulsing dot. Never shows a "selected" state.
 */
@Composable
fun RecordDisc(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11y = stringResource(if (recording) Res.string.nav_record_active_a11y else Res.string.nav_record_a11y)
    val size = BtTheme.spacing.recordFab
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .shadow(8.dp, PillShape)
                .background(MaterialTheme.colorScheme.primary, PillShape)
                .then(if (recording) Modifier.border(3.dp, BtTheme.colors.recording, PillShape) else Modifier)
                .clickable(onClick = onClick)
                .semantics { contentDescription = a11y; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            BtIcon(BtIcons.DirectionsWalk, null, size = 30.dp, tint = MaterialTheme.colorScheme.onPrimary)
        }
        if (recording) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, PillShape),
                contentAlignment = Alignment.Center,
            ) { RecordingDot(size = 10.dp) }
        }
    }
}
