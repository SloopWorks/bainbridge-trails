package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.ResolveBy
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.time.SunDay
import com.bainbridgetrails.ui.components.GateKind
import com.bainbridgetrails.ui.play.components.PlayFormat
import kotlinx.datetime.LocalDate
import kotlin.math.floor
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The `play_*` strings the EncounterSheet logic formats (English values in tests, `stringResource` on screen). */
data class EncounterCopy(
    val reward: String,
    val rewardNone: String,
    val logLine: String,
    val tiers: Map<TierName, String>,
    val expires: String,
    val collected: String,
    val foundOn: String,
    val distanceAway: String,
    val gateDark: String,
    val gateTide: String,
    val gateTideUnknown: String,
    val tagHistory: String,
    val tagTallTale: String,
    val tagRemembrance: String,
    val safetyShore: String,
    val krakenTitle: String,
    val krakenBody: String,
    val krakenRule: String,
    val krakenUntil: String,
    val krakenReward: String,
    val stretchProgress: String,
    val repelledYou: String,
    val findTitleEgg: String,
) {
    companion object {
        /** From a `play_*` key lookup (tests read values/strings.xml). */
        fun english(s: (String) -> String) = EncounterCopy(
            reward = s("play_reward"), rewardNone = s("play_reward_none"), logLine = s("play_log_line"),
            tiers = TierName.entries.associateWith { s("play_tier_${it.name}") },
            expires = s("play_treasure_expires"), collected = s("play_treasure_collected"), foundOn = s("play_found_on"),
            distanceAway = s("play_distance_away"), gateDark = s("play_gate_dark"), gateTide = s("play_gate_tide"),
            gateTideUnknown = s("play_gate_tide_unknown"), tagHistory = s("play_tag_history"), tagTallTale = s("play_tag_tall_tale"),
            tagRemembrance = s("play_tag_remembrance"), safetyShore = s("play_safety_shore"), krakenTitle = s("play_kraken_title"),
            krakenBody = s("play_kraken_body"), krakenRule = s("play_kraken_rule"), krakenUntil = s("play_kraken_until"),
            krakenReward = s("play_kraken_reward"), stretchProgress = s("play_kraken_stretch_progress"),
            repelledYou = s("play_kraken_repelled_you"), findTitleEgg = s("play_find_title_egg"),
        )
    }
}

enum class EncounterVariant { TREASURE_LIVE, TREASURE_FOUND, TREASURE_ASLEEP, EGG, LORE, REMEMBRANCE, KRAKEN, RIDDLE }

/** A neutral fact pill ([MetaChip]); [check]: the sea-glass check of "Found". */
data class EncounterChip(val text: String, val outlined: Boolean = false, val check: Boolean = false)

/** Why the CTA is absent (GateNote, ux.md §2.12). */
data class GateLine(val kind: GateKind, val text: String)

/** Where `play_walk_there` goes: the feature's detail, or the nearest Play-safe line to a point (MapTab resolves it). */
sealed interface WalkTarget {
    data class Feature(val id: String) : WalkTarget

    data class Point(val lat: Double, val lon: Double) : WalkTarget
}

/**
 * One `KrakenStretchRow` (ux.md §2.3): [share] = the best share walked in one walk (null = not walked), [text] the
 * trailing state ("—", `play_kraken_stretch_progress`, `play_kraken_repelled_you`); [walkable] = the row's 48dp walk
 * button exists (absent while the stretch is gated, PF4-2, or once repelled).
 */
data class StretchRow(
    val key: String,
    val featureId: String?,
    val name: String,
    val share: Double?,
    val text: String,
    val repelled: Boolean,
    val walkable: Boolean,
)

/**
 * Everything the EncounterSheet shows for one marker (ux.md §2.3). [reward] null = no RewardChip (remembrance, PF4-4);
 * [walkThere] null = no CTA (absent, never disabled; [gate] says why when it is a gate); [motion] false = no stamp ink,
 * shake or glint (remembrance). [footnote] is the remembrance `play_reward_none` line.
 */
data class EncounterModel(
    val key: String,
    val itemId: String,
    val variant: EncounterVariant,
    val glyph: FxGlyph,
    val title: String,
    val meta: String? = null,
    val stamp: String? = null,
    val reward: String? = null,
    val rewardClaimed: Boolean = false,
    val chips: List<EncounterChip> = emptyList(),
    val body: String? = null,
    val rule: String? = null,
    val gate: GateLine? = null,
    val safety: String? = null,
    val walkThere: WalkTarget? = null,
    val motion: Boolean = true,
    val dimArt: Boolean = false,
    val washArt: Boolean = false,
    val sources: List<String> = emptyList(),
    val stretches: List<StretchRow> = emptyList(),
    val footnote: String? = null,
)

