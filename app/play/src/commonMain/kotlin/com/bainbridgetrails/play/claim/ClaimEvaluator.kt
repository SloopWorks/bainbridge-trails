@file:Suppress("EnumEntryName", "EnumNaming") // ClaimKind constants are the wire `kind` values (product §3.10)

package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.ChallengeScope
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.ResolveBy
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.geo.QuietZones
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.schedule.DayPlan
import com.bainbridgetrails.play.schedule.FeatureRef
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.PlaceRef
import com.bainbridgetrails.play.schedule.PoolRef
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.SegRef
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * A location fix as Play sees it: epoch [timeMs], WGS84, horizontal [accuracyM] (null = unknown, which never senses).
 * At Finish these are the Finalizer's kept fixes (coverage `keptFixes`: no mock, inaccurate, spike or too-fast fix);
 * live they are the recording's raw fixes. Never stored by Play.
 */
data class GatedFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val isMock: Boolean = false,
) {
    val point: GeoPoint get() = GeoPoint(lat = lat, lon = lon)
    val instant: Instant get() = Instant.fromEpochMilliseconds(timeMs)
}

/** What Play reads of the Finalizer's walk summary (`SessionSummary`; :play does not depend on :tracking). */
data class GatedSummary(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceM: Double,
    val matchedM: Double,
    /** Counted features this walk completed first (TL-7). */
    val newlyCompleted: List<String>,
    val verified: Boolean,
    /** Fixes the Finalizer's vehicle gate dropped: the reason for items sensed live but gone at Finish. */
    val droppedVehicle: Int,
)

/** A claim's wire `kind` (product §3.10). */
enum class ClaimKind {
    treasure, egg, riddle, ink, lore, challenge;

    val logKind: LogKind get() = LogKind.valueOf(name)
}

/**
 * A claimed (or, from [SensingTracker], sensed) item. [key] is the claim id (occurrence key, `#n` for set members);
 * [weekId] the week of [at]; [marks] the local display value (the server derives its own); [band] the F-INK bucket.
 * [at] is in memory only: the Logbook keeps its island date.
 */
data class Claim(
    val key: String,
    val itemId: String,
    val kind: ClaimKind,
    val weekId: LocalDate,
    val marks: Int,
    val band: Int? = null,
    val at: Instant,
)

/** An item the walk reached that could not be claimed, listed once in results (PF6-2). */
data class Unclaimed(val key: String, val itemId: String, val reason: UnclaimedReason)

/** A walk through a quiet zone: a Logbook stamp with no number (product §4.6.3, PF10-2). */
data class QuietVisit(val zoneId: String)

/**
 * One finished walk for solo challenge windows: [walkId] (the session in memory, opaque on disk like Logbook walk ids),
 * when it ended ([endedAt], epoch ms; the app keeps it in the no-backup dir only, pruned after [KEEP_MS]) and how many
 * counted, non-quiet features it completed first.
 */
@Serializable
data class WalkDone(val walkId: String, val endedAt: Long, val completed: Int) {
    companion object {
        /** Longer than any challenge window (a week) plus its late days. */
        const val KEEP_MS = 15L * 24 * 60 * 60 * 1000
    }
}

/**
 * The claim pass of one walk. [stretchShares]: the walked share (0..1) of every kraken stretch the walk touched,
 * by placement key, for the attack sheet ("50 % walked — 60 % repels it", PF7-2).
 */
data class ClaimResult(
    val claims: List<Claim>,
    val unclaimed: List<Unclaimed>,
    val visits: List<QuietVisit>,
    val stretchShares: Map<String, Double> = emptyMap(),
)

/**
 * Sensed → claimed (product §3.6, §4; schedule.md §4.6; PD-12). Pure and deterministic over (pack, places, fixes, day
 * plans): the same walk always gives the same claims. A fix senses an item when (§3.6.1):
 * 1. its accuracy is known and ≤ the item's limit (treasure / egg: the radius; lore and riddle places: 30 m;
 *    walk-through items: 35 m, the coverage filter's),
 * 2. it lies within the item's radius, or (walk-through) the walk's fixes cover the required share / metres of the
 *    stretch or feature (repeat metres count, within [LINE_BUFFER_M] of the line),
 * 3. the speed over the trailing 60 s is ≤ 4.0 m/s and it is not mock,
 * 4. the occurrence is active then: in its window, in daylight ([Occurrence.daySpans]: civil dawn → civil dusk ∩ play
 *    hours) and, for the kraken, at a low enough tide ([Occurrence.resolvable]); and the fix is outside every quiet zone.
 * Quiet-zone fixes never sense anything; they leave a [QuietVisit]. Riddles claim by reaching the answer, solved or
 * not (PF8-4). Challenges are counted by [challenges]. Killed or disabled items never claim (PF16-1).
 *
 * Not thread-safe (caches): use one instance per dispatcher.
 */
