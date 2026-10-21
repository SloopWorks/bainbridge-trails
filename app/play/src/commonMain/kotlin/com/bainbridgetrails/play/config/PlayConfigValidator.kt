package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.geo.QuietZones
import com.bainbridgetrails.play.geo.check
import com.bainbridgetrails.play.geo.isAtLeastAsStrictAs
import com.bainbridgetrails.play.schedule.AllTrailheadsRef
import com.bainbridgetrails.play.schedule.FeatureInfo
import com.bainbridgetrails.play.schedule.FeatureRef
import com.bainbridgetrails.play.schedule.GeoPoint
import com.bainbridgetrails.play.schedule.ParkRef
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.PlaceRef
import com.bainbridgetrails.play.schedule.PoolRef
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.StretchRef
import com.bainbridgetrails.play.schedule.TrailRef
import com.bainbridgetrails.play.schedule.TrailheadRef
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.Season
import com.bainbridgetrails.play.time.Sun
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Validation rule ids (schedule.md §4.3). [id] appears in logs, the debug drawer and the build script's output. */
enum class Rule(val id: String) {
    PARSE("parse"),
    SCHEMA("schema"),
    TZ("tz"),
    ID_FORMAT("id-format"),
    DUPLICATE_ID("duplicate-id"),
    BAD_REF("bad-ref"),
    GOAL_MISMATCH("goal-mismatch"),
    UNKNOWN_FX("unknown-fx"),
    PLACEMENT_SHAPE("placement-shape"),
    MISSING_WHERE("missing-where"),
    IMMUTABLE("immutable-id"),
    REMOVED_WITHOUT_EXPIRES("removed-without-expires"),
    RECURRENCE("recurrence"),
    TIME_EXPR("time-expr"),
    EMPTY_WINDOW("empty-window"),
    SERVER_SUBSET("server-subset"),
    TIDE_ANCHORED_OBSTACLE("tide-anchored-obstacle"),
    REWARD("reward"),
    CAPS("caps"),
    COUNT("count"),
    A11Y("a11y"),
    COPY("copy"),
    DAYLIGHT("daylight"),
    CREATURE_HOURS("creature-hours"),
    AMBIENT_HOURS("ambient-hours"),
    ROAD_SAFETY("road-safety"),
    QUIET_ZONE_ANCHOR("quiet-zone-anchor"),
    QUIET_ZONE_TOUCH("quiet-zone-touch"),
    EXCLUDED_PARK("excluded-park"),
    HERO_CONFLICT("hero-conflict"),
}

/** One reason a pack is rejected. [path] is a JSON-ish locator (`items[kraken-roads].where.roads`). */
data class PlayConfigError(val rule: Rule, val path: String, val message: String) {
    override fun toString(): String = "[${rule.id}] $path: $message"
}

/**
 * The semantic checks of schedule.md §4.3. **Any error rejects the whole pack** (the app keeps its last good one).
 *
 * With [places] null (no dataset: CI, or a cold device) every dataset rule is skipped: trail/road/park/stretch/
 * trailhead refs, `roadSafety`, quiet zones and excluded parks. Place, pool, window and fx refs always resolve
 * against the pack itself. The coastline buffer is the build script's (it owns `coastline.json`).
 *
 * Calendar rules (daylight, creature hours, ambient hours, `from < to`, hero conflicts) simulate [simDays] island days
 * from [simStart] with the date-level recurrence ([Recurrences.startsOn]: `every`, `on`, `in`, `dates`, `fallback`,
 * `season`, `except`) and the authored `[from, to]` across `days`; anchors only narrow a window, so ignoring them here
 * is conservative.
 */
object PlayConfigValidator {
    /** effects.md §7 v1 effect ids (plan §2 inventory). */
    val V1_EFFECTS: Set<String> = setOf(
        "treasure_glint", "exploration_fog", "riddle_marker", "kraken_lurk", "ferry_crossing", "frog_rock",
        "kraken_attack", "kraken_recede", "treasure_found", "riddle_light", "claim_pop", "play_wake",
    )
    val BUILT_IN_POOLS: Map<String, Goal> = mapOf("play-safe-trails" to Goal.trails, "play-safe-roads" to Goal.roads)
    val SIM_START: LocalDate = LocalDate(2027, 1, 1)
    const val SIM_DAYS = 400

    // product §3.3 / §3.4, exactly
    val TIER_MARKS: Map<TierName, Int> =
        mapOf(TierName.sea_glass to 25, TierName.agate to 40, TierName.glass_float to 60, TierName.ships_bell to 150)
    val KRAKEN_BANDS: List<Band> = listOf(Band(60, 60), Band(80, 70), Band(95, 80))
    const val EGG_MARKS = 20
    const val LORE_MARKS = 15
    const val CREATURE_MARKS = 15
    const val BRAMBLE_MARKS = 40
    const val RIDDLE_MARKS_PER_STAR = 40
    val SOLO_CHALLENGE_MARKS: Set<Int> = setOf(50, 100, 150)
    const val ISLAND_CHALLENGE_MARKS = 75
    const val RETREAT_MARKS = 50
    const val MAX_LABEL = 140

