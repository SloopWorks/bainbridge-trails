package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_walk_there
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * KrakenStretchRow (ux.md §2.3): 64dp; name, a PlayBar with the 60 % tick and the best share walked in one walk, the
 * state text; a 48dp walk button only while the stretch is open and not yet repelled (absent when gated, PF4-2).
 * Row tap → [onFocus] (camera fits the stretch, the sheet stays). One merged spoken node.
 */
@Composable
fun KrakenStretchRow(row: StretchRow, minShare: Double, onFocus: () -> Unit, onWalk: () -> Unit, modifier: Modifier = Modifier) {
    val spoken = "${row.name}, ${row.text.replace("%", " percent")}"
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onFocus),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            PlayBar((row.share ?: if (row.repelled) 1.0 else 0.0).toFloat(), row.text, tick = minShare.toFloat())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.repelled) BtIcon(BtIcons.Check, null, size = 16.dp, tint = BtTheme.colors.play.seaGlass)
                Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.walkable) MapIconButton(BtIcons.DirectionsWalk, stringResource(Res.string.play_walk_there), onWalk, Modifier.padding(start = 4.dp))
    }
}
