package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.bainbridgetrails.resources.play_challenges_chip
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.pluralStringResource

/** ChallengesChip (ux.md §2.1): pennant + "2 challenges this week" ([compact]: the count only; a11y keeps the phrase). */
@Composable
fun ChallengesChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = pluralStringResource(Res.plurals.play_challenges_chip, count, count)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .shadow(3.dp, PillShape)
            .background(BtTheme.colors.mapChrome, PillShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } }
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FxGlyphImage(FxGlyph.Pennant, size = 18.dp, ink = BtTheme.colors.play.ink)
        Text(if (compact) "$count" else label, style = MaterialTheme.typography.labelLarge, color = BtTheme.colors.onMapChrome, maxLines = 1)
    }
}