    private val ID = Regex("^[a-z0-9-]{3,48}$")
    private val BANNED = Regex("\\b(race|run|fast|speed|points)\\b", RegexOption.IGNORE_CASE)
    private val SEASON_IDS: Set<String> = Season.entries.map { it.id }.toSet() +
        setOf("winter", "spring", "summer", "fall", "shoulder")

    fun validate(
        config: PlayConfig,
        places: PlaceIndex?,
        previous: PlayConfig? = null,
        knownFx: Set<String> = V1_EFFECTS,
        simStart: LocalDate = SIM_START,
        simDays: Int = SIM_DAYS,
    ): List<PlayConfigError> = Run(config, places, knownFx, simStart, simDays).apply { all(previous) }.errors

    private class Run(
        val c: PlayConfig,
        val index: PlaceIndex?,
        val knownFx: Set<String>,
        val simStart: LocalDate,
        val simDays: Int,
    ) {
        val errors = ArrayList<PlayConfigError>()
        val zones: QuietZones? = index?.let { QuietZones(QuietZones.build(c.quietZones, c.places, it).zones) }
        val simDates: List<LocalDate> = List(simDays) { simStart.plus(it, DateTimeUnit.DAY) }
        private val sunCache = HashMap<LocalDate, com.bainbridgetrails.play.time.SunDay>()
        fun sun(d: LocalDate) = sunCache.getOrPut(d) { Sun.sunEvents(d) }

        fun err(rule: Rule, path: String, message: String) {
            errors += PlayConfigError(rule, path, message)
        }

        fun all(previous: PlayConfig?) {
            top()
            ids()
            for ((id, w) in c.windows) recurrence("windows[$id]", w, definition = true)
            for ((i, d) in c.quietDays.withIndex()) if (d !in c.windows) err(Rule.BAD_REF, "quietDays[$i]", "unknown window '$d'")
            val itemIds = c.items.map { it.id }.toSet()
            for ((i, k) in c.kill.items.withIndex()) if (k !in itemIds) err(Rule.BAD_REF, "kill.items[$i]", "unknown item '$k'")
            places()
            pools()
            quietZones()
            pauses()
            for (item in c.items) item(item)
            heroes()
            if (previous != null) immutability(previous)
        }

        // --- top level ------------------------------------------------------------------------------------------

        fun top() {
            if (c.schema != PlayConfig.SUPPORTED_SCHEMA) err(Rule.SCHEMA, "schema", "schema ${c.schema} not supported")
            if (c.tz != PlayConfig.ISLAND_TZ) err(Rule.TZ, "tz", "must be ${PlayConfig.ISLAND_TZ} in schema 1, was '${c.tz}'")
            if (c.caps != Caps()) err(Rule.CAPS, "caps", "caps must equal product §3.5 ${Caps()}, was ${c.caps}")
            if (!c.rules.roadSafety.isAtLeastAsStrictAs(RoadSafety())) {
                err(Rule.ROAD_SAFETY, "rules.roadSafety", "may only be stricter than product §4.0.2 ${RoadSafety()}")
            }
            for ((name, span) in listOf("playHours" to c.rules.playHours, "ambientHours" to c.rules.ambientHours)) {
                val f = TimeExpr.parseOrNull(span.from)
                val t = TimeExpr.parseOrNull(span.to)
                if (f !is TimeExpr.Clock || t !is TimeExpr.Clock || f.minuteOfDay >= t.minuteOfDay) {
                    err(Rule.TIME_EXPR, "rules.$name", "must be an HH:MM span with from < to")
                }
            }
        }

        fun ids() {
            fun check(path: String, id: String) {
                if (!ID.matches(id)) err(Rule.ID_FORMAT, path, "id '$id' must match [a-z0-9-]{3,48}")
            }
            for (id in c.windows.keys) check("windows[$id]", id)
            for (id in c.places.keys) check("places[$id]", id)
            for (id in c.pools.keys) check("pools[$id]", id)
            for (id in c.pools.keys) if (id in BUILT_IN_POOLS) err(Rule.DUPLICATE_ID, "pools[$id]", "'$id' is a built-in pool")
            for (z in c.quietZones) check("quietZones[${z.id}]", z.id)
            for (i in c.items) check("items[${i.id}]", i.id)
            for ((id, n) in c.items.groupingBy { it.id }.eachCount()) if (n > 1) err(Rule.DUPLICATE_ID, "items[$id]", "$n items share id '$id'")
            for ((id, n) in c.quietZones.groupingBy { it.id }.eachCount()) if (n > 1) err(Rule.DUPLICATE_ID, "quietZones[$id]", "$n zones share id '$id'")
        }

        fun places() {
            for ((id, p) in c.places) {
                if (p.radiusM !in 1..2000) err(Rule.BAD_REF, "places[$id].radiusM", "radius ${p.radiusM} out of 1..2000")
                if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) err(Rule.BAD_REF, "places[$id]", "coordinate out of range")
            }
        }

