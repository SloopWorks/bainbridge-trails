package com.bainbridgetrails.ui.play.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.play.screens.WhatsOnGate
import com.bainbridgetrails.ui.play.screens.WhatsOnKind
import com.bainbridgetrails.ui.theme.BtTheme

/** The art tile glyph of a What's On row (placeholder art, ux § 7.2 #4). */
fun WhatsOnKind.glyph(): FxGlyph = when (this) {
    WhatsOnKind.Kraken -> FxGlyph.KrakenEye
    WhatsOnKind.Treasures -> FxGlyph.SeaGlass
    WhatsOnKind.Riddle -> FxGlyph.LanternUnlit
    WhatsOnKind.Challenge -> FxGlyph.Pennant
    WhatsOnKind.Memorial -> FxGlyph.QuietRing
    WhatsOnKind.Other -> FxGlyph.Marks
}

/**
 * WhatsOnRow (ux.md § 2.8): ≥ 64dp; art tile 40dp; [title] `titleSmall` one line; [window] `bodySmall`
 * `onSurfaceVariant` (island-time days/times; `play_island_time` already appended when the phone differs); trailing
 * gate glyph (wave / moon, 20dp) or a chevron. [spoken] is the merged sentence ("The kraken is at Manitou, Thursday 6
 * am to Sunday 8 pm, waits for the tide"). A remembrance row ([gate] None) has no chevron, no motion, no number.
 * [onClick] null = not tappable.
 */
@Composable
fun WhatsOnRow(
    kind: WhatsOnKind,
    title: String,
    window: String?,
    gate: WhatsOnGate,
    spoken: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) role = Role.Button
            }
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        Box(
            Modifier.size(BtTheme.spacing.playArtTileRow).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { FxGlyphImage(kind.glyph(), size = 24.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (window != null) Text(window, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (gate) {
            WhatsOnGate.Wave -> FxGlyphImage(FxGlyph.GateWave, size = 20.dp, ink = MaterialTheme.colorScheme.onSurfaceVariant)
            WhatsOnGate.Moon -> FxGlyphImage(FxGlyph.GateMoon, size = 20.dp, ink = MaterialTheme.colorScheme.onSurfaceVariant)
            WhatsOnGate.Chevron -> if (onClick != null) BtIcon(BtIcons.ChevronRight, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            WhatsOnGate.None -> Unit
        }
    }
}
