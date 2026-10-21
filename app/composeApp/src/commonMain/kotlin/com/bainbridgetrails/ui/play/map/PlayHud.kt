package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_map_chip
import com.bainbridgetrails.resources.play_map_chip_a11y
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.stringResource

/**
 * The Play chip (ux.md §2.1): `mapChrome` pill, △ marks glyph in sea glass + `play_map_chip` ("1,240 · Salal Scout")
 * in `labelLarge` tnum; [compact] drops the rank title. [total] null = loading (glyph + skeleton). Tap → Logbook.
 */
@Composable
fun PlayHud(total: Int?, rankTitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val marks = total?.let { PlayMarkerLogic.chipMarks(it) }
    val spoken = stringResource(Res.string.play_map_chip_a11y, PlayFormat.marks(total ?: 0), rankTitle)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            // Clickable before the visual inset: the 48dp touch target is also the semantic bounds (ux.md §2.1).
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button; onClick { onClick(); true } }
            .padding(vertical = 4.dp)
            .shadow(3.dp, PillShape)
            .background(BtTheme.colors.mapChrome, PillShape)
            .clip(PillShape)
            .indication(interaction, ripple())
            .widthIn(min = 96.dp)
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FxGlyphImage(FxGlyph.Marks, size = 18.dp, ink = BtTheme.colors.play.seaGlass)
        if (marks == null) {
            Box(Modifier.size(40.dp, 12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall))
        } else {
            Text(
                if (compact) marks else stringResource(Res.string.play_map_chip, marks, rankTitle),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = BtTheme.colors.onMapChrome,
                maxLines = 1,
            )
        }
    }
}