        fun pools() {
            for ((id, pool) in c.pools) {
                if (pool.members.isEmpty()) err(Rule.BAD_REF, "pools[$id]", "empty pool")
                for ((i, raw) in pool.members.withIndex()) {
                    val path = "pools[$id].members[$i]"
                    when (val ref = Ref.parse(raw)) {
                        null -> err(Rule.BAD_REF, path, "malformed ref '$raw'")
                        is PoolRef -> err(Rule.BAD_REF, path, "pools cannot contain pools ('$raw')")
                        is PlaceRef -> placeRef(path, ref, exempt = false)
                        is FeatureRef -> {
                            val g = ref.goal
                            if (pool.goal != null && g != null && g != pool.goal) {
                                err(Rule.GOAL_MISMATCH, path, "'$raw' is a ${g.name} feature in a ${pool.goal.name} pool")
                            }
                            featureRef(path, ref, safety = true)
                        }
                    }
                }
            }
        }

        fun quietZones() {
            for (z in c.quietZones) {
                val path = "quietZones[${z.id}]"
                if (z.bufferM <= 0) err(Rule.BAD_REF, path, "bufferM must be > 0")
                when (val ref = Ref.parse(z.feature)) {
                    is TrailRef -> if (index != null && index.segments(ref).isEmpty()) err(Rule.BAD_REF, path, "unknown feature '${z.feature}'")
                    is PlaceRef -> if (ref.id !in c.places) err(Rule.BAD_REF, path, "unknown place '${z.feature}'")
                    else -> err(Rule.BAD_REF, path, "feature must be trail:<id> or place:<id>, was '${z.feature}'")
                }
            }
        }

        fun pauses() {
            for ((i, p) in c.pauses.withIndex()) {
                if (p.window !in c.windows) err(Rule.BAD_REF, "pauses[$i].window", "unknown window '${p.window}'")
                // pause lists are not placement pools: they may name roads that fail roadSafety (schedule.md §3.4)
                for ((j, raw) in p.features.withIndex()) {
                    when (val ref = Ref.parse(raw)) {
                        is PoolRef -> poolExists("pauses[$i].features[$j]", ref)
                        is FeatureRef -> featureRef("pauses[$i].features[$j]", ref, safety = false)
                        else -> err(Rule.BAD_REF, "pauses[$i].features[$j]", "'$raw' must be a feature or pool ref")
                    }
                }
            }
        }

        // --- refs -----------------------------------------------------------------------------------------------

        /** A remembrance card: the only marker allowed inside a quiet zone (PD-6): view-only, no marks, no effect. */
        fun quietCard(item: Item?): Boolean = item != null && item.lore == "remembrance" && item.resolve.by == ResolveBy.none &&
            item.marks == 0 && item.fx == null && item.variants.all { it.fx == null }

        fun placeRef(path: String, ref: PlaceRef, exempt: Boolean) {
            val p = c.places[ref.id] ?: return err(Rule.BAD_REF, path, "unknown place '${ref.raw}'")
            anchor(path, ref.raw, GeoPoint(p.lat, p.lon), exempt)
        }

        fun anchor(path: String, raw: String, p: GeoPoint, exempt: Boolean) {
            val zone = zones?.zoneAt(p) ?: return
            if (!exempt) err(Rule.QUIET_ZONE_ANCHOR, path, "'$raw' lies inside quiet zone '${zone.id}'")
        }

        fun poolExists(path: String, ref: PoolRef): Goal? {
            BUILT_IN_POOLS[ref.id]?.let { return it }
            val pool = c.pools[ref.id] ?: run {
                err(Rule.BAD_REF, path, "unknown pool '${ref.raw}'")
                return null
            }
            return pool.goal
        }

        /** The dataset rows [ref] names (a park: all its trails). */
        fun featuresOf(ref: FeatureRef): List<FeatureInfo> {
            val idx = index ?: return emptyList()
            return when (ref) {
                is TrailRef -> listOfNotNull(idx.feature(ref.id))
                is RoadRef -> listOfNotNull(idx.feature(ref.id))
                is StretchRef -> listOfNotNull(idx.feature(ref.featureId))
                is ParkRef -> idx.features(Goal.trails).filter { it.park == ref.name }
                is TrailheadRef, AllTrailheadsRef -> emptyList()
            }
        }

