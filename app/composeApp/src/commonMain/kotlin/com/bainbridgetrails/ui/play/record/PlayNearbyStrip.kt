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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.SensedFind
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.schedule.GeoPoint
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_find_provisional
import com.bainbridgetrails.resources.play_nearby_found
import com.bainbridgetrails.resources.play_nearby_header
import com.bainbridgetrails.resources.play_nearby_row
import com.bainbridgetrails.resources.play_record_finds
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.StateChip
import com.bainbridgetrails.ui.play.map.EncounterLogic
import com.bainbridgetrails.ui.play.map.MarkerKind
import com.bainbridgetrails.ui.play.map.PlayMarker
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One `NearbyRow` (ux.md §2.9). */
data class NearbyRow(val key: String, val glyph: FxGlyph, val name: String, val distanceM: Double, val found: Boolean)

/** The record screen's Play logic (ux.md §3.8, §2.9; product PF5): pure. */
object PlayRecordLogic {
    const val NEARBY_M = 400.0
    const val NEARBY_MAX = 3

    /** ≤ 3 treasure / lore / found-egg markers within 400 m of [here], nearest first; [found] = sensed or claimed. */
    fun nearby(markers: List<PlayMarker>, here: GeoPoint, sensed: Set<String>, name: (PlayMarker) -> String): List<NearbyRow> =
        markers.filter { it.kind == MarkerKind.TREASURE || it.kind == MarkerKind.LORE || it.kind == MarkerKind.EGG }
            .map { m -> m to GeoMath.distanceM(here, GeoPoint(m.lat, m.lon)) }
            .filter { it.second <= NEARBY_M }
            .sortedBy { it.second }
            .take(NEARBY_MAX)
            .map { (m, d) -> NearbyRow(m.key, m.glyph, name(m), d, m.found || m.key in sensed) }

    /** The recording notification body + `play_notif_finds` ("· 2 finds") once the walk has finds (PF5 step 3). */
    fun notificationText(base: String, finds: Int, suffix: (Int) -> String): String = if (finds <= 0) base else "$base ${suffix(finds)}"

    /** The FindMoment for a live find; [title] formats per type (`play_find_title_*`, `play_kraken_repelled_you`). */
    fun moment(find: SensedFind, item: Item?, tierName: (com.bainbridgetrails.play.config.TierName) -> String, titles: FindTitles): FindMomentModel {
        val o = find.occurrence
        val p = find.placement
        val marks = p?.marks?.takeIf { it > 0 } ?: o.marks
        return when (o.type) {
            ItemType.treasure -> {
                val tier = p?.tier ?: com.bainbridgetrails.play.config.TierName.sea_glass
                FindMomentModel(find.key, EncounterLogic.tierGlyph(tier), titles.treasure.replace("%1\$s", tierName(tier)), tierName(tier), marks)
            }
            ItemType.egg -> FindMomentModel(find.key, FxGlyph.EggStamp, titles.egg, titles.egg, marks)
            ItemType.obstacle -> FindMomentModel(find.key, FxGlyph.KrakenEye, titles.kraken, titles.kraken, marks)
            else -> {
                val t = item?.copy?.title ?: o.itemId
                FindMomentModel(find.key, FxGlyph.Pennant, t, t, marks)
            }
        }
    }

    data class FindTitles(val treasure: String, val egg: String, val kraken: String)
}

/** FindsChip (ux.md §2.9): StateChip with the △ glyph, "2 finds"; only with ≥ 1 find. */
@Composable
fun FindsChip(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    StateChip(pluralStringResource(Res.plurals.play_record_finds, count, count), modifier, leading = {
        FxGlyphImage(FxGlyph.Marks, size = 16.dp, ink = BtTheme.colors.play.seaGlass)
    })
}

/** The Nearby section under the live list (ux.md §3.8): header, the provisional caption once a find exists, ≤ 3 rows. Empty = hidden. */
@Composable
fun PlayNearbyStrip(rows: List<NearbyRow>, distance: (Double) -> String, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(Res.string.play_nearby_header))
        if (rows.any { it.found }) Text(stringResource(Res.string.play_find_provisional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (r in rows) {
            val text = stringResource(Res.string.play_nearby_row, r.name, distance(r.distanceM))
            val found = stringResource(Res.string.play_nearby_found)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = if (r.found) "$text. $found" else text },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FxGlyphImage(r.glyph, size = 24.dp)
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (r.found) {
                    BtIcon(BtIcons.Check, null, size = 16.dp, tint = BtTheme.colors.play.seaGlass)
                    Text(found, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The running repository's items by id (titles for finds and results). */
fun PlayRepository.itemsById(): Map<String, Item> = config.value?.config?.items?.associateBy { it.id }.orEmpty()
