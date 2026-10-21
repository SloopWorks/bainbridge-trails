package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_legend_fog
import com.bainbridgetrails.resources.play_legend_group
import com.bainbridgetrails.resources.play_legend_kraken
import com.bainbridgetrails.resources.play_legend_lore
import com.bainbridgetrails.resources.play_legend_riddle
import com.bainbridgetrails.resources.play_legend_treasure
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The legend's Play group (ux.md §2.12): header `play_legend_group` and 40dp rows of Play art + label. Shown with the
 * expanded legend; Play art only, never a status pattern.
 */
@Composable
fun PlayLegendGroup(modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 280.dp).shadow(3.dp, MaterialTheme.shapes.large)
            .background(BtTheme.colors.mapChrome, MaterialTheme.shapes.large).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(stringResource(Res.string.play_legend_group), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium, color = BtTheme.colors.onMapChrome)
        LegendRow(FxGlyph.QuietRing, Res.string.play_legend_fog)
        LegendRow(FxGlyph.SeaGlass, Res.string.play_legend_treasure)
        LegendRow(FxGlyph.KrakenEye, Res.string.play_legend_kraken)
        LegendRow(FxGlyph.LanternUnlit, Res.string.play_legend_riddle)
        LegendRow(FxGlyph.Pennant, Res.string.play_legend_lore)
    }
}

@Composable
private fun LegendRow(glyph: FxGlyph, label: StringResource) {
    val text = stringResource(label)
    Row(Modifier.heightIn(min = 40.dp).clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FxGlyphImage(glyph, Modifier.size(32.dp, 16.dp), size = 16.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = BtTheme.colors.onMapChrome)
    }
}