        fun featureRef(path: String, ref: FeatureRef, safety: Boolean) {
            if (ref is ParkRef && ref.name in c.excludedParks) {
                err(Rule.EXCLUDED_PARK, path, "'${ref.raw}' is an excluded park")
                return
            }
            val idx = index ?: return
            when (ref) {
                AllTrailheadsRef -> return
                is TrailheadRef -> {
                    val p = idx.point(ref) ?: return err(Rule.BAD_REF, path, "unknown trailhead '${ref.raw}'")
                    if (safety) anchor(path, ref.raw, p, exempt = false)
                    return
                }
                else -> Unit
            }
            if (ref.goal == null || idx.segments(ref).isEmpty()) return err(Rule.BAD_REF, path, "'${ref.raw}' not in the ${ref.goal ?: "?"} dataset")
            if (!safety) return
            for (f in featuresOf(ref)) {
                if (f.goal == Goal.roads) {
                    c.rules.roadSafety.check(f)?.let { err(Rule.ROAD_SAFETY, path, "${f.id} fails roadSafety: ${it.reason} (${f.category}, ${f.speedMph} mph, ${f.ownership})") }
                }
                if (f.park != null && f.park in c.excludedParks) err(Rule.EXCLUDED_PARK, path, "${f.id} is in excluded park '${f.park}'")
                val segs = if (ref is ParkRef) idx.segments(TrailRef(f.id)) else idx.segments(ref)
                zones?.touching(segs)?.let { err(Rule.QUIET_ZONE_TOUCH, path, "${f.id} touches quiet zone '${it.id}'") }
            }
        }

        // --- items ----------------------------------------------------------------------------------------------

        fun item(item: Item) {
            val p = "items[${item.id}]"
            val rec = c.effectiveWhen(item)
            if (rec == null) {
                err(Rule.BAD_REF, "$p.when.window", "unknown window '${item.`when`.window}'")
            } else {
                recurrence("$p.when", rec, definition = false)
                calendar(p, item, rec)
                if (item.type == ItemType.challenge || (item.type == ItemType.obstacle && item.hero)) serverSubset("$p.when", rec)
                if (item.type == ItemType.obstacle && rec.anchor?.at?.isTide == true) {
                    err(Rule.TIDE_ANCHORED_OBSTACLE, "$p.when.anchor", "obstacles are never anchored to the tide (PD-8)")
                }
            }
            if (item.goals.isEmpty()) err(Rule.MISSING_WHERE, "$p.goals", "no goals")
            for (g in item.goals) item.where[g]?.let { placement("$p.where.${g.name}", it, g, item) }
            if (item.goals.none { item.where[it] != null } &&
                item.resolve.by != ResolveBy.challenge && item.resolve.by != ResolveBy.none
            ) {
                err(Rule.MISSING_WHERE, "$p.where", "no placement for any of its goals ${item.goals}")
            }
            if (item.count !in 1..20) err(Rule.COUNT, "$p.count", "count ${item.count} out of 1..20")
            resolve(p, item)
            rewards(p, item)
            copy(p, item)
            for (fx in listOfNotNull(item.fx) + item.variants.mapNotNull { it.fx }) {
                if (fx !in knownFx) err(Rule.UNKNOWN_FX, "$p.fx", "unknown effect id '$fx'")
            }
            for ((i, v) in item.variants.withIndex()) {
                v.`when`.window?.let { if (it !in c.windows) err(Rule.BAD_REF, "$p.variants[$i].when.window", "unknown window '$it'") }
                for (s in v.`when`.season) if (s !in SEASON_IDS) err(Rule.RECURRENCE, "$p.variants[$i].when.season", "unknown season '$s'")
                for (s in v.`when`.climate) if (c.climate.none { it.id == s }) err(Rule.BAD_REF, "$p.variants[$i].when.climate", "unknown climate '$s'")
            }
            if (item.notBefore != null && item.expires != null && item.expires <= item.notBefore) {
                err(Rule.EMPTY_WINDOW, "$p.expires", "expires ${item.expires} is not after notBefore ${item.notBefore}")
            }
        }

