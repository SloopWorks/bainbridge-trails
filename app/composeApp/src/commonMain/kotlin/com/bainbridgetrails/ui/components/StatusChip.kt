package com.bainbridgetrails.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.status_complete
import com.bainbridgetrails.resources.status_nearly_done
import com.bainbridgetrails.resources.status_not_started
import com.bainbridgetrails.resources.status_partway
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import com.bainbridgetrails.ui.theme.of
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The four status labels (product.md §3). */
val TrailStatus.label: StringResource
    get() = when (this) {
        TrailStatus.NOT_STARTED -> Res.string.status_not_started
        TrailStatus.PARTWAY -> Res.string.status_partway
        TrailStatus.NEARLY_DONE -> Res.string.status_nearly_done
        TrailStatus.COMPLETE -> Res.string.status_complete
    }

/**
 * Status pill: glyph + label in `onContainer` on `container` (the only place status-colored text is allowed).
 * Non-interactive. [compact] = glyph only, label kept as the content description.
 */
@Composable
fun StatusChip(status: TrailStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = BtTheme.colors.of(status)
    val spec = BtTheme.motion.tweenOrSnap<androidx.compose.ui.graphics.Color>(BtDuration.medium1)
    val bg by animateColorAsState(colors.container, spec)
    val fg by animateColorAsState(colors.onContainer, spec)
    val label = stringResource(status.label)
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(bg, PillShape)
            .padding(start = if (compact) 7.dp else 8.dp, end = if (compact) 7.dp else 10.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(status, size = 14.dp, color = fg)
        if (!compact) Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}