class ClaimEvaluator(val config: PlayConfig, private val places: PlaceIndex) {
    private val items: Map<String, Item> = config.items.associateBy { it.id }
    private val killedItems = config.kill.items.toSet()
    private val killedTypes = config.kill.types.toSet()
    private val zones: QuietZones by lazy { QuietZones(QuietZones.build(config.quietZones, config.places, places).zones) }
    private val segCache = HashMap<String, List<SegRef>>()

    /**
     * The claim pass at Finish over the walk's [kept] fixes and the [plans] of every island date the walk touched
     * ([islandDates]). [found]: keys already claimed (never claimed or listed again: idempotent per walk and content);
     * [hints]: hints used per riddle item; [sensed]: keys [SensingTracker] sensed live, listed as unclaimed when the
     * kept fixes no longer reach them ([droppedVehicle] > 0 → vehicle, else accuracy). With [walkEnd], the solo
     * `completed` challenges this walk reaches ([challenges]: [completedThisWalk] over the [earlier] walks) join the
     * claims, sorted by key.
     */
    fun evaluate(
        kept: List<GatedFix>,
        plans: List<DayPlan>,
        found: Set<String> = emptySet(),
        hints: Map<String, Int> = emptyMap(),
        sensed: Set<String> = emptySet(),
        droppedVehicle: Int = 0,
        walkEnd: Instant? = null,
        completedThisWalk: Int = 0,
        earlier: List<WalkDone> = emptyList(),
    ): ClaimResult {
        val walk = Walk(found, hints)
        plans.forEach(walk::addPlan)
        for (f in kept.sortedBy { it.timeMs }) walk.add(f)
        val result = walk.result(sensed, droppedVehicle)
        if (walkEnd == null) return result
        val known = result.claims.mapTo(HashSet()) { it.key }
        val challenges = challenges(plans, walkEnd, completedThisWalk, earlier, found).filter { it.key !in known }
        return if (challenges.isEmpty()) result else result.copy(claims = (result.claims + challenges).sortedBy { it.key })
    }

    /**
     * Solo `completed` challenges (`ch-sweep`, product §4.5, schedule.md §7.3) claimed by the walk that finished at
     * [walkEnd] having completed [completedThisWalk] counted, non-quiet features first: a walk counts when it finished
     * inside the window (half-open, island time), and the walk whose completions reach the goal's target claims it.
     * [earlier]: the earlier walks (any window; only those finished inside this one count).
     */
    internal fun challenges(
        plans: List<DayPlan>,
        walkEnd: Instant,
        completedThisWalk: Int,
        earlier: List<WalkDone>,
        found: Set<String> = emptySet(),
    ): List<Claim> {
        if (completedThisWalk <= 0) return emptyList()
        val out = ArrayList<Claim>()
        val seen = HashSet<String>()
        for (plan in plans) for (o in plan.occurrences) {
            if (o.type != ItemType.challenge || !seen.add(o.key) || o.key in found || !o.activeAt(walkEnd)) continue
            val item = live(o.itemId) ?: continue
            val spec = item.challenge ?: continue
            if (spec.scope != ChallengeScope.solo || spec.metric != ChallengeMetric.completed) continue
            val target = spec.target?.get(plan.goal) ?: continue
            val prior = earlier.map { Instant.fromEpochMilliseconds(it.endedAt) to it.completed }
                .filter { (end, _) -> end < walkEnd && o.activeAt(end) }.sumOf { it.second }
            if (prior < target && prior + completedThisWalk >= target) {
                out += Claim(o.key, item.id, ClaimKind.challenge, IslandTime.weekId(walkEnd), Marks.found(item), null, walkEnd)
            }
        }
        return out.sortedBy { it.key }
    }

    private fun live(itemId: String): Item? =
        items[itemId]?.takeIf { it.enabled && !config.kill.all && it.id !in killedItems && it.type !in killedTypes }

    // --- one walk ---------------------------------------------------------------------------------------------------

