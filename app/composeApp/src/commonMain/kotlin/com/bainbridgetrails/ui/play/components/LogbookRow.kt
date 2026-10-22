package com.bainbridgetrails.ui.play.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_celebrate_marks
import com.bainbridgetrails.resources.play_kind_bramble
import com.bainbridgetrails.resources.play_kind_challenge
import com.bainbridgetrails.resources.play_kind_complete
import com.bainbridgetrails.resources.play_kind_coverage
import com.bainbridgetrails.resources.play_kind_day
import com.bainbridgetrails.resources.play_kind_egg
import com.bainbridgetrails.resources.play_kind_horn
import com.bainbridgetrails.resources.play_kind_ink
import com.bainbridgetrails.resources.play_kind_island
import com.bainbridgetrails.resources.play_kind_island_challenge
import com.bainbridgetrails.resources.play_kind_lore
import com.bainbridgetrails.resources.play_kind_milestone
import com.bainbridgetrails.resources.play_kind_park_complete
import com.bainbridgetrails.resources.play_kind_park_first
import com.bainbridgetrails.resources.play_kind_restored
import com.bainbridgetrails.resources.play_kind_retreat
import com.bainbridgetrails.resources.play_kind_riddle
import com.bainbridgetrails.resources.play_kind_square
import com.bainbridgetrails.resources.play_kind_start_balance
import com.bainbridgetrails.resources.play_kind_treasure
import com.bainbridgetrails.resources.play_kind_visit
import com.bainbridgetrails.resources.play_kind_wave
import com.bainbridgetrails.resources.play_kind_week
import com.bainbridgetrails.resources.play_log_cap
import com.bainbridgetrails.resources.play_log_line
import com.bainbridgetrails.resources.play_log_local_only
import com.bainbridgetrails.resources.play_log_restored
import com.bainbridgetrails.resources.play_log_stamp
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.play.screens.LogNote
import com.bainbridgetrails.ui.play.screens.LogRowModel
import com.bainbridgetrails.ui.play.screens.LogTrailing
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** `play_kind_*` for a Logbook kind (goal words through `LocalGoal`). */
@Composable
fun LogKind.labelRes(): StringResource = when (this) {
    LogKind.coverage -> Res.string.play_kind_coverage
    LogKind.complete -> LocalGoal.current.s(Res.string.play_kind_complete)
    LogKind.park_first -> Res.string.play_kind_park_first
    LogKind.park_complete -> Res.string.play_kind_park_complete
    LogKind.milestone -> Res.string.play_kind_milestone
    LogKind.island -> Res.string.play_kind_island
    LogKind.square -> Res.string.play_kind_square
    LogKind.day -> Res.string.play_kind_day
    LogKind.week -> Res.string.play_kind_week
    LogKind.treasure -> Res.string.play_kind_treasure
    LogKind.egg -> Res.string.play_kind_egg
    LogKind.riddle, LogKind.stamp -> Res.string.play_kind_riddle
    LogKind.ink -> Res.string.play_kind_ink
    LogKind.retreat -> Res.string.play_kind_retreat
    LogKind.bramble -> Res.string.play_kind_bramble
    LogKind.lore -> Res.string.play_kind_lore
    LogKind.horn -> Res.string.play_kind_horn
    LogKind.challenge -> Res.string.play_kind_challenge
    LogKind.island_challenge -> Res.string.play_kind_island_challenge
    LogKind.wave -> Res.string.play_kind_wave
    LogKind.start_balance -> Res.string.play_kind_start_balance
    LogKind.restored -> Res.string.play_kind_restored
    LogKind.visit -> Res.string.play_kind_visit
    // Play trails v1.1 placeholders (plan P1-10): L2-3 swaps in play_kind_climb / play_kind_high_ground / play_kind_start_trails.
    LogKind.climb, LogKind.high_ground -> Res.string.play_kind_milestone
    LogKind.start_trails -> Res.string.play_kind_start_balance
}

/** The art tile's glyph for a kind (placeholder art, ux § 7.2 #4). */
fun LogKind.glyph(): FxGlyph = when (this) {
    LogKind.treasure -> FxGlyph.SeaGlass
    LogKind.egg, LogKind.stamp -> FxGlyph.EggStamp
    LogKind.riddle -> FxGlyph.LanternLit
    LogKind.ink, LogKind.retreat -> FxGlyph.KrakenEye
    LogKind.lore -> FxGlyph.Frog
    LogKind.horn -> FxGlyph.Ferry
    LogKind.challenge, LogKind.island_challenge, LogKind.wave -> FxGlyph.Pennant
    LogKind.visit -> FxGlyph.QuietRing
    else -> FxGlyph.Marks
}

/**
 * LogbookRow (ux.md § 2.6): ≥ 56dp; art tile 32dp (kind glyph in ink on a `surfaceContainerHigh` circle); what
 * `titleSmall`, why `bodySmall` `onSurfaceVariant`; trailing `+n` in `marksDelta` `play.seaGlass` (muted:
 * `onSurfaceVariant`), a stamp, or nothing (remembrance). `local_only` adds a third line with cloud-off. One merged
 * node: "Treasure. Sea glass on Rose Loop. Weekly treasure, common. Plus 25 marks." No time of day, ever.
 */
@Composable
fun LogbookRow(row: LogRowModel, modifier: Modifier = Modifier) {
    val kind = stringResource(row.kind.labelRes())
    val why = when {
        row.note == LogNote.Cap -> stringResource(Res.string.play_log_cap)
        row.kind == LogKind.restored -> stringResource(Res.string.play_log_restored)
        else -> row.why
    }
    val localOnly = if (row.note == LogNote.LocalOnly) stringResource(Res.string.play_log_local_only) else null
    val stamp = stringResource(Res.string.play_log_stamp)
    val trailing = row.trailing
    val number = (trailing as? LogTrailing.Marks)?.let { PlayFormat.marks(it.marks) }
    val spokenMarks = number?.let { stringResource(Res.string.play_celebrate_marks, it) }
    val spoken = listOfNotNull(kind, row.what, why, spokenMarks ?: stamp.takeIf { trailing == LogTrailing.Stamp }, localOnly)
        .joinToString(". ")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        Box(
            Modifier.size(BtTheme.spacing.playArtTileLog).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { FxGlyphImage(row.kind.glyph(), size = 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(row.what, style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (localOnly != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BtIcon(BtIcons.CloudOff, null, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(localOnly, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when (trailing) {
            is LogTrailing.Marks -> Text(
                stringResource(Res.string.play_log_line, PlayFormat.marks(trailing.marks)),
                style = BtTheme.type.marksDelta,
                color = if (trailing.muted) MaterialTheme.colorScheme.onSurfaceVariant else BtTheme.colors.play.seaGlass,
                maxLines = 1,
            )
            LogTrailing.Stamp -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FxGlyphImage(FxGlyph.EggStamp, size = 18.dp)
                Text(stamp, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LogTrailing.None -> Unit
        }
    }
}
