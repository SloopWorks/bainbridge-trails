package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Every
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Placement
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigValidator
import com.bainbridgetrails.play.config.Recurrence
import com.bainbridgetrails.play.config.Recurrences
import com.bainbridgetrails.play.config.Repeat
import com.bainbridgetrails.play.config.ResolveBy
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.config.TimeExpr
import com.bainbridgetrails.play.config.VariantWhen
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.QuietZones
import com.bainbridgetrails.play.geo.passes
import com.bainbridgetrails.play.rng.Fnv1a
import com.bainbridgetrails.play.rng.SplitMix64
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.Sun
import com.bainbridgetrails.play.time.SunDay
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The pack build's per-goal table (`data/play/derived/<goal>.json`, bundled as `files/play/derived-<goal>.json`):
 * [playSafe] = the built-in `pool:play-safe-<goal>` (counted/included, `roadSafety`, no quiet-zone contact, no
 * excluded park); [includedSegments] = the goal's included segment ids (the keys of `segmentSquares`), the only
 * segments a point placement may land on (schedule.md §3.4 #6).
 */
data class DerivedGoal(val goal: Goal, val playSafe: List<String>, val includedSegments: Set<String>?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): DerivedGoal {
            val root = json.parseToJsonElement(text).jsonObject
            val goal = Goal.valueOf(root.getValue("goal").jsonPrimitive.content)
            val pool = root["pools"]?.jsonObject?.get("play-safe-${goal.name}")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val included = (root["segmentSquares"] as? JsonObject)?.keys?.toSet()
            return DerivedGoal(goal, pool, included)
        }
    }
}

/**
 * The device-side scheduler (schedule.md §5): pure and deterministic over (config, places, tides, ferry, date, goal,
 * salt). [dayPlan] resolves every occurrence touching an island day: windows (recurrence DSL), placements (seeded per
 * item and occurrence, §2.2), the daylight / play-hours / tide spans in which each can be sensed (§3.4 #1, PD-8), hero
 * conflicts (§3.4 #3), quiet days (PD-6) and the density caps ([Density]). [agenda] filters the memoized plans by
 * instant and user state.
 *
 * Memoizes day plans (a small LRU) and treasure draws (the `spotCooldown` replay chain) internally. Not thread-safe:
 * call it from one dispatcher.
 */
