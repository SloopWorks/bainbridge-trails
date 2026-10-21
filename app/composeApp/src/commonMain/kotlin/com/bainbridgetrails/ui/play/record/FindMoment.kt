package com.bainbridgetrails.ui.play.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.map_close
import com.bainbridgetrails.resources.play_find_a11y
import com.bainbridgetrails.resources.play_find_provisional
import com.bainbridgetrails.resources.play_log_line
import com.bainbridgetrails.ui.play.map.ArtTile
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** What the FindMoment card shows for one live find (provisional until Finish, PD-14). */
data class FindMomentModel(val key: String, val glyph: FxGlyph, val title: String, val spokenName: String, val marks: Int)

/**
 * FindMoment (ux.md §3.8, §4.2; PF5-1): screen-anchored chrome card under the chips row, `mapChrome`, art 48dp,
 * title, `play_find_provisional`, trailing provisional "+25" in sea glass; tap dismisses (the host auto-dismisses
 * after 4 s). Announces `play_find_a11y` politely; "Dismiss" custom action.
 */
@Composable
fun FindMoment(model: FindMomentModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spoken = stringResource(Res.string.play_find_a11y, model.spokenName, model.marks.toString())
    val dismiss = stringResource(Res.string.map_close)
    Row(
        modifier.widthIn(max = 400.dp).fillMaxWidth().shadow(6.dp, MaterialTheme.shapes.large)
            .background(BtTheme.colors.mapChrome, MaterialTheme.shapes.large)
            .clickable(onClick = onDismiss)
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
                customActions = listOf(CustomAccessibilityAction(dismiss) { onDismiss(); true })
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtTile(model.glyph, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium, color = BtTheme.colors.onMapChrome, maxLines = 2)
            Text(stringResource(Res.string.play_find_provisional), style = MaterialTheme.typography.bodySmall, color = BtTheme.colors.onMapChrome.copy(alpha = 0.78f))
        }
        if (model.marks > 0) Text(stringResource(Res.string.play_log_line, model.marks.toString()), style = BtTheme.type.marksDelta, color = BtTheme.colors.play.seaGlass)
    }
}