/**
 * EncounterSheet content per variant (ux.md §2.3; product § 4, PF4-1 … PF4-4, PF7-2): pure, so the gate and CTA
 * rules are host-tested. [names] resolves a trail / road id to its display name; [shares] the kraken stretch shares by
 * placement key (this process's claim passes); [distance] the formatted distance (omitted when unknown, no spinner);
 * [foundOn] the island date of the claim (the "Found 27 Oct" stamp); [coast] the kraken coast's name.
 */
object EncounterLogic {
    fun model(
        item: Item,
        active: ActiveItem,
        key: String,
        now: Instant,
        sun: SunDay,
        copy: EncounterCopy,
        names: (String) -> String?,
        shares: Map<String, Double> = emptyMap(),
        distance: String? = null,
        foundOn: LocalDate? = null,
        coast: String? = null,
    ): EncounterModel {
        val o = active.occurrence
        val placement = o.placements.firstOrNull { it.key == key } ?: o.placements.firstOrNull()
        val found = key in active.found || o.key in active.found
        val place = placement?.featureId?.let(names)
        val meta = listOfNotNull(place, distance?.let { PlayFormat.fill(copy.distanceAway, it) }).joinToString(" · ").ifEmpty { null }
        val stamp = foundOn?.let { PlayFormat.fill(copy.foundOn, PlayFormat.monthDay(it)) }
        return when {
            o.type == ItemType.obstacle -> kraken(item, active, now, sun, copy, names, shares, coast)
            o.type == ItemType.lore && item.lore == "remembrance" -> EncounterModel(
                key, item.id, EncounterVariant.REMEMBRANCE, FxGlyph.QuietRing, item.copy.title,
                chips = listOf(EncounterChip(copy.tagRemembrance, outlined = true)),
                body = item.copy.body, motion = false, sources = item.sources, footnote = copy.rewardNone,
            )
            o.type == ItemType.treasure -> {
                val tier = placement?.tier ?: TierName.sea_glass
                val marks = placement?.marks ?: o.marks
                val base = EncounterModel(key, item.id, EncounterVariant.TREASURE_LIVE, tierGlyph(tier), copy.tiers[tier] ?: tier.name, meta = meta)
                when {
                    found -> base.copy(
                        variant = EncounterVariant.TREASURE_FOUND, stamp = stamp, reward = PlayFormat.fill(copy.logLine, PlayFormat.marks(marks)),
                        rewardClaimed = true, chips = listOf(EncounterChip(copy.collected, check = true)), body = item.copy.found, washArt = true, motion = false,
                    )
                    active.gate == Gate.OPEN -> base.copy(
                        reward = PlayFormat.fill(copy.reward, PlayFormat.marks(marks)), chips = listOf(EncounterChip(copy.expires)),
                        body = item.copy.body, walkThere = walkTarget(placement?.featureId, placement?.point),
                    )
                    else -> base.copy(
                        variant = EncounterVariant.TREASURE_ASLEEP, reward = PlayFormat.fill(copy.reward, PlayFormat.marks(marks)),
                        chips = listOf(EncounterChip(copy.expires)), body = item.copy.body, dimArt = true, motion = false,
                        gate = gateLine(active, now, sun, copy),
                    )
                }
            }
            o.type == ItemType.egg -> EncounterModel(
                key, item.id, EncounterVariant.EGG, FxGlyph.EggStamp, copy.findTitleEgg, meta = meta, stamp = stamp,
                reward = if (found && o.marks > 0) PlayFormat.fill(copy.logLine, PlayFormat.marks(o.marks)) else null, rewardClaimed = found,
                body = item.copy.found ?: item.copy.body,
            )
            o.type == ItemType.riddle -> EncounterModel(
                key, item.id, EncounterVariant.RIDDLE, FxGlyph.LanternUnlit, item.copy.title, meta = meta,
                reward = PlayFormat.fill(copy.reward, PlayFormat.marks(item.marks.takeIf { it > 0 } ?: o.marks)), body = item.copy.body,
                sources = item.sources,
            )
            else -> {
                val open = active.gate == Gate.OPEN
                val proximity = item.resolve.by == ResolveBy.proximity && placement != null
                val marks = item.marks.takeIf { it > 0 } ?: o.marks
                EncounterModel(
                    key, item.id, EncounterVariant.LORE, PlayMarker(key, o.key, item.id, MarkerKind.LORE, 0.0, 0.0, null, found, active.gate, lore = item.lore).glyph,
                    item.copy.title, meta = meta, stamp = if (found) stamp else null,
                    reward = when {
                        marks <= 0 -> null
                        found -> PlayFormat.fill(copy.logLine, PlayFormat.marks(marks))
                        else -> PlayFormat.fill(copy.reward, PlayFormat.marks(marks))
                    },
                    rewardClaimed = found,
                    chips = listOf(EncounterChip(if (item.lore == "kraken") copy.tagTallTale else copy.tagHistory)),
                    body = item.copy.body,
                    safety = if (item.shore) copy.safetyShore else null,
                    walkThere = if (open && proximity && !found) walkTarget(placement?.featureId, placement?.point) else null,
                    gate = if (proximity && !found && active.gate == Gate.SLEEPING) gateLine(active, now, sun, copy) else null,
                    sources = item.sources,
                )
            }
        }
    }

