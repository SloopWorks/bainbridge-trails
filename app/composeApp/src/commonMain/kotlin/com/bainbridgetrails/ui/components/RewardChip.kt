package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_celebrate_marks
import com.bainbridgetrails.resources.play_log_line
import com.bainbridgetrails.resources.play_reward
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.stringResource

/**
 * What a Play item is worth (docs/play/ux.md §2.12): 32dp neutral pill `surfaceContainerHigh`, survey mark △ 16dp in
 * `play.seaGlass` + `play_reward` ("25 marks") in `labelLarge` `onSurface`. [claimed] shows `play_log_line` ("+25")
 * in `marksDelta`, still `onSurface` (sea-glass text is allowed only on surface / surfaceContainerLow / mapChrome).
 * [marks] is already formatted (thousands separator, product §3.1). Non-interactive; one spoken node. Never
 * rendered for remembrance (no numbers there, PF4-4): callers leave it out.
 */
@Composable
fun RewardChip(marks: String, modifier: Modifier = Modifier, claimed: Boolean = false) {
    val text = if (claimed) stringResource(Res.string.play_log_line, marks) else stringResource(Res.string.play_reward, marks)
    val spoken = if (claimed) stringResource(Res.string.play_celebrate_marks, marks) else text
    Row(
        modifier
            .heightIn(min = 32.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape)
            .padding(start = 10.dp, end = 12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FxGlyphImage(FxGlyph.Marks, size = 16.dp, ink = BtTheme.colors.play.seaGlass)
        Text(
            text,
            style = if (claimed) BtTheme.type.marksDelta else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