    /** An occurrence merged across the day plans of a walk (each plan holds that day's spans). */
    private class Occ(val occ: Occurrence) {
        val daySpans = ArrayList(occ.daySpans)
        val resolvable = ArrayList(occ.resolvable)

        fun merge(o: Occurrence) {
            o.daySpans.forEach { if (it !in daySpans) daySpans += it }
            o.resolvable.forEach { if (it !in resolvable) resolvable += it }
        }

        /** The first failing rule-4 check at [t], or null. */
        fun gate(t: Instant): UnclaimedReason? = when {
            !occ.activeAt(t) -> UnclaimedReason.WINDOW
            daySpans.none { t in it } -> UnclaimedReason.DARK
            resolvable.none { t in it } -> UnclaimedReason.TIDE
            else -> null
        }
    }

    /**
     * The incremental state of one walk: day plans add targets, fixes (in time order) advance them. [add] returns the
     * claims a fix newly satisfied (live sensing); [result] is the claim pass.
     */
    internal inner class Walk(private val found: Set<String>, private val hints: Map<String, Int>) {
        private val occs = HashMap<String, Occ>()
        private val targets = LinkedHashMap<String, Target>()
        private val visits = LinkedHashSet<String>()
        private val history = ArrayList<GatedFix>()
        private var ref = 0

        fun addPlan(plan: DayPlan) {
            for (o in plan.occurrences) {
                occs[o.key]?.let { it.merge(o); continue }
                val m = Occ(o)
                occs[o.key] = m
                if (!o.sensable) continue
                val item = live(o.itemId) ?: continue
                for (t in targetsFor(item, m)) if (t.key !in found && t.key !in targets) targets[t.key] = t
            }
        }

        fun add(f: GatedFix): List<Claim> {
            if (f.isMock) return emptyList()
            if (history.isNotEmpty() && f.timeMs <= history.last().timeMs) return emptyList()
            val speedOk = speedOk(f)
            history += f
            val p = f.point
            val zone = zones.zoneAt(p)
            if (zone != null) {
                visits += zone.id
                targets.values.forEach { it.interrupt() }
                return emptyList()
            }
            val t = f.instant
            val out = ArrayList<Claim>(0)
            for (tg in targets.values) tg.offer(f, p, t, speedOk)?.let { out += it }
            return out
        }

        /** Rule 3: speed from the latest fix ≥ 60 s earlier (else the first, once ≥ 5 s have passed) ≤ 4.0 m/s. */
        private fun speedOk(f: GatedFix): Boolean {
            if (history.isEmpty()) return false
            while (ref + 1 < history.size && f.timeMs - history[ref + 1].timeMs >= SPEED_WINDOW_MS) ref++
            val r = history[ref]
            val dt = f.timeMs - r.timeMs
            if (dt < MIN_SPEED_BASIS_MS) return false
            return GeoMath.distanceM(r.point, f.point) / (dt / 1000.0) <= MAX_SPEED_MPS
        }

        fun result(sensed: Set<String>, droppedVehicle: Int): ClaimResult {
            val claims = targets.values.mapNotNull { it.claim() }.sortedBy { it.key }
            val claimed = claims.mapTo(HashSet()) { it.key }
            val unclaimed = LinkedHashMap<String, Unclaimed>()
            for (t in targets.values) {
                if (t.key in claimed) continue
                t.reason()?.let { unclaimed[t.key] = Unclaimed(t.key, t.item.id, it) }
            }
            for (k in sensed) {
                val t = targets[k] ?: continue
                if (k in claimed || k in unclaimed) continue
                unclaimed[k] = Unclaimed(k, t.item.id, if (droppedVehicle > 0) UnclaimedReason.VEHICLE else UnclaimedReason.ACCURACY)
            }
            val shares = targets.values.filterIsInstance<LineTarget>().filter { it.kind == ClaimKind.ink && it.touched }
                .associate { it.key to it.okShare }
            return ClaimResult(claims, unclaimed.values.sortedBy { it.key }, visits.map { QuietVisit(it) }, shares)
        }

        private fun targetsFor(item: Item, o: Occ): List<Target> {
            val occ = o.occ
            return when (item.type) {
                ItemType.treasure, ItemType.egg, ItemType.lore, ItemType.creature -> {
                    if (item.resolve.by != ResolveBy.proximity) return emptyList()
                    val radius = (item.resolve.radiusM ?: DEFAULT_RADIUS_M).toDouble()
                    val isLore = item.type == ItemType.lore || item.type == ItemType.creature
                    val acc = if (isLore) min(radius, PLACE_ACCURACY_M) else radius
                    val kind = when (item.type) {
                        ItemType.treasure -> ClaimKind.treasure
                        ItemType.egg -> ClaimKind.egg
                        else -> ClaimKind.lore
                    }
                    occ.placements.mapNotNull { pl ->
                        val pt = pl.point ?: return@mapNotNull null
                        val marks = if (item.type == ItemType.treasure) pl.marks else Marks.found(item)
                        PointTarget(pl.key, item, o, kind, marks, pt, radius, acc)
                    }
                }
                ItemType.riddle -> listOfNotNull(riddleTarget(item, o))
                ItemType.obstacle -> {
                    if (item.resolve.by != ResolveBy.segments) return emptyList()
                    occ.placements.mapNotNull { pl ->
                        val ref = Ref.parse(pl.ref) as? FeatureRef ?: return@mapNotNull null
                        val ids = pl.segmentIds.toSet()
                        val segs = segments(ref).filter { ids.isEmpty() || it.id in ids }
                        if (segs.isEmpty()) return@mapNotNull null
                        val minShare = item.resolve.minShare
                        if (item.bands.isNotEmpty() || minShare != null) {
                            LineTarget(pl.key, item, o, ClaimKind.ink, segs, minShare = minShare ?: 0.0, minM = null)
                        } else {
                            LineTarget(pl.key, item, o, ClaimKind.ink, segs, minShare = null, minM = (item.resolve.minM ?: DEFAULT_MIN_M).toDouble())
                        }
                    }
                }
                else -> emptyList()
            }
        }

        private fun riddleTarget(item: Item, o: Occ): Target? {
            val answer = item.resolve.answer ?: return null
            val marks = Marks.riddle(item.stars ?: 0, hints[item.id] ?: 0)
            return when (val ref = Ref.parse(answer)) {
                is PlaceRef -> {
                    val place = config.places[ref.id] ?: return null
                    val radius = (item.resolve.radiusM ?: PLACE_ACCURACY_M.toInt()).toDouble()
                    PointTarget(o.occ.key, item, o, ClaimKind.riddle, marks, GeoPoint(lat = place.lat, lon = place.lon), radius, min(radius, PLACE_ACCURACY_M))
                }
                is PoolRef, is FeatureRef -> {
                    val members = if (ref is PoolRef) config.pools[ref.id]?.members.orEmpty() else listOf(answer)
                    val segs = members.mapNotNull { Ref.parse(it) as? FeatureRef }.flatMap { segments(it) }.distinctBy { it.id }
                    if (segs.isEmpty()) return null
                    LineTarget(o.occ.key, item, o, ClaimKind.riddle, segs, minShare = null, minM = (item.resolve.minM ?: DEFAULT_MIN_M).toDouble(), marks = marks)
                }
                else -> null
            }
        }
    }