        fun placement(path: String, pl: Placement, goal: Goal, item: Item) {
            val shapes = listOf(
                pl.at != null, pl.from != null || (pl.pick != null && pl.along == null), pl.along != null,
                pl.segments != null, pl.rotate != null,
            ).count { it }
            val ok = shapes == 1 && when {
                pl.at != null -> pl.pick == null && pl.minM == null && pl.epoch == null
                pl.from != null -> pl.pick != null && pl.minM == null && pl.epoch == null
                pl.along != null -> pl.minM == null && pl.epoch == null
                pl.segments != null -> pl.pick == null && pl.epoch == null
                pl.rotate != null -> pl.rotate.isNotEmpty() && pl.epoch != null && pl.pick == null && pl.minM == null
                else -> false
            }
            if (!ok) return err(Rule.PLACEMENT_SHAPE, path, "must be exactly one of {at} {pick,from} {along,pick?} {segments,minM?} {rotate,epoch}")
            if (pl.pick != null && pl.pick < 1) err(Rule.PLACEMENT_SHAPE, "$path.pick", "pick must be ≥ 1")
            val exempt = quietCard(item)
            when {
                pl.at != null -> when (val ref = Ref.parse(pl.at)) {
                    is PlaceRef -> placeRef("$path.at", ref, exempt)
                    else -> err(Rule.BAD_REF, "$path.at", "'${pl.at}' must be a place ref")
                }
                pl.from != null -> when (val ref = Ref.parse(pl.from)) {
                    is PoolRef -> placementPool("$path.from", ref, goal)
                    else -> err(Rule.BAD_REF, "$path.from", "'${pl.from}' must be a pool ref")
                }
                pl.rotate != null -> for ((i, raw) in pl.rotate.withIndex()) {
                    when (val ref = Ref.parse(raw)) {
                        is PoolRef -> placementPool("$path.rotate[$i]", ref, goal)
                        else -> err(Rule.BAD_REF, "$path.rotate[$i]", "'$raw' must be a pool ref")
                    }
                }
                else -> {
                    val raw = pl.along ?: pl.segments!!
                    val sub = if (pl.along != null) "$path.along" else "$path.segments"
                    when (val ref = Ref.parse(raw)) {
                        is PoolRef -> placementPool(sub, ref, goal)
                        is FeatureRef -> {
                            if (ref.goal != goal) err(Rule.GOAL_MISMATCH, sub, "'$raw' is not a ${goal.name} feature")
                            else featureRef(sub, ref, safety = true)
                        }
                        else -> err(Rule.BAD_REF, sub, "'$raw' must be a feature or pool ref")
                    }
                }
            }
        }

        fun placementPool(path: String, ref: PoolRef, goal: Goal) {
            val poolGoal = poolExists(path, ref)
            if (poolGoal != null && poolGoal != goal) err(Rule.GOAL_MISMATCH, path, "'${ref.raw}' is a ${poolGoal.name} pool used for ${goal.name}")
        }

        fun resolve(p: String, item: Item) {
            val r = item.resolve
            val path = "$p.resolve"
            when (r.by) {
                ResolveBy.proximity -> if ((r.radiusM ?: 0) <= 0) err(Rule.PLACEMENT_SHAPE, path, "proximity needs radiusM > 0")
                ResolveBy.segments -> if (!((r.minShare ?: 0.0) in 0.01..1.0 || (r.minM ?: 0) > 0)) {
                    err(Rule.PLACEMENT_SHAPE, path, "segments needs minShare in (0, 1] or minM > 0")
                }
                ResolveBy.riddle -> {
                    val raw = r.answer ?: return err(Rule.BAD_REF, path, "riddle needs an answer")
                    when (val ref = Ref.parse(raw)) {
                        is PlaceRef -> placeRef("$path.answer", ref, exempt = false)
                        is PoolRef -> poolExists("$path.answer", ref)
                        is FeatureRef -> featureRef("$path.answer", ref, safety = true)
                        null -> err(Rule.BAD_REF, "$path.answer", "malformed ref '$raw'")
                    }
                }
                ResolveBy.challenge -> if (item.type != ItemType.challenge) err(Rule.PLACEMENT_SHAPE, path, "resolve.by challenge is for challenge items only")
                ResolveBy.none -> Unit
            }
            if (item.type == ItemType.challenge) {
                if (r.by != ResolveBy.challenge) err(Rule.PLACEMENT_SHAPE, path, "challenge items resolve by challenge")
                if (item.challenge == null) err(Rule.PLACEMENT_SHAPE, "$p.challenge", "challenge items need a challenge spec")
            } else if (item.challenge != null) {
                err(Rule.PLACEMENT_SHAPE, "$p.challenge", "only challenge items carry a challenge spec")
            }
        }