    /** The kraken attack sheet (ux.md §2.3 `KrakenAttackSheet`; PF3-3, PF4-2, PF7-2, PF16-3). */
    private fun kraken(
        item: Item,
        active: ActiveItem,
        now: Instant,
        sun: SunDay,
        copy: EncounterCopy,
        names: (String) -> String?,
        shares: Map<String, Double>,
        coast: String?,
    ): EncounterModel {
        val o = active.occurrence
        val minShare = item.resolve.minShare ?: 0.6
        val open = active.gate == Gate.OPEN
        val rows = o.placements.map { p -> stretchRow(p.key, p.featureId, p.featureId?.let(names) ?: p.ref, shares[p.key], minShare, p.key in active.found, open, copy) }
        val bands = item.bands.map { it.marks }
        return EncounterModel(
            o.key, item.id, EncounterVariant.KRAKEN, FxGlyph.KrakenEye, copy.krakenTitle, meta = copy.krakenUntil,
            reward = if (bands.isEmpty()) null else PlayFormat.fill(copy.krakenReward, bands.min(), bands.max()),
            chips = listOf(EncounterChip(copy.tagTallTale)),
            body = PlayFormat.fill(copy.krakenBody, coast ?: rows.joinToString(", ") { it.name }),
            rule = PlayFormat.fill(copy.krakenRule, pct(minShare)),
            gate = if (open) null else gateLine(active, now, sun, copy),
            stretches = rows,
        )
    }

    /** One stretch row: repelled by you, else the best share walked against the rule, else "—" (PF7-2). */
    fun stretchRow(
        key: String,
        featureId: String?,
        name: String,
        share: Double?,
        minShare: Double,
        repelled: Boolean,
        open: Boolean,
        copy: EncounterCopy,
    ): StretchRow {
        val text = when {
            repelled -> copy.repelledYou
            share != null && share > 0.0 -> PlayFormat.fill(copy.stretchProgress, pct(share), pct(minShare))
            else -> "—"
        }
        return StretchRow(key, featureId, name, share, text, repelled, walkable = open && !repelled && featureId != null)
    }

    /** The GateNote for a gated item: dark (next daylight), tide high (when it drops), tide unknown (fail closed). */
    fun gateLine(active: ActiveItem, now: Instant, sun: SunDay, copy: EncounterCopy): GateLine? = when (active.gate) {
        Gate.OPEN, Gate.VIEW -> null
        Gate.TIDE_UNKNOWN -> GateLine(GateKind.Tide, copy.gateTideUnknown)
        Gate.TIDE_HIGH -> GateLine(GateKind.Tide, PlayFormat.fill(copy.gateTide, nextOpen(active.occurrence, now, sun)?.let(PlayFormat::time) ?: ""))
        Gate.SLEEPING -> GateLine(GateKind.Dark, PlayFormat.fill(copy.gateDark, nextOpen(active.occurrence, now, sun)?.let(PlayFormat::time) ?: ""))
    }

    /** The next instant the occurrence can be sensed: its next resolvable span today, else tomorrow's civil dawn (≈). */
    fun nextOpen(o: Occurrence, now: Instant, sun: SunDay): Instant? =
        o.resolvable.firstOrNull { it.start > now }?.start
            ?: o.daySpans.firstOrNull { it.start > now && o.tideGated.not() }?.start
            ?: sun.dawn?.let { if (it > now) it else it + 1.days }

    /** Whole percent, rounded down so "60 %" never shows before the rule is met. */
    fun pct(share: Double): Int = floor(share * 100 + 1e-9).toInt()

    fun tierGlyph(tier: TierName): FxGlyph = when (tier) {
        TierName.sea_glass -> FxGlyph.SeaGlass
        TierName.agate -> FxGlyph.Agate
        TierName.glass_float -> FxGlyph.GlassFloat
        TierName.ships_bell -> FxGlyph.ShipsBell
    }

    private fun walkTarget(featureId: String?, point: GeoPoint?): WalkTarget? =
        featureId?.let { WalkTarget.Feature(it) } ?: point?.let { WalkTarget.Point(it.lat, it.lon) }
}