    private fun segments(ref: FeatureRef): List<SegRef> = segCache.getOrPut(ref.raw) {
        places.segments(ref).filter { it.line.size >= 2 && it.lengthM > 0.0 }
    }

    // --- targets ----------------------------------------------------------------------------------------------------

    private abstract class Target(val key: String, val item: Item, val occ: Occ, val kind: ClaimKind) {
        /** Feeds one non-quiet fix; returns the claim when this fix first satisfies the item. */
        abstract fun offer(f: GatedFix, p: GeoPoint, t: Instant, speedOk: Boolean): Claim?

        /** A quiet-zone fix: the walk-through line is broken there. */
        open fun interrupt() {}

        abstract fun claim(): Claim?

        abstract fun reason(): UnclaimedReason?

        fun claimAt(t: Instant, marks: Int, band: Int? = null) = Claim(key, item.id, kind, IslandTime.weekId(t), marks, band, t)
    }

    /** Proximity: one point and radius (treasures, eggs, lore, riddle places). */
    private class PointTarget(
        key: String, item: Item, occ: Occ, kind: ClaimKind,
        private val marks: Int,
        private val point: GeoPoint,
        private val radiusM: Double,
        private val accuracyM: Double,
    ) : Target(key, item, occ, kind) {
        private var claimed: Claim? = null
        private var furthest: UnclaimedReason? = null

        override fun offer(f: GatedFix, p: GeoPoint, t: Instant, speedOk: Boolean): Claim? {
            if (GeoMath.distanceM(p, point) > radiusM) return null
            val acc = f.accuracyM
            val fail = occ.gate(t) ?: when {
                !speedOk -> UnclaimedReason.VEHICLE
                acc == null || acc < 0.0 || acc > accuracyM -> UnclaimedReason.ACCURACY
                else -> null
            }
            if (fail != null) {
                if (furthest == null || fail > furthest!!) furthest = fail
                return null
            }
            if (claimed != null) return null
            return claimAt(t, marks).also { claimed = it }
        }

        override fun claim(): Claim? = claimed

        override fun reason(): UnclaimedReason? = if (claimed == null) furthest else null
    }

