package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.play_riddle_answer
import com.bainbridgetrails.resources.play_riddle_claimed
import com.bainbridgetrails.resources.play_riddle_pick_list
import com.bainbridgetrails.resources.play_riddle_pick_on_map
import com.bainbridgetrails.resources.play_riddle_solved_stamp
import com.bainbridgetrails.resources.play_riddle_stars_a11y
import com.bainbridgetrails.resources.play_riddle_wrong
import com.bainbridgetrails.resources.play_walk_there
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.SecondaryButton
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberRiddleCopy(): RiddleCopy = RiddleCopy(
    stringResource(Res.string.play_riddle_wrong), stringResource(Res.string.play_riddle_solved_stamp), stringResource(Res.string.play_riddle_claimed),
)

/**
 * RiddleSheet's riddle block inside the EncounterSheet (ux.md §2.4): ★ difficulty, the clue with an ink cartouche
 * edge, then by stage: the answer row (map pick + list pick, PF8-5) and the gentle wrong line (PF8-6, no red, no ✕),
 * the solved stamp + Walk there, or the claimed line.
 */
@Composable
fun ColumnScope.RiddleBlock(
    ui: RiddleUi,
    stars: Int,
    onPickMap: () -> Unit,
    onPickList: () -> Unit,
    onWalkThere: (() -> Unit)?,
) {
    val starsA11y = pluralStringResource(Res.plurals.play_riddle_stars_a11y, stars, stars)
    Text("★".repeat(stars.coerceIn(1, 3)), Modifier.clearAndSetSemantics { contentDescription = starsA11y }, color = BtTheme.colors.play.ink)
    ui.stamp?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge) }
    ui.claimed?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    if (ui.answerRow) {
        Text(stringResource(Res.string.play_riddle_answer), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.mapPicker) SecondaryButton(stringResource(Res.string.play_riddle_pick_on_map), onPickMap, Modifier.weight(1f))
            if (ui.listPicker) SecondaryButton(stringResource(Res.string.play_riddle_pick_list), onPickList, Modifier.weight(1f))
        }
        ui.message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    }
    if (ui.walkThere && onWalkThere != null) {
        PrimaryButton(stringResource(Res.string.play_walk_there), onWalkThere, Modifier.fillMaxWidth())
    }
}

/** Pick mode (ux.md §2.4): the sheet collapses to a 72dp bar; map taps go to the answer hit test until Cancel. */
@Composable
fun RiddlePickBar(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .padding(start = BtTheme.spacing.screenH, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.play_riddle_pick_on_map), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
    }
}