        fun rewards(p: String, item: Item) {
            fun bad(msg: String) = err(Rule.REWARD, p, msg)
            if (item.marks !in 0..1000) bad("marks ${item.marks} out of 0..1000")
            if (item.type != ItemType.treasure && item.tiers.isNotEmpty()) bad("only treasures have tiers")
            if (item.type != ItemType.obstacle && item.bands.isNotEmpty()) bad("only obstacles have bands")
            if (item.type != ItemType.riddle && item.stars != null) bad("only riddles have stars")
            when (item.type) {
                ItemType.treasure -> {
                    if (item.marks != 0) bad("treasure marks come from tiers, marks must be 0")
                    if (item.tiers.isEmpty()) bad("treasure needs tiers")
                    for (t in item.tiers) {
                        if (t.marks != TIER_MARKS.getValue(t.tier)) bad("tier ${t.tier} pays ${TIER_MARKS.getValue(t.tier)}, not ${t.marks}")
                        if (t.count < 1) bad("tier ${t.tier} count ${t.count} < 1")
                        if (t.everyNthWeek != null && (t.everyNthWeek < 2 || t.elseTier == null)) bad("tier ${t.tier}: everyNthWeek needs ≥ 2 and an else tier")
                    }
                    if (item.tiers.isNotEmpty() && item.tiers.sumOf { it.count } != item.count) bad("tier counts sum to ${item.tiers.sumOf { it.count }}, count is ${item.count}")
                }
                ItemType.egg -> if (item.marks != EGG_MARKS) bad("an egg pays $EGG_MARKS, not ${item.marks}")
                ItemType.riddle -> {
                    val s = item.stars
                    if (s == null || s !in 1..3) bad("riddle stars must be 1..3, was $s")
                    else if (item.marks != 0 && item.marks != s * RIDDLE_MARKS_PER_STAR) bad("a $s-star riddle pays ${s * RIDDLE_MARKS_PER_STAR}, not ${item.marks}")
                }
                ItemType.obstacle -> {
                    if (item.bands.isNotEmpty()) {
                        if (item.bands != KRAKEN_BANDS) bad("kraken bands must be $KRAKEN_BANDS")
                        if (item.marks != 0) bad("kraken marks come from bands, marks must be 0")
                    } else if (item.marks != BRAMBLE_MARKS) {
                        bad("an obstacle without bands pays $BRAMBLE_MARKS, not ${item.marks}")
                    }
                }
                ItemType.lore -> {
                    val expected = if (item.lore == "remembrance" || item.resolve.by == ResolveBy.none) 0 else LORE_MARKS
                    if (item.marks != expected) {
                        bad(if (expected == 0) "remembrance and view-only lore pay 0, not ${item.marks}" else "lore pays $LORE_MARKS, not ${item.marks}")
                    }
                }
                ItemType.creature -> if (item.marks != CREATURE_MARKS) bad("a creature pays $CREATURE_MARKS, not ${item.marks}")
                ItemType.ambient -> if (item.marks != 0) bad("ambient items pay 0")
                ItemType.challenge -> {
                    val ch = item.challenge ?: return
                    when (ch.scope) {
                        ChallengeScope.solo -> if (item.marks !in SOLO_CHALLENGE_MARKS) bad("a solo challenge pays one of $SOLO_CHALLENGE_MARKS, not ${item.marks}")
                        ChallengeScope.island -> {
                            if (item.marks != ISLAND_CHALLENGE_MARKS) bad("an island challenge pays $ISLAND_CHALLENGE_MARKS, not ${item.marks}")
                            if (ch.retreatMarks != null && ch.retreatMarks != RETREAT_MARKS) bad("F-RETREAT pays $RETREAT_MARKS, not ${ch.retreatMarks}")
                        }
                    }
                }
            }
        }

        fun copy(p: String, item: Item) {
            fun label(path: String, s: String, rule: Rule) {
                if (s.isBlank()) err(rule, path, "blank")
                else if (s.length > MAX_LABEL) err(rule, path, "${s.length} characters > $MAX_LABEL")
            }
            label("$p.copy.title", item.copy.title, Rule.COPY)
            label("$p.a11y.label", item.a11y.label, Rule.A11Y)
            label("$p.a11y.announce", item.a11y.announce, Rule.A11Y)
            val texts = mutableListOf("copy" to item.copy)
            for ((i, v) in item.variants.withIndex()) v.copy?.let { texts += "variants[$i].copy" to it }
            val strings = texts.flatMap { (path, cp) ->
                listOfNotNull("$path.title" to cp.title, "$path.body" to cp.body, cp.found?.let { "$path.found" to it }, cp.missed?.let { "$path.missed" to it })
            } + listOf("a11y.label" to item.a11y.label, "a11y.announce" to item.a11y.announce)
            for ((path, s) in strings) {
                if ('!' in s) err(Rule.COPY, "$p.$path", "no exclamation marks")
                BANNED.find(s)?.let { err(Rule.COPY, "$p.$path", "'${it.value}' is not Play vocabulary (PD-16)") }
            }
        }

        // --- recurrences ------------------------------------------------------------------------------------------