    /**
     * Walk-through: the share ([minShare], kraken stretches, banded) or metres ([minM], feature riddles) of [segments]
     * covered by the walk's fixes, each fix joined to the previous one when they are ≤ 60 s and ≤ 100 m apart.
     * [all] counts every non-quiet fix, [ok] only fixes passing rules 1, 3 and 4, so a walk that covered the line while
     * the gates were closed reports why.
     */
    private class LineTarget(
        key: String, item: Item, occ: Occ, kind: ClaimKind,
        segments: List<SegRef>,
        private val minShare: Double?,
        private val minM: Double?,
        private val marks: Int = 0,
    ) : Target(key, item, occ, kind) {
        private val ticks = Ticks(segments)
        private val all = Cover(ticks)
        private val ok = Cover(ticks)
        private var prevAll: GatedFix? = null
        private var prevOk: GatedFix? = null
        private var firstAt: Instant? = null
        private val fails = IntArray(UnclaimedReason.entries.size)
        var touched = false
            private set

        val okShare: Double get() = ok.share

        private fun met(c: Cover): Boolean = when {
            minM != null -> c.walkedM + EPS >= minM
            item.bands.isNotEmpty() -> Marks.inkBand(item.bands, c.share) != null && c.share + EPS >= (minShare ?: 0.0)
            else -> c.share + EPS >= (minShare ?: 1.0)
        }

        override fun offer(f: GatedFix, p: GeoPoint, t: Instant, speedOk: Boolean): Claim? {
            val near = all.mark(prevAll, f)
            prevAll = f
            if (near) touched = true
            val acc = f.accuracyM
            val fail = occ.gate(t) ?: when {
                !speedOk -> UnclaimedReason.VEHICLE
                acc == null || acc < 0.0 || acc > LINE_ACCURACY_M -> UnclaimedReason.ACCURACY
                else -> null
            }
            if (fail != null) {
                prevOk = null
                if (near) fails[fail.ordinal]++
                return null
            }
            ok.mark(prevOk, f)
            prevOk = f
            if (firstAt == null && met(ok)) {
                firstAt = t
                return current(t)
            }
            return null
        }

        override fun interrupt() {
            prevAll = null
            prevOk = null
        }

        private fun current(t: Instant): Claim? = when {
            minM != null -> claimAt(t, marks)
            else -> Marks.inkBand(item.bands, ok.share)?.let { claimAt(t, it.marks, it.pct) }
                ?: claimAt(t, marks)
        }

        override fun claim(): Claim? = firstAt?.let { current(it) }

        override fun reason(): UnclaimedReason? {
            if (firstAt != null || !met(all)) return null
            var best = -1
            for (i in fails.indices) if (fails[i] > 0 && (best < 0 || fails[i] >= fails[best])) best = i
            return if (best < 0) null else UnclaimedReason.entries[best]
        }
    }

    /** Points every ~[TICK_M] along [segments] (local metres), each weighing its share of its segment's length. */
    private class Ticks(segments: List<SegRef>) {
        val x: DoubleArray
        val y: DoubleArray
        val w: DoubleArray
        val totalM: Double
        val minX: Double
        val maxX: Double
        val minY: Double
        val maxY: Double

        init {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val ws = ArrayList<Double>()
            var total = 0.0
            for (s in segments) {
                val len = GeoMath.lineLengthM(s.line)
                if (len <= 0.0) continue
                val n = max(1, (len / TICK_M).roundToInt())
                val step = len / n
                for (k in 0 until n) {
                    val at = pointAt(s.line, (k + 0.5) * step)
                    xs += GeoMath.x(at.lon)
                    ys += GeoMath.y(at.lat)
                    ws += step
                }
                total += len
            }
            x = xs.toDoubleArray()
            y = ys.toDoubleArray()
            w = ws.toDoubleArray()
            totalM = total
            minX = xs.minOrNull() ?: 0.0
            maxX = xs.maxOrNull() ?: 0.0
            minY = ys.minOrNull() ?: 0.0
            maxY = ys.maxOrNull() ?: 0.0
        }

