package com.bainbridgetrails.ui.play.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.claim.UnclaimedReason
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_results_header
import com.bainbridgetrails.resources.play_results_none
import com.bainbridgetrails.resources.play_results_total
import com.bainbridgetrails.resources.play_unclaimed_accuracy
import com.bainbridgetrails.resources.play_unclaimed_dark
import com.bainbridgetrails.resources.play_unclaimed_header
import com.bainbridgetrails.resources.play_unclaimed_tide
import com.bainbridgetrails.resources.play_unclaimed_vehicle
import com.bainbridgetrails.resources.play_unclaimed_window
import com.bainbridgetrails.ui.play.components.LogbookRow
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.RankUpLine
import org.jetbrains.compose.resources.stringResource

/**
 * The results sheet's Play section (ux.md §3.9, PF6): header + total (polite, once), this walk's Logbook lines, the
 * RankUpLine (a line, never a modal), then "Not claimed" with the reason and no number.
 */
@Composable
fun ResultsPlaySectionView(section: ResultsPlaySection, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in section.items) when (item) {
            is ResultsPlayItem.Header -> {
                val total = stringResource(Res.string.play_results_total, PlayFormat.marks(item.total))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { heading(); contentDescription = total; liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.play_results_header), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(total, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is ResultsPlayItem.Row -> LogbookRow(item.row)
            ResultsPlayItem.None -> Text(stringResource(Res.string.play_results_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ResultsPlayItem.RankUp -> RankUpLine(item.rank)
            ResultsPlayItem.UnclaimedHeader -> Text(stringResource(Res.string.play_unclaimed_header), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            is ResultsPlayItem.Unclaimed -> {
                val reason = stringResource(item.row.reason.res())
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FxGlyphImage(FxGlyph.SeaGlass, size = 18.dp, ink = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${item.row.what} · $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun UnclaimedReason.res() = when (this) {
    UnclaimedReason.WINDOW -> Res.string.play_unclaimed_window
    UnclaimedReason.DARK -> Res.string.play_unclaimed_dark
    UnclaimedReason.TIDE -> Res.string.play_unclaimed_tide
    UnclaimedReason.VEHICLE -> Res.string.play_unclaimed_vehicle
    UnclaimedReason.ACCURACY -> Res.string.play_unclaimed_accuracy
}