        fun recurrence(path: String, r: Recurrence, definition: Boolean) {
            if (definition && r.window != null) err(Rule.RECURRENCE, path, "a window cannot reference another window")
            val every = r.every ?: return err(Rule.RECURRENCE, "$path.every", "missing")
            val rules = r.on.map { it to DayRule.parse(it) }
            for ((raw, rule) in rules) {
                val fits = when (every) {
                    Every.week -> rule is DayRule.Weekday
                    Every.month, Every.year -> rule is DayRule.MonthDay || rule is DayRule.NthWeekday
                    else -> false
                }
                if (!fits) err(Rule.RECURRENCE, "$path.on", "'$raw' is not a valid day for every: ${every.name}")
            }
            if (every == Every.year && r.inMonths.isEmpty()) err(Rule.RECURRENCE, "$path.in", "every: year needs `in`")
            if (every == Every.dates && r.dates.isEmpty()) err(Rule.RECURRENCE, "$path.dates", "every: dates needs dates")
            if (every != Every.dates && r.dates.isNotEmpty()) err(Rule.RECURRENCE, "$path.dates", "dates only with every: dates")
            if (r.fallback != null) {
                if (every != Every.dates) err(Rule.RECURRENCE, "$path.fallback", "fallback only with every: dates")
                if (r.fallback.every == Every.dates) err(Rule.RECURRENCE, "$path.fallback", "a fallback cannot be dates")
                recurrence("$path.fallback", r.fallback, definition = true)
            }
            r.days?.let { if (it !in 1..31) err(Rule.RECURRENCE, "$path.days", "days $it out of 1..31") }
            for (s in r.season) if (s !in SEASON_IDS && c.climate.none { it.id == s }) err(Rule.RECURRENCE, "$path.season", "unknown season '$s'")
            for (x in r.except) if (x !in c.windows) err(Rule.BAD_REF, "$path.except", "unknown window '$x'")
            if (every == Every.always) {
                if (r.from != null || r.to != null || r.on.isNotEmpty() || r.days != null || r.anchor != null) {
                    err(Rule.RECURRENCE, path, "every: always takes no from/to/on/days/anchor")
                }
            } else {
                val f = timeExpr("$path.from", r.from, asFrom = true)
                val t = timeExpr("$path.to", r.to, asFrom = false)
                if (f != null && t != null && simDates.any { d -> startsOn(r, d) }) {
                    val anyNonEmpty = simDates.any { d -> startsOn(r, d) && window(d, f, t, r.days ?: 1)?.let { it.first < it.second } == true }
                    if (!anyNonEmpty) err(Rule.EMPTY_WINDOW, path, "from < to never holds in $simDays simulated days")
                }
            }
            r.anchor?.let { a ->
                for ((name, v) in listOf("before" to a.before, "after" to a.after)) {
                    if (Dur.minutesOrNull(v) == null) err(Rule.TIME_EXPR, "$path.anchor.$name", "invalid duration '$v'")
                }
                a.fallback?.let { fb ->
                    timeExpr("$path.anchor.fallback.from", fb.from, asFrom = true)
                    timeExpr("$path.anchor.fallback.to", fb.to, asFrom = false)
                }
                if (a.max != null && a.max < 1) err(Rule.RECURRENCE, "$path.anchor.max", "max must be ≥ 1")
            }
        }

        fun timeExpr(path: String, s: String?, asFrom: Boolean): TimeExpr? {
            if (s == null) {
                err(Rule.RECURRENCE, path, "missing")
                return null
            }
            val e = TimeExpr.parseOrNull(s) ?: run {
                err(Rule.TIME_EXPR, path, "invalid TimeExpr '$s'")
                return null
            }
            if (asFrom && !e.validAsFrom) {
                err(Rule.TIME_EXPR, path, "'$s': a relative time is only valid for `to`")
                return null
            }
            return e
        }

        fun serverSubset(path: String, r: Recurrence) {
            if (r.every == Every.always) return
            val f = r.from?.let(TimeExpr::parseOrNull)
            val t = r.to?.let(TimeExpr::parseOrNull)
            if ((f != null && f !is TimeExpr.Clock) || (t != null && t !is TimeExpr.Clock)) {
                err(Rule.SERVER_SUBSET, path, "challenge and hero windows use HH:MM times (the Worker evaluates them)")
            }
            if (r.anchor != null) err(Rule.SERVER_SUBSET, "$path.anchor", "challenge and hero windows cannot be anchored")
        }

        // --- calendar simulation ----------------------------------------------------------------------------------

        /** Date level, the scheduler's and the Worker's rule ([Recurrences.startsOn]). */
        fun startsOn(r: Recurrence, d: LocalDate): Boolean = Recurrences.startsOn(c, r, d)

        /** [from, to] of an occurrence starting [d] spanning [days] days, or null when a sun event is missing. */
        fun window(d: LocalDate, f: TimeExpr, t: TimeExpr, days: Int): Pair<Instant, Instant>? {
            val start = f.resolve(d, sun(d)) ?: return null
            val last = d.plus(days - 1, DateTimeUnit.DAY)
            val end = t.resolve(last, sun(last), from = start) ?: return null
            return start to end
        }

