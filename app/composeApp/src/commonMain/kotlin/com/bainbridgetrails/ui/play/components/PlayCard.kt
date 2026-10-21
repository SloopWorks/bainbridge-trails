package com.bainbridgetrails.ui.play.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_log_next_rank
import com.bainbridgetrails.resources.play_me_card_title
import com.bainbridgetrails.resources.play_me_logbook
import com.bainbridgetrails.resources.play_me_marks
import com.bainbridgetrails.resources.play_me_stories
import com.bainbridgetrails.resources.play_me_whats_on
import com.bainbridgetrails.resources.play_stories_count
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.play.screens.LogbookTotals
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Me › PlayCard (ux.md § 3.12): `surfaceContainerLow`, medium shape, padding 16. `play_me_card_title` heading; the total
 * in `marksTotal` (Fraunces, a static total) + "marks", RankBadge right; Play bar + `play_log_next_rank`; 48dp rows
 * Logbook › What's on › Island stories (with `play_stories_count`), `outlineVariant` dividers. Play on only.
 */
@Composable
fun PlayCard(
    totals: LogbookTotals,
    storiesMet: Int,
    storiesTotal: Int,
    onLogbook: () -> Unit,
    onWhatsOn: () -> Unit,
    onStories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marks = PlayFormat.marks(totals.total)
    val marksLine = stringResource(Res.string.play_me_marks, marks)
    val next = totals.next
    val nextLine = if (next != null && totals.toNext != null) stringResource(Res.string.play_log_next_rank, PlayFormat.marks(totals.toNext), stringResource(next.titleRes)) else null
    val rankTitle = stringResource(totals.rank.titleRes)
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(top = BtTheme.spacing.lg),
    ) {
        Column(Modifier.padding(horizontal = BtTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            Text(stringResource(Res.string.play_me_card_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = listOfNotNull(marksLine, rankTitle, nextLine).joinToString(". ") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(marks, style = BtTheme.type.marksTotal)
                Text(" " + marksLine.removePrefix(marks).trim(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                RankBadge(totals.rank)
            }
            if (nextLine != null) PlayBarWithLabel(totals.fraction, nextLine)
        }
        Column(Modifier.padding(top = BtTheme.spacing.sm)) {
            CardLink(stringResource(Res.string.play_me_logbook), null, onLogbook)
            HorizontalDivider(Modifier.padding(horizontal = BtTheme.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
            CardLink(stringResource(Res.string.play_me_whats_on), null, onWhatsOn)
            HorizontalDivider(Modifier.padding(horizontal = BtTheme.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
            CardLink(stringResource(Res.string.play_me_stories), stringResource(Res.string.play_stories_count, storiesMet, storiesTotal), onStories)
        }
    }
}

/** Font scale from which a Play bar and its label stack (ux § 2.1's compact step; the bar never collapses). */
private const val STACK_FONT_SCALE = 1.3f

/**
 * A PlayBar with its `numeric` label (ux § 2.7 "PlayBar to next rank"): one row ([leading] e.g. a RankBadge, the bar,
 * the label sharing the width); at font scale ≥ 1.3 stacked (leading, full-width bar, label) so neither collapses.
 */
@Composable
fun PlayBarWithLabel(fraction: Float, label: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val labelText = @Composable { m: Modifier -> Text(label, m, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            leading?.invoke()
            PlayBar(fraction, label, Modifier.fillMaxWidth())
            labelText(Modifier)
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
            leading?.invoke()
            PlayBar(fraction, label, Modifier.weight(1f))
            labelText(Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun CardLink(title: String, trailing: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(horizontal = BtTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BtIcon(BtIcons.ChevronRight, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
