package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.data.LocalGoal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.*
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.GateKind
import com.bainbridgetrails.ui.components.GateNote
import com.bainbridgetrails.ui.components.MapSheet
import com.bainbridgetrails.ui.components.MapSheetDefaults
import com.bainbridgetrails.ui.components.MetaChip
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.stringResource

/** [EncounterCopy] from the string resources. */
@Composable
fun rememberEncounterCopy(): EncounterCopy = EncounterCopy(
    reward = stringResource(Res.string.play_reward), rewardNone = stringResource(Res.string.play_reward_none),
    logLine = stringResource(Res.string.play_log_line),
    tiers = mapOf(
        TierName.sea_glass to stringResource(Res.string.play_tier_sea_glass), TierName.agate to stringResource(Res.string.play_tier_agate),
        TierName.glass_float to stringResource(Res.string.play_tier_glass_float), TierName.ships_bell to stringResource(Res.string.play_tier_ships_bell),
    ),
    expires = stringResource(Res.string.play_treasure_expires), collected = stringResource(Res.string.play_treasure_collected),
    foundOn = stringResource(Res.string.play_found_on), distanceAway = stringResource(Res.string.play_distance_away),
    gateDark = stringResource(Res.string.play_gate_dark), gateTide = stringResource(Res.string.play_gate_tide),
    gateTideUnknown = stringResource(Res.string.play_gate_tide_unknown), tagHistory = stringResource(Res.string.play_tag_history),
    tagTallTale = stringResource(Res.string.play_tag_tall_tale), tagRemembrance = stringResource(Res.string.play_tag_remembrance),
    safetyShore = stringResource(Res.string.play_safety_shore), krakenTitle = stringResource(Res.string.play_kraken_title),
    krakenBody = stringResource(LocalGoal.current.s(Res.string.play_kraken_body)), krakenRule = stringResource(Res.string.play_kraken_rule),
    krakenUntil = stringResource(Res.string.play_kraken_until), krakenReward = stringResource(Res.string.play_kraken_reward),
    stretchProgress = stringResource(Res.string.play_kraken_stretch_progress), repelledYou = stringResource(Res.string.play_kraken_repelled_you),
    findTitleEgg = stringResource(Res.string.play_find_title_egg),
)

/**
 * EncounterSheet (ux.md §2.3): the one non-modal Play sheet over the map, on [MapSheet] capped at 60 %. Art tile, title
 * (heading) + stamp + meta, reward and fact chips (one merged node), body, kraken stretches, the riddle block
 * ([riddle]), GateNote / safety line, then `play_walk_there` — absent (never disabled) when gated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EncounterSheet(
    model: EncounterModel,
    onDismiss: () -> Unit,
    onWalkThere: (WalkTarget) -> Unit,
    onStretchFocus: (StretchRow) -> Unit,
    modifier: Modifier = Modifier,
    minShare: Double = 0.6,
    riddle: (@Composable ColumnScope.() -> Unit)? = null,
) {
    MapSheet(onDismiss, modifier, maxHeightFraction = MapSheetDefaults.EncounterMaxHeightFraction) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtTile(model.glyph, dim = model.dimArt, wash = model.washArt)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        model.title,
                        Modifier.semantics { heading(); contentDescription = listOfNotNull(model.title, model.stamp).joinToString(". ") },
                        style = MaterialTheme.typography.titleLarge, maxLines = 2,
                    )
                    model.stamp?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    model.meta?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            if (model.reward != null || model.chips.isNotEmpty()) {
                val spoken = (listOfNotNull(model.reward) + model.chips.map { it.text }).joinToString(". ")
                FlowRow(Modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.reward?.let { RewardPill(it, model.rewardClaimed) }
                    for (c in model.chips) MetaChip(c.text, outlined = c.outlined, icon = if (c.check) BtIcons.Check else null, iconTint = BtTheme.colors.play.seaGlass)
                }
            }
            model.body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 8) }
            model.rule?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (model.stretches.isNotEmpty()) {
                Column {
                    for (row in model.stretches) {
                        KrakenStretchRow(row, minShare, onFocus = { onStretchFocus(row) }, onWalk = { row.featureId?.let { onWalkThere(WalkTarget.Feature(it)) } })
                    }
                }
            }
            riddle?.invoke(this)
            model.gate?.let { GateNote(it.kind, it.text) }
            model.safety?.let { GateNote(GateKind.Safety, it) }
            model.footnote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            model.walkThere?.let { target ->
                PrimaryButton(stringResource(Res.string.play_walk_there), { onWalkThere(target) }, Modifier.fillMaxWidth(), icon = BtIcons.DirectionsWalk)
            }
        }
    }
}

/** The 56dp paper art tile (ux.md §2.3): ink figure, 1dp ink border; [wash] = found (sea-glass wash), [dim] = asleep. */
@Composable
internal fun ArtTile(glyph: FxGlyph, dim: Boolean = false, wash: Boolean = false, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val play = BtTheme.colors.play
    Box(
        Modifier.size(size)
            .background(if (wash) play.seaGlassWash else MaterialTheme.colorScheme.surfaceBright, MaterialTheme.shapes.medium)
            .border(1.dp, play.ink, MaterialTheme.shapes.medium)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        FxGlyphImage(glyph, Modifier.alpha(if (dim) 0.6f else 1f), size = size * 0.6f)
    }
}

/** RewardChip look (ux.md §2.12) for already formatted text ("25 marks", "+25", "60–80 marks a stretch"). */
@Composable
private fun RewardPill(text: String, claimed: Boolean) {
    Row(
        Modifier.heightIn(min = 32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FxGlyphImage(FxGlyph.Marks, size = 16.dp, ink = BtTheme.colors.play.seaGlass)
        Text(text, style = if (claimed) BtTheme.type.marksDelta else MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