        fun calendar(p: String, item: Item, r: Recurrence) {
            if (r.every == null || r.every == Every.always) return
            val f = r.from?.let(TimeExpr::parseOrNull)?.takeIf { it.validAsFrom } ?: return
            val t = r.to?.let(TimeExpr::parseOrNull) ?: return
            val days = r.days ?: 1
            when {
                item.type == ItemType.ambient -> {
                    val hours = c.rules.ambientHours
                    val lo = TimeExpr.parseOrNull(hours.from) ?: return
                    val hi = TimeExpr.parseOrNull(hours.to) ?: return
                    firstBad(r, f, t, days) { d, w ->
                        w.first < lo.resolve(d)!! || w.second > hi.resolve(d.plus(days - 1, DateTimeUnit.DAY))!!
                    }?.let { err(Rule.AMBIENT_HOURS, "$p.when", "ambient window leaves ${hours.from}–${hours.to} on $it") }
                }
                item.type == ItemType.creature -> {
                    if (days > 1) return err(Rule.CREATURE_HOURS, "$p.when", "creatures are single-day items")
                    firstBad(r, f, t, days) { d, w ->
                        val s = sun(d)
                        val lo = s.sunrise?.plus(30.minutes)
                        val hi = s.sunset?.minus(30.minutes)
                        lo == null || hi == null || w.first < lo || w.second > hi
                    }?.let { err(Rule.CREATURE_HOURS, "$p.when", "outside [sunrise + 30 min, sunset − 30 min] on $it (PD-5)") }
                }
                item.resolve.by != ResolveBy.none && days <= 1 -> {
                    firstBad(r, f, t, days) { d, w ->
                        val s = sun(d)
                        (s.dawn != null && w.first < s.dawn) || (s.dusk != null && w.second > s.dusk)
                    }?.let { err(Rule.DAYLIGHT, "$p.when", "a resolvable window outside [civil dawn, civil dusk] on $it (PD-5)") }
                }
            }
        }

        /** The first simulated start date whose window fails [bad], or null. */
        fun firstBad(r: Recurrence, f: TimeExpr, t: TimeExpr, days: Int, bad: (LocalDate, Pair<Instant, Instant>) -> Boolean): LocalDate? {
            for (d in simDates) {
                if (!startsOn(r, d)) continue
                val w = window(d, f, t, days) ?: return d
                if (bad(d, w)) return d
            }
            return null
        }

        /**
         * Hero items per goal: at most one active at any instant and at most one starting per island day (§3.4 #3).
         * The loser (lower priority, then the later id) must carry `yields: true`.
         */
        fun heroes() {
            val killed = c.kill.items.toSet()
            val heroes = c.items.filter { it.hero && it.enabled && it.id !in killed && it.type !in c.kill.types }
            if (heroes.size < 2) return
            val spans = heroes.associateWith { occurrences(it) }
            for (goal in Goal.entries) {
                val hs = heroes.filter { goal in it.goals }
                for (i in hs.indices) for (j in i + 1 until hs.size) {
                    val a = hs[i]
                    val b = hs[j]
                    val clash = clash(spans.getValue(a), spans.getValue(b)) ?: continue
                    val loser = if (a.priority != b.priority) (if (a.priority < b.priority) a else b) else maxOf(a, b, compareBy { it.id })
                    val winner = if (loser === a) b else a
                    if (!loser.yields) {
                        err(Rule.HERO_CONFLICT, "items[${loser.id}]", "conflicts with hero '${winner.id}' (${goal.name}) on $clash and does not yield")
                    }
                }
            }
        }

        /** (start, end, start date) of every simulated occurrence; `always` = the whole simulated span. */
        fun occurrences(item: Item): List<Triple<Instant, Instant, LocalDate>> {
            val r = c.effectiveWhen(item) ?: return emptyList()
            if (r.every == Every.always) {
                val s = IslandTime.instantOf(simStart, LocalTime(0, 0))
                return listOf(Triple(s, IslandTime.instantOf(simDates.last(), LocalTime(23, 59)), simStart))
            }
            val f = r.from?.let(TimeExpr::parseOrNull)?.takeIf { it.validAsFrom } ?: return emptyList()
            val t = r.to?.let(TimeExpr::parseOrNull) ?: return emptyList()
            return simDates.filter { startsOn(r, it) }.mapNotNull { d -> window(d, f, t, r.days ?: 1)?.let { Triple(it.first, it.second, d) } }
        }

        fun clash(a: List<Triple<Instant, Instant, LocalDate>>, b: List<Triple<Instant, Instant, LocalDate>>): LocalDate? {
            for (x in a) for (y in b) {
                if (x.third == y.third || (x.first < y.second && y.first < x.second)) return maxOf(x.third, y.third)
            }
            return null
        }

        // --- immutability -----------------------------------------------------------------------------------------

        fun immutability(previous: PlayConfig) {
            val now = c.items.associateBy { it.id }
            for (old in previous.items) {
                val cur = now[old.id]
                if (cur == null) {
                    if (old.expires == null) err(Rule.REMOVED_WITHOUT_EXPIRES, "items[${old.id}]", "a published id is removed only after it was given `expires`")
                    continue
                }
                val changed = buildList {
                    if (cur.`when` != old.`when`) add("when")
                    if (cur.where != old.where) add("where")
                    if (cur.count != old.count) add("count")
                    if (cur.marks != old.marks) add("marks")
                    if (cur.tiers != old.tiers) add("tiers")
                    if (cur.bands != old.bands) add("bands")
                    if (cur.stars != old.stars) add("stars")
                    if (cur.fx != old.fx) add("fx")
                }
                if (changed.isNotEmpty()) {
                    err(Rule.IMMUTABLE, "items[${old.id}]", "published id changed $changed: publish a new id and set `expires` on this one (PD-11)")
                }
            }
        }
    }
}
