package com.bainbridgetrails.ui.play.components

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
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_rank_1
import com.bainbridgetrails.resources.play_rank_10
import com.bainbridgetrails.resources.play_rank_10_flavour
import com.bainbridgetrails.resources.play_rank_11
import com.bainbridgetrails.resources.play_rank_11_flavour
import com.bainbridgetrails.resources.play_rank_1_flavour
import com.bainbridgetrails.resources.play_rank_2
import com.bainbridgetrails.resources.play_rank_2_flavour
import com.bainbridgetrails.resources.play_rank_3
import com.bainbridgetrails.resources.play_rank_3_flavour
import com.bainbridgetrails.resources.play_rank_4
import com.bainbridgetrails.resources.play_rank_4_flavour
import com.bainbridgetrails.resources.play_rank_5
import com.bainbridgetrails.resources.play_rank_5_flavour
import com.bainbridgetrails.resources.play_rank_6
import com.bainbridgetrails.resources.play_rank_6_flavour
import com.bainbridgetrails.resources.play_rank_7
import com.bainbridgetrails.resources.play_rank_7_flavour
import com.bainbridgetrails.resources.play_rank_8
import com.bainbridgetrails.resources.play_rank_8_flavour
import com.bainbridgetrails.resources.play_rank_9
import com.bainbridgetrails.resources.play_rank_9_flavour
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val RANK_TITLES = listOf(
    Res.string.play_rank_1, Res.string.play_rank_2, Res.string.play_rank_3, Res.string.play_rank_4, Res.string.play_rank_5,
    Res.string.play_rank_6, Res.string.play_rank_7, Res.string.play_rank_8, Res.string.play_rank_9, Res.string.play_rank_10,
    Res.string.play_rank_11,
)
private val RANK_FLAVOURS = listOf(
    Res.string.play_rank_1_flavour, Res.string.play_rank_2_flavour, Res.string.play_rank_3_flavour, Res.string.play_rank_4_flavour,
    Res.string.play_rank_5_flavour, Res.string.play_rank_6_flavour, Res.string.play_rank_7_flavour, Res.string.play_rank_8_flavour,
    Res.string.play_rank_9_flavour, Res.string.play_rank_10_flavour, Res.string.play_rank_11_flavour,
)

/** `play_rank_<n>`: the ladder's title in the app's copy (product § 3.7.1). */
val Rank.titleRes: StringResource get() = RANK_TITLES[(number - 1).coerceIn(0, RANK_TITLES.lastIndex)]

/** `play_rank_<n>_flavour`. */
val Rank.flavourRes: StringResource get() = RANK_FLAVOURS[(number - 1).coerceIn(0, RANK_FLAVOURS.lastIndex)]

/**
 * RankBadge (ux.md § 2.7): 28dp pill, `tertiaryContainer`, `onTertiaryContainer` `labelMedium`; leading 14dp survey
 * mark △ with 0–3 rank ticks (ranks 1–3 one, 4–7 two, 8–11 three) drawn in `onTertiaryContainer` (engraved brass).
 * Non-interactive; speaks the title.
 */
@Composable
fun RankBadge(rank: Rank, modifier: Modifier = Modifier) {
    val title = stringResource(rank.titleRes)
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, PillShape)
            .padding(start = 8.dp, end = 10.dp)
            .clearAndSetSemantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FxGlyphImage(FxGlyph.Marks, size = 14.dp, ink = fg, lamp = fg, ticks = FxArt.ticksForRank(rank.number))
        Text(title, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}