        private fun pointAt(line: List<GeoPoint>, target: Double): GeoPoint {
            var acc = 0.0
            for (i in 0 until line.size - 1) {
                val d = GeoMath.distanceM(line[i], line[i + 1])
                if (acc + d >= target && d > 0) {
                    val t = (target - acc) / d
                    return GeoPoint(lat = line[i].lat + t * (line[i + 1].lat - line[i].lat), lon = line[i].lon + t * (line[i + 1].lon - line[i].lon))
                }
                acc += d
            }
            return line.last()
        }
    }

    /** The ticks of [ticks] a walk covered. */
    private class Cover(private val ticks: Ticks) {
        private val marked = BooleanArray(ticks.x.size)
        var walkedM = 0.0
            private set

        val share: Double get() = if (ticks.totalM <= 0.0) 0.0 else min(1.0, walkedM / ticks.totalM)

        /**
         * Marks the ticks within [LINE_BUFFER_M] of the leg [prev] → [f] (or of [f] alone when there is no joinable
         * previous fix). Returns whether any tick (new or not) was that close.
         */
        fun mark(prev: GatedFix?, f: GatedFix): Boolean {
            val bx = GeoMath.x(f.lon)
            val by = GeoMath.y(f.lat)
            var ax = bx
            var ay = by
            if (prev != null && f.timeMs - prev.timeMs <= JOIN_MS) {
                val px = GeoMath.x(prev.lon)
                val py = GeoMath.y(prev.lat)
                if (hypot(bx - px, by - py) <= JOIN_M) {
                    ax = px
                    ay = py
                }
            }
            val b = LINE_BUFFER_M
            if (max(ax, bx) + b < ticks.minX || min(ax, bx) - b > ticks.maxX || max(ay, by) + b < ticks.minY || min(ay, by) - b > ticks.maxY) {
                return false
            }
            val loX = min(ax, bx) - b
            val hiX = max(ax, bx) + b
            val loY = min(ay, by) - b
            val hiY = max(ay, by) + b
            var near = false
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (i in ticks.x.indices) {
                val tx = ticks.x[i]
                val ty = ticks.y[i]
                if (tx < loX || tx > hiX || ty < loY || ty > hiY) continue
                val u = if (len2 == 0.0) 0.0 else (((tx - ax) * dx + (ty - ay) * dy) / len2).coerceIn(0.0, 1.0)
                if (hypot(tx - (ax + u * dx), ty - (ay + u * dy)) > b) continue
                near = true
                if (!marked[i]) {
                    marked[i] = true
                    walkedM += ticks.w[i]
                }
            }
            return near
        }
    }

    companion object {
        /** Rule 3 (product §3.6.1, A-2): the coverage plausibility speed over the trailing 60 s. */
        const val MAX_SPEED_MPS = 4.0
        const val SPEED_WINDOW_MS = 60_000L

        /** Less history than this: the speed is unknown and nothing senses yet. */
        const val MIN_SPEED_BASIS_MS = 5_000L

        /** Accuracy limit for lore encounters and riddle places (product §3.6.1). */
        const val PLACE_ACCURACY_M = 30.0

        /** Accuracy limit for walk-through items: the coverage fix filter's (`CoverageParams.maxAccuracyM`). */
        const val LINE_ACCURACY_M = 35.0

        /** A walk-through tick counts when the walk's track passes within this distance. */
        const val LINE_BUFFER_M = 25.0
        const val TICK_M = 5.0

        /** Consecutive fixes are joined into a leg like coverage pieces (`maxGapS`, `maxJumpM`). */
        const val JOIN_MS = 60_000L
        const val JOIN_M = 100.0

        const val DEFAULT_RADIUS_M = 20
        const val DEFAULT_MIN_M = 100
        private const val EPS = 1e-9

        /** The island dates a walk from [fromMs] to [toMs] touches: the day plans [evaluate] needs. */
        fun islandDates(fromMs: Long, toMs: Long): List<LocalDate> {
            val first = IslandTime.dateOf(Instant.fromEpochMilliseconds(min(fromMs, toMs)))
            val last = IslandTime.dateOf(Instant.fromEpochMilliseconds(max(fromMs, toMs)))
            val out = ArrayList<LocalDate>()
            var d = first
            while (d <= last) {
                out += d
                d = d.plus(1, DateTimeUnit.DAY)
            }
            return out
        }
    }
}

