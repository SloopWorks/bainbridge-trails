package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.board_col_island
import com.bainbridgetrails.resources.board_col_name
import com.bainbridgetrails.resources.board_col_rank
import com.bainbridgetrails.resources.board_col_trails
import com.bainbridgetrails.resources.board_more_a11y
import com.bainbridgetrails.resources.board_report_name
import com.bainbridgetrails.resources.board_row_a11y
import com.bainbridgetrails.resources.board_row_you_a11y
import com.bainbridgetrails.resources.board_you_tag
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val RankW = 40.dp
private val PctW = 64.dp
private val TrailsW = 48.dp
private val MenuW = 48.dp

/** Server `islandPct` (1-decimal, already rounded down, TL-5) as shown: "41.0%". */
fun boardPct(islandPct: Double): String {
    val tenths = kotlin.math.floor(islandPct * 10 + 1e-6).toLong().coerceIn(0, 1000)
    return "${tenths / 10}.${tenths % 10}%"
}

/** Sticky column header for [LeaderRow]: "#  Name  Island  Trails" (labelMedium). */
@Composable
fun LeaderHeader(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = BtTheme.spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        Text(stringResource(Res.string.board_col_rank), Modifier.width(RankW), style = style, color = c, textAlign = TextAlign.End)
        Text(stringResource(Res.string.board_col_name), Modifier.weight(1f).padding(start = 4.dp), style = style, color = c)
        Text(stringResource(Res.string.board_col_island), Modifier.width(PctW), style = style, color = c, textAlign = TextAlign.End)
        Text(stringResource(Res.string.board_col_trails), Modifier.width(TrailsW), style = style, color = c, textAlign = TextAlign.End)
        Box(Modifier.width(MenuW))
    }
}

/**
 * Leaderboard row (ux.md §3.8): rank (Fraunces, `primary` for the top 3 — no medals, no status hues), name, island %,
 * trails completed. [isYou] = `primaryContainer` with a 2dp `primary` bar and a "You" tag; [onReport] adds the ⋯ menu
 * (others' rows only).
 */
@Composable
fun LeaderRow(
    rank: Int,
    name: String,
    islandPct: Double,
    trailsCompleted: Int,
    isYou: Boolean,
    modifier: Modifier = Modifier,
    onReport: (() -> Unit)? = null,
) {
    val pct = boardPct(islandPct)
    val a11y = if (isYou) pluralStringResource(Res.plurals.board_row_you_a11y, trailsCompleted, rank, pct, trailsCompleted)
    else pluralStringResource(Res.plurals.board_row_a11y, trailsCompleted, rank, name, pct, trailsCompleted)
    val bg = if (isYou) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (isYou) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val sub = if (isYou) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().background(bg).then(
            if (isYou) Modifier.drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) } else Modifier,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = BtTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
        ) {
            Row(Modifier.weight(1f).clearAndSetSemantics { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                Text(
                    "$rank",
                    Modifier.width(RankW),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "lnum, tnum"),
                    color = if (rank <= 3 && !isYou) MaterialTheme.colorScheme.primary else sub,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isYou) {
                        Text(
                            stringResource(Res.string.board_you_tag),
                            Modifier.background(MaterialTheme.colorScheme.primary, PillShape).padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Text(pct, Modifier.width(PctW), style = BtTheme.type.numeric, color = fg, textAlign = TextAlign.End, maxLines = 1)
                Text("$trailsCompleted", Modifier.width(TrailsW), style = BtTheme.type.numeric, color = sub, textAlign = TextAlign.End, maxLines = 1)
            }
            Box(Modifier.width(MenuW), contentAlignment = Alignment.Center) {
                if (onReport != null) {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) {
                        BtIcon(BtIcons.MoreHoriz, stringResource(Res.string.board_more_a11y, name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.board_report_name)) }, onClick = { open = false; onReport() })
                    }
                }
            }
        }
    }
}

/** Placeholder row while the board loads. */
@Composable
fun SkeletonLeaderRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = BtTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
    ) {
        SkeletonBlock(24.dp, 20.dp)
        SkeletonBlock(140.dp, 14.dp, Modifier.weight(1f, fill = false))
        Box(Modifier.weight(1f))
        SkeletonBlock(44.dp, 14.dp)
    }
}

/** Section label used above lists (labelLarge, onSurfaceVariant, heading semantics). */
@Composable
fun ListLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