class PlayScheduler(
    val config: PlayConfig,
    private val places: PlaceIndex,
    private val tides: TideTable?,
    private val ferry: FerryTimetable?,
    private val derived: Map<Goal, DerivedGoal> = emptyMap(),
) {
    private val islandSeed: ULong = config.islandSeed.toULongOrNull(16) ?: 0uL
    private val recs: Map<String, Recurrence?> = config.items.associate { it.id to config.effectiveWhen(it) }
    private val killedTypes = config.kill.types.toSet()
    private val killedItems = config.kill.items.toSet()
    private val playFrom = TimeExpr.parseOrNull(config.rules.playHours.from) as? TimeExpr.Clock
    private val playTo = TimeExpr.parseOrNull(config.rules.playHours.to) as? TimeExpr.Clock

    private data class PlanKey(val date: LocalDate, val goal: Goal, val salt: ULong)

    private val plans = LinkedHashMap<PlanKey, DayPlan>()
    private val suns = HashMap<LocalDate, SunDay>()
    private val draws = HashMap<String, List<Pick>>()
    private val chains = HashMap<String, Chain>()
    private val segCache = HashMap<String, List<SegRef>>()
    private val centres = HashMap<String, GeoPoint?>()
    private val playSafe = HashMap<Goal, List<String>>()

    // --- public API -------------------------------------------------------------------------------------------------

    /** Everything Play shows on island [date] for [goal]. [salt] seeds personal (v2) items only. */
    fun dayPlan(date: LocalDate, goal: Goal, salt: ULong = 0uL): DayPlan {
        val key = PlanKey(date, goal, salt)
        plans[key]?.let { return it }
        val plan = buildPlan(date, goal, salt)
        plans[key] = plan
        if (plans.size > PLAN_CACHE) plans.remove(plans.keys.first())
        return plan
    }

    /** What is on now, what comes in the next 7 days, and the day's display facts (schedule.md §5.2). */
    fun agenda(now: Instant, ctx: PlayContext, user: PlayUserState = PlayUserState()): PlayAgenda {
        val date = IslandTime.dateOf(now)
        val salt = user.salt ?: 0uL
        val plan = dayPlan(date, ctx.goal, salt)
        val active = ArrayList<ActiveItem>()
        for (o in plan.occurrences) {
            if (!o.activeAt(now)) continue
            active += ActiveItem(o, gate(o, now), found(o, user))
        }
        val near = ctx.currentFeature
        active.sortWith(
            compareBy<ActiveItem>(
                { if (near != null && it.occurrence.placements.any { p -> p.featureId == near }) 0 else 1 },
                { Density.rank(it.occurrence) },
                { it.occurrence.key },
            ),
        )
        val horizon = now + 7.days
        val seen = HashSet<String>()
        val upcoming = ArrayList<UpcomingItem>()
        for (i in 0..7) {
            for (o in dayPlan(date.plus(i, DateTimeUnit.DAY), ctx.goal, salt).occurrences) {
                if (o.scope != Scope.island || o.start <= now || o.start >= horizon || o.type == ItemType.ambient) continue
                if (seen.add(o.key)) upcoming += UpcomingItem(o.key, o.itemId, o.type, o.start, o.end)
            }
        }
        upcoming.sortWith(compareBy({ it.start }, { it.key }))
        return PlayAgenda(active, upcoming, DayInfo(date, plan.sun, plan.tides, nextFerryArrival(now, date), plan.quietDay))
    }

    // --- plan -------------------------------------------------------------------------------------------------------

    private fun sun(d: LocalDate): SunDay = suns.getOrPut(d) { Sun.sunEvents(d) }

    private fun buildPlan(date: LocalDate, goal: Goal, salt: ULong): DayPlan {
        val sunDay = sun(date)
        val day = Span(Recurrences.wallInstant(date, 0), Recurrences.wallInstant(date, TimeExpr.MINUTES_PER_DAY))
        val quiet = Recurrences.isQuietDay(config, date)
        val light = daylight(date, sunDay)
        val out = ArrayList<Occurrence>()
        if (!config.kill.all) for (item in config.items) {
            if (!live(item, goal)) continue
            if (quiet && !quietCard(item)) continue
            val rec = recs[item.id] ?: continue
            val placement = item.where[goal]
            for ((occDate, w) in windowsTouching(item, rec, date, day)) {
                if (item.hero && heroSuppressed(item, goal, occDate, w)) continue
                out += occurrence(item, goal, placement, occDate, w, date, day, light, sunDay, salt)
            }
        }
        val dense = Density.apply(out, config.rules.maxPlacementsPerCell, config.rules.maxVisiblePlacements)
        return DayPlan(date, goal, quiet, sunDay, tides?.events(date), dense.occurrences, dense.dropped)
    }

    /** Enabled, not killed, in [goal], and placed for it (or a placement-free card / challenge). */
    private fun live(item: Item, goal: Goal): Boolean {
        if (!item.enabled || item.id in killedItems || item.type in killedTypes || goal !in item.goals) return false
        if (item.where[goal] != null) return true
        return item.where.isEmpty() && (item.resolve.by == ResolveBy.none || item.resolve.by == ResolveBy.challenge)
    }

    /** A remembrance card: the only content a quiet day keeps (PD-6; the validator's rule). */
    private fun quietCard(item: Item): Boolean =
        item.lore == "remembrance" && item.resolve.by == ResolveBy.none && item.marks == 0 && item.fx == null

    /**
     * (start date, window) of every occurrence of [item] whose window overlaps [day]. A permanent item lives from its
     * `notBefore` (else the pack epoch; neither = no occurrence, as on the Worker) to the end of its `expires` day.
     */
    private fun windowsTouching(item: Item, rec: Recurrence, date: LocalDate, day: Span): List<Pair<LocalDate, OccWindow>> {
        if (rec.every == Every.always) {
            val d0 = Recurrences.alwaysDate(config, item) ?: return emptyList()
            val w = OccWindow(Recurrences.wallInstant(d0, 0), item.expires?.let { Recurrences.wallInstant(it, TimeExpr.MINUTES_PER_DAY) }, null)
            return if (w.start < day.end && (w.end == null || w.end > day.start)) listOf(d0 to w) else emptyList()
        }
        val out = ArrayList<Pair<LocalDate, OccWindow>>(1)
        for (k in Recurrences.spanDays(rec) downTo 0) {
            val s = date.minus(k, DateTimeUnit.DAY)
            if (item.notBefore != null && s < item.notBefore) continue
            if (item.expires != null && s > item.expires) continue
            for (w in Recurrences.windowsOn(config, rec, s, sun(s), tides, ferry)) {
                if (w.start < day.end && w.end > day.start) out += s to OccWindow(w.start, w.end, w.sub)
            }
        }
        return out
    }

    private data class OccWindow(val start: Instant, val end: Instant?, val sub: Int?)

    private fun occurrence(
        item: Item,
        goal: Goal,
        placement: Placement?,
        occDate: LocalDate,
        w: OccWindow,
        date: LocalDate,
        day: Span,
        light: Span?,
        sunDay: SunDay,
        salt: ULong,
    ): Occurrence {
        val key = "${item.id}@$occDate" + (w.sub?.let { "#$it" } ?: "")
        var seed = occSeed(item.id, occDate)
        if (item.scope == Scope.personal) seed = SplitMix64.mix64(seed xor salt)
        val placements = placement?.let { placements(item, goal, it, occDate, seed, key) }.orEmpty()
        val sensable = item.type != ItemType.ambient &&
            (item.resolve.by == ResolveBy.proximity || item.resolve.by == ResolveBy.segments || item.resolve.by == ResolveBy.riddle)
        var daySpans: List<Span> = emptyList()
        if (sensable && light != null) {
            var s: Span? = Span(w.start, w.end ?: day.end).intersect(day)?.intersect(light)
            if (s != null && item.type == ItemType.creature) {
                val lo = sunDay.sunrise?.plus(30.minutes)
                val hi = sunDay.sunset?.minus(30.minutes)
                s = if (lo == null || hi == null || hi <= lo) null else s.intersect(Span(lo, hi))
            }
            daySpans = listOfNotNull(s)
        }
        val tideGated = item.type == ItemType.obstacle
        val resolvable = if (tideGated) daySpans.flatMap { TideGate.lowSpans(tides, it, config.rules.krakenMaxTideM) } else daySpans
        return Occurrence(
            key = key, itemId = item.id, type = item.type, scope = item.scope, start = w.start, end = w.end, hero = item.hero,
            placements = placements, marks = item.marks, variant = variant(item, date), sensable = sensable,
            daySpans = daySpans, resolvable = resolvable, tideGated = tideGated, hidden = item.hidden,
        )
    }

    /** [civil dawn, civil dusk] ∩ `rules.playHours` on [date]; null when empty or the sun events are missing. */
    private fun daylight(date: LocalDate, s: SunDay): Span? {
        val dawn = s.dawn ?: return null
        val dusk = s.dusk ?: return null
        val lo = playFrom?.let { Recurrences.wallInstant(date, it.minuteOfDay) } ?: dawn
        val hi = playTo?.let { Recurrences.wallInstant(date, it.minuteOfDay) } ?: dusk
        return Span(maxOf(dawn, lo), minOf(dusk, hi)).takeUnless { it.isEmpty }
    }

    private fun gate(o: Occurrence, now: Instant): Gate {
        if (!o.sensable) return Gate.VIEW
        if (o.resolvable.any { now in it }) return Gate.OPEN
        if (o.daySpans.any { now in it }) {
            return when (TideGate.state(tides, now, config.rules.krakenMaxTideM)) {
                TideGate.State.HIGH -> Gate.TIDE_HIGH
                TideGate.State.UNKNOWN -> Gate.TIDE_UNKNOWN
                TideGate.State.LOW -> Gate.OPEN // on a solved crossing to the millisecond
            }
        }
        return Gate.SLEEPING
    }

    private fun found(o: Occurrence, user: PlayUserState): Set<String> {
        if (user.foundOcc.isEmpty()) return emptySet()
        val out = HashSet<String>()
        if (o.key in user.foundOcc) out += o.key
        for (p in o.placements) if (p.key in user.foundOcc) out += p.key
        return out
    }

    private fun nextFerryArrival(now: Instant, date: LocalDate): Instant? {
        val tt = ferry ?: return null
        for (d in listOf(date, date.plus(1, DateTimeUnit.DAY))) {
            tt.departures(d, FerryTimetable.Terminal.SEATTLE)?.firstOrNull { it.arrive > now }?.let { return it.arrive }
        }
        return null
    }

    /** Index of the first variant whose `when` matches [date] (variants never change rewards or windows). */
    private fun variant(item: Item, date: LocalDate): Int? {
        if (item.variants.isEmpty()) return null
        val i = item.variants.indexOfFirst { matches(it.`when`, date) }
        return if (i < 0) null else i
    }

    private fun matches(v: VariantWhen, date: LocalDate): Boolean {
        if (v.month.isNotEmpty() && v.month.none { it.number == date.month.number }) return false
        if (v.season.isNotEmpty() && IslandTime.season(date).id !in v.season && Recurrences.climateOf(config, date) !in v.season) return false
        if (v.climate.isNotEmpty() && Recurrences.climateOf(config, date) !in v.climate) return false
        if (v.window != null && config.windows[v.window]?.let { Recurrences.covers(config, it, date) } != true) return false
        return true
    }

    // --- heroes -----------------------------------------------------------------------------------------------------

    /**
     * §3.4 #3: a hero occurrence is suppressed (never shifted) when a higher-ranked hero of the same goal (priority,
     * then the lower id) has an occurrence overlapping it or starting the same island day.
     */
    private fun heroSuppressed(item: Item, goal: Goal, occDate: LocalDate, w: OccWindow): Boolean {
        val mine = recs[item.id] ?: return false
        for (other in config.items) {
            if (other === item || !other.hero || !live(other, goal)) continue
            val outranks = other.priority > item.priority || (other.priority == item.priority && other.id < item.id)
            if (!outranks) continue
            val rec = recs[other.id] ?: continue
            if (rec.every == Every.always) return true
            val reach = Recurrences.spanDays(rec) + Recurrences.spanDays(mine) + 1
            for (k in -reach..reach) {
                val s = occDate.plus(k, DateTimeUnit.DAY)
                if (other.notBefore != null && s < other.notBefore) continue
                if (other.expires != null && s > other.expires) continue
                for (ow in Recurrences.windowsOn(config, rec, s, sun(s), tides, ferry)) {
                    if (s == occDate || (ow.start < (w.end ?: ow.end) && w.start < ow.end)) return true
                }
            }
        }
        return false
    }

    // --- placements -------------------------------------------------------------------------------------------------

    /** A drawn placement before keys and tiers. */
    private data class Pick(val ref: String, val featureId: String?, val segmentIds: List<String>, val point: GeoPoint?)

    private fun placements(item: Item, goal: Goal, pl: Placement, occDate: LocalDate, seed: ULong, occKey: String): List<ResolvedPlacement> {
        val picks: List<Pick> = when {
            pl.at != null -> listOfNotNull(placePick(pl.at))
            pl.rotate != null -> KrakenRotation.pool(pl, occDate)?.let { members(it, goal) }.orEmpty().mapNotNull { whole(it, goal) }
            pl.segments != null -> refsOf(pl.segments, goal).mapNotNull { whole(it, goal) }
            pl.along != null -> along(pl.along, pl.pick ?: 1, goal, seed)
            pl.from != null && item.type == ItemType.treasure -> treasureDraw(item, goal, pl, occDate)
            pl.from != null -> {
                val rng = SplitMix64(seed)
                rng.pick(pl.pick ?: 1, members(pl.from, goal)) { it }.mapNotNull { pointPick(it, goal, seed) }
            }
            else -> emptyList()
        }
        val multi = item.repeat == Repeat.weekly_set || item.repeat == Repeat.per_attack
        val width = (maxOf(picks.size, pl.pick ?: 0, item.count) - 1).coerceAtLeast(0).toString().length
        val tiers = if (item.tiers.isNotEmpty()) tiersFor(item, occDate) else emptyList()
        return picks.mapIndexed { i, p ->
            val tier = tiers.getOrNull(i)
            ResolvedPlacement(
                key = if (multi) "$occKey#${i.toString().padStart(width, '0')}" else occKey,
                index = i,
                ref = p.ref,
                featureId = p.featureId,
                segmentIds = p.segmentIds,
                point = p.point,
                square = p.point?.let { ChartSquare.of(it).id },
                tier = tier?.first,
                marks = tier?.second ?: item.marks,
            )
        }
    }

    /**
     * The weekly set's tiers in placement order (product §4.1): a tier with `everyNthWeek` n runs in weeks whose index
     * (whole weeks since the Monday of the pack epoch, the launch week = 0) is a multiple of n, else its `else` tier.
     */
    private fun tiersFor(item: Item, occDate: LocalDate): List<Pair<TierName, Int>> {
        val week0 = IslandTime.weekId(config.epoch ?: occDate)
        val week = week0.daysUntil(occDate).floorDiv(7)
        val out = ArrayList<Pair<TierName, Int>>()
        for (t in item.tiers) {
            val n = t.everyNthWeek
            val name = if (n != null && n > 0 && week.mod(n) != 0) t.elseTier ?: continue else t.tier
            val marks = if (name == t.tier) t.marks else item.tiers.firstOrNull { it.tier == name }?.marks ?: PlayConfigValidator.TIER_MARKS.getValue(name)
            repeat(t.count) { out += name to marks }
        }
        return out
    }

    private fun placePick(ref: String): Pick? {
        val id = (Ref.parse(ref) as? PlaceRef)?.id ?: return null
        val p = config.places[id] ?: return null
        return Pick(ref, null, emptyList(), GeoPoint(p.lat, p.lon))
    }

    /** A pool's member refs: the built-in Play-safe pool of [goal], or the pack pool's members. */
    private fun members(poolRef: String, goal: Goal): List<String> {
        val id = (Ref.parse(poolRef) as? PoolRef)?.id ?: return emptyList()
        PlayConfigValidator.BUILT_IN_POOLS[id]?.let { g ->
            val prefix = if (g == Goal.trails) "trail:" else "road:"
            return playSafe(g).map { prefix + it }
        }
        return config.pools[id]?.members.orEmpty()
    }

    /** A pool ref's members, or the single feature ref. */
    private fun refsOf(ref: String, goal: Goal): List<String> = if (ref.startsWith("pool:")) members(ref, goal) else listOf(ref)

    private fun featureIdOf(ref: FeatureRef): String? = when (ref) {
        is TrailRef -> ref.id
        is RoadRef -> ref.id
        is StretchRef -> ref.featureId
        else -> null
    }

    /** Segments of [ref] a placement may use: a stretch's as derived; a feature's included ones. */
    private fun segs(ref: String, goal: Goal): List<SegRef> = segCache.getOrPut("$goal|$ref") {
        val r = Ref.parse(ref) as? FeatureRef ?: return@getOrPut emptyList()
        val all = places.segments(r).filter { it.line.size >= 2 && it.lengthM > 0.0 }
        val included = derived[r.goal ?: goal]?.includedSegments
        if (r is StretchRef || included == null) all else all.filter { it.id in included }
    }

    /** A whole feature or stretch (kraken, `segments:`), marked at its half-length point. */
    private fun whole(ref: String, goal: Goal): Pick? {
        if (ref.startsWith("place:")) return placePick(ref)
        val r = Ref.parse(ref) as? FeatureRef ?: return null
        val s = segs(ref, goal)
        if (s.isEmpty()) return null
        val total = s.sumOf { it.lengthM }
        var acc = 0.0
        var mid = s.last()
        for (seg in s) {
            if (acc + seg.lengthM >= total / 2) {
                mid = seg
                break
            }
            acc += seg.lengthM
        }
        return Pick(ref, featureIdOf(r), s.map { it.id }, round(GeoPoint(mid.midLat, mid.midLon)))
    }

    /** `along`: [k] seeded points (pool members, or segments of one feature), jittered along their segment. */
    private fun along(ref: String, k: Int, goal: Goal, seed: ULong): List<Pick> {
        if (ref.startsWith("pool:")) {
            return SplitMix64(seed).pick(k, members(ref, goal)) { it }.mapNotNull { pointPick(it, goal, seed) }
        }
        if (k <= 1) return listOfNotNull(pointPick(ref, goal, seed))
        val r = Ref.parse(ref) as? FeatureRef ?: return emptyList()
        val rng = SplitMix64(SplitMix64.mix64(seed xor Fnv1a.hash64(ref)))
        return rng.pick(k, segs(ref, goal)) { it.id }.map { s -> Pick(ref, featureIdOf(r), listOf(s.id), jitter(s, rng)) }
    }

    /**
     * One seeded point on [ref] (a place, or a trail/road): a segment drawn by length, then a point at fraction
     * 0.15–0.85 along it (off segment ends and junctions, never off the line; schedule.md §2.3 `jitter`). The rng is the
     * member's own (`mix64(seed ⊕ fnv1a64(ref))`), so a member's point never depends on the other members.
     */
    private fun pointPick(ref: String, goal: Goal, seed: ULong): Pick? {
        if (ref.startsWith("place:")) return placePick(ref)
        val r = Ref.parse(ref) as? FeatureRef ?: return null
        val s = segs(ref, goal)
        if (s.isEmpty()) return null
        val rng = SplitMix64(SplitMix64.mix64(seed xor Fnv1a.hash64(ref)))
        val total = s.sumOf { it.lengthM }
        var x = rng.nextDouble() * total
        var seg = s.last()
        for (c in s) {
            if (x < c.lengthM) {
                seg = c
                break
            }
            x -= c.lengthM
        }
        return Pick(ref, featureIdOf(r), listOf(seg.id), jitter(seg, rng))
    }

    private fun jitter(seg: SegRef, rng: SplitMix64): GeoPoint = round(pointAt(seg.line, 0.15 + 0.7 * rng.nextDouble()))

    private fun pointAt(line: List<GeoPoint>, f: Double): GeoPoint {
        val target = GeoMath.lineLengthM(line) * f
        var acc = 0.0
        for (i in 0 until line.size - 1) {
            val d = GeoMath.distanceM(line[i], line[i + 1])
            if (acc + d >= target && d > 0) {
                val t = (target - acc) / d
                return GeoPoint(line[i].lat + t * (line[i + 1].lat - line[i].lat), line[i].lon + t * (line[i + 1].lon - line[i].lon))
            }
            acc += d
        }
        return line.last()
    }

    /** Micro-degrees (≈ 0.1 m): stable text in goldens and claims on every platform. */
    private fun round(p: GeoPoint) = GeoPoint((p.lat * 1e6).roundToLong() / 1e6, (p.lon * 1e6).roundToLong() / 1e6)

    // --- the weekly treasure set ------------------------------------------------------------------------------------

    /** Start dates of an item's occurrences from its chain start, extended on demand (the cooldown replay). */
    private class Chain(val first: LocalDate?, val starts: MutableList<LocalDate>, var scannedTo: LocalDate?)

    private fun chain(item: Item, rec: Recurrence, upTo: LocalDate): Chain {
        val c = chains.getOrPut(item.id) {
            val anchor = item.notBefore ?: config.epoch
            var first: LocalDate? = null
            if (anchor != null) {
                val span = Recurrences.spanDays(rec)
                var d = anchor.minus(span, DateTimeUnit.DAY)
                repeat(CHAIN_SEARCH_DAYS) {
                    if (first == null && Recurrences.startsOn(config, rec, d) && d.plus(span, DateTimeUnit.DAY) >= anchor) first = d
                    d = d.plus(1, DateTimeUnit.DAY)
                }
            }
            Chain(first, ArrayList(), null)
        }
        val first = c.first ?: return c
        var d = c.scannedTo?.plus(1, DateTimeUnit.DAY) ?: first
        while (d <= upTo) {
            if (Recurrences.startsOn(config, rec, d) && (item.expires == null || d <= item.expires)) c.starts += d
            c.scannedTo = d
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return c
    }

    /**
     * The weekly treasure set of the occurrence starting [occDate] (product §4.1, schedule.md §3.4 #5): `pick`
     * Play-safe features, excluding those the previous `spotCooldown` occurrences used (replayed from the item's first
     * occurrence at or after its `notBefore` / the pack epoch), spread per `spread` (one per chart square, `minApartM`
     * apart, one per island quadrant first, then the low-traffic share from the baked aggregates, then the rest).
     */
    private fun treasureDraw(item: Item, goal: Goal, pl: Placement, occDate: LocalDate): List<Pick> {
        val memoKey = "${item.id}|$goal|$occDate"
        draws[memoKey]?.let { return it }
        val rec = recs[item.id]
        val k = item.spotCooldown
        if (k <= 0 || rec == null) return drawSet(item, goal, pl, occDate, emptySet()).also { draws[memoKey] = it }
        val c = chain(item, rec, occDate)
        val idx = c.starts.indexOf(occDate)
        if (idx < 0) return drawSet(item, goal, pl, occDate, emptySet()).also { draws[memoKey] = it }
        for (j in 0..idx) {
            val d = c.starts[j]
            val key = "${item.id}|$goal|$d"
            if (key in draws) continue
            val excluded = HashSet<String>()
            for (p in maxOf(0, j - k) until j) draws["${item.id}|$goal|${c.starts[p]}"]?.forEach { excluded += it.ref }
            draws[key] = drawSet(item, goal, pl, d, excluded)
        }
        return draws.getValue(memoKey)
    }

    private fun drawSet(item: Item, goal: Goal, pl: Placement, occDate: LocalDate, excluded: Set<String>): List<Pick> {
        val want = pl.pick ?: item.count
        val seed = occSeed(item.id, occDate)
        val pool = members(pl.from!!, goal)
        val order = SplitMix64(seed).pick(pool.size, pool.filter { it !in excluded }) { it }
        val cands = order.mapNotNull { pointPick(it, goal, seed) }
        val spread = item.spread
        val maxPerSquare = spread?.maxPerSquare ?: Int.MAX_VALUE
        val minApart = spread?.minApartM?.toDouble() ?: 0.0
        val chosen = ArrayList<Pick>(want)
        val perSquare = HashMap<String, Int>()
        val used = HashSet<String>()
        fun fits(p: Pick): Boolean {
            val pt = p.point ?: return false
            if (p.ref in used) return false
            if ((perSquare[ChartSquare.of(pt).id] ?: 0) >= maxPerSquare) return false
            return chosen.all { GeoMath.distanceM(it.point!!, pt) >= minApart }
        }
        fun take(p: Pick) {
            chosen += p
            used += p.ref
            val sq = ChartSquare.of(p.point!!).id
            perSquare[sq] = (perSquare[sq] ?: 0) + 1
        }
        // 1. one per island quadrant (about the pool's centre)
        val quadrants = (spread?.quadrants ?: 0).coerceAtMost(4)
        val centre = centre(pl.from, goal)
        if (quadrants > 0 && centre != null) {
            for (q in 0 until quadrants) {
                if (chosen.size >= want) break
                cands.firstOrNull { quadrant(it.point!!, centre) == q && fits(it) }?.let(::take)
            }
        }
        // 2. the low-traffic share, from the baked aggregates (none until Play has players)
        val low = (if (goal == Goal.trails) config.aggregates.lowTrafficSquares.trails else config.aggregates.lowTrafficSquares.roads).toSet()
        val share = spread?.lowTrafficShare ?: 0.0
        if (low.isNotEmpty() && share > 0) {
            val quota = ceil(share * want).toInt()
            fun inLow(p: Pick) = ChartSquare.of(p.point!!).id in low
            for (p in cands) {
                if (chosen.size >= want || chosen.count(::inLow) >= quota) break
                if (inLow(p) && fits(p)) take(p)
            }
        }
        // 3. the rest, in draw order
        for (p in cands) {
            if (chosen.size >= want) break
            if (fits(p)) take(p)
        }
        return chosen
    }

    /** 0 NE, 1 NW, 2 SE, 3 SW of [c]. */
    private fun quadrant(p: GeoPoint, c: GeoPoint): Int = (if (p.lat >= c.lat) 0 else 2) + (if (p.lon >= c.lon) 0 else 1)

    /** Mean of the pool members' half-length points (all members, before cooldown, so the quadrants never move). */
    private fun centre(poolRef: String, goal: Goal): GeoPoint? = centres.getOrPut("$goal|$poolRef") {
        val pts = members(poolRef, goal).mapNotNull { whole(it, goal)?.point }
        if (pts.isEmpty()) null else GeoPoint(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
    }

    /**
     * `pool:play-safe-<goal>`: from the pack build's derived table when given; else computed from [places] with the
     * same rules (counted, `roadSafety` for roads, not touching a quiet zone, not in an excluded park).
     */
    private fun playSafe(goal: Goal): List<String> = playSafe.getOrPut(goal) {
        derived[goal]?.playSafe?.let { return@getOrPut it.sorted() }
        val zones = QuietZones(QuietZones.build(config.quietZones, config.places, places).zones)
        places.features(goal).filter { f ->
            val ref: FeatureRef = if (goal == Goal.trails) TrailRef(f.id) else RoadRef(f.id)
            val s = places.segments(ref)
            f.counted && s.isNotEmpty() && (goal == Goal.trails || config.rules.roadSafety.passes(f)) &&
                (f.park == null || f.park !in config.excludedParks) && zones.touching(s) == null
        }.map { it.id }.sorted()
    }

    companion object {
        private const val PLAN_CACHE = 48
        private const val CHAIN_SEARCH_DAYS = 400

        /**
         * schedule.md §2.2: `mix64(islandSeed ⊕ fnv1a64(itemId) ⊕ (occDay · 0x9E3779B97F4A7C15))`, occDay = the
         * occurrence's first island date as an epoch day.
         */
        fun occSeed(islandSeed: ULong, itemId: String, occDate: LocalDate): ULong =
            SplitMix64.mix64(islandSeed xor Fnv1a.hash64(itemId) xor (occDate.toEpochDays().toULong() * SplitMix64.GAMMA))
    }

    private fun occSeed(itemId: String, occDate: LocalDate): ULong = occSeed(islandSeed, itemId, occDate)
}
