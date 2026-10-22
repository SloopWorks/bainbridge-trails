package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.ChallengeMetric
import kotlinx.datetime.LocalDate
import kotlin.math.floor
import kotlin.math.min

/**
 * The trails dataset tables the six trails rules read (plan D6; built from the derived Play JSON + the counted
 * dataset by the app, `null` on roads). [segmentLengthM]: counted (included) segments only; [trailSegments]: each
 * counted feature's segments; [quietSegments]: the derived JSON's `quietSegments` (segments of a quiet feature **or**
 * touching a quiet zone); [quietFeatures]: quiet trails (never earn); [featurePark] / [featureGainM]: the derived
 * `featurePark` / `featureGainM` (park membership = the counted features listed here); [parks]: the Play parks (the
 * derived `parks`, excluded parks such as Pritchard Park already removed).
 */
data class TrailTables(
    val segmentLengthM: Map<String, Double>,
    val trailSegments: Map<String, List<String>>,
    val quietSegments: Set<String>,
    val quietFeatures: Set<String>,
    val featurePark: Map<String, String>,
    val featureGainM: Map<String, Double>,
    val parks: Set<String>,
) {
    /** Gain in integer decimetres (`round(gain_m × 10)`, half up as the Worker's `Math.round`): no float compare. */
    val gainDm: Map<String, Int> by lazy { featureGainM.mapValues { (_, m) -> floor(m * 10.0 + 0.5).toInt() } }

    /** Play park → its non-quiet member trails. */
    val parkMembers: Map<String, List<String>> by lazy {
        featurePark.entries.filter { (t, p) -> p in parks && t !in quietFeatures }.groupBy({ it.value }, { it.key })
    }
}

/**
 * What the trails rules read of a coverage snapshot (plan D6): [nonQuietCoveredM] Σ min(covered, length) over counted
 * segments outside [TrailTables.quietSegments]; [touchedTrails] (sorted) the trails with such a covered segment;
 * [touchedParks] (sorted) their Play parks; [completed] the sticky completed set; [islandWhole] W-ISLAND reached.
 */
data class TrailRewardInput(
    val nonQuietCoveredM: Double,
    val touchedTrails: List<String>,
    val touchedParks: List<String>,
    val completed: Set<String>,
    val islandWhole: Boolean,
)

/**
 * A climb / parks challenge's progress P over one window (plan D5): [climbDm] Σ gain (dm) of the distinct non-quiet
 * features completed inside it, [parks] their distinct Play parks.
 */
data class TrailProgress(val climbDm: Int, val parks: Set<String>) {
    /** P in the challenge's compare unit. */
    fun value(metric: ChallengeMetric): Int = when (metric) {
        ChallengeMetric.climb -> climbDm
        ChallengeMetric.parks -> parks.size
        else -> 0
    }

    companion object {
        val NONE = TrailProgress(0, emptySet())

        /** P of the features [completedInWindow] (any order, duplicates count once). */
        fun of(completedInWindow: Collection<String>, tables: TrailTables): TrailProgress {
            val done = completedInWindow.toSet().filter { it !in tables.quietFeatures }
            return TrailProgress(
                climbDm = done.sumOf { tables.gainDm[it] ?: 0 },
                parks = done.mapNotNullTo(HashSet()) { t -> tables.featurePark[t]?.takeIf { it in tables.parks } },
            )
        }
    }
}

/**
 * The six trails rules (brief § 2: W-CLIMB, W-STEEP, W-MILE, W-PARK1, W-PARKC, W-ISLAND; goals `[trails]` only),
 * pure and deterministic; mirrored by the Worker's `src/play/trails.ts` and pinned for both by the shared fixture
 * `data/play/golden/trails-rewards.json`. Every rule pays by **key**, by value, never re-derived (plan D1):
 * `climb:<m>`, `mile:<mi>`, `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`. A key is paid once ever;
 * owed = [qualifying] − [paid], so a dataset that drops a trail lowers nothing and one that adds a trail pays only
 * keys not yet paid. Keys are unit-free (metres and statute miles), so a unit switch never pays twice.
 */
object TrailRewards {
    /** W-CLIMB steps, metres (brief Q1: the island's non-quiet climb is 1,368.8 m, so no 1,500). */
    val CLIMB_STEPS_M: List<Int> = listOf(100, 250, 500, 750, 1_000, 1_250)

    /** W-STEEP: a completed non-quiet trail with gain ≥ 40.0 m. */
    const val STEEP_DM = 400

    /** W-MILE: one statute mile (plan D6: AC 2's "1,609 m" is 1,609.344 m). */
    const val METRES_PER_MILE = 1_609.344
    private const val EPS = 1e-9

    const val STEP_MARKS = 50
    const val PARK_COMPLETE_MARKS = 150
    const val ISLAND_MARKS = 1_000
    const val ISLAND = "island"

    /** The Logbook kinds whose `contentId` is a trails key (plan D1: part of [paid]). */
    val KINDS: Set<LogKind> =
        setOf(LogKind.climb, LogKind.high_ground, LogKind.milestone, LogKind.park_first, LogKind.park_complete, LogKind.island)

    /** Same-walk row order (ux § 3: high ground, park first, park complete, climb, milestone, island). */
    private val ROW_ORDER = listOf(LogKind.high_ground, LogKind.park_first, LogKind.park_complete, LogKind.climb, LogKind.milestone, LogKind.island)

    /** The W-MILE ladder up to [miles]: 1, then every 5 (the `Milestones` distance ladder, RP-8). */
    fun mileSteps(miles: Double): List<Int> {
        val out = ArrayList<Int>()
        var step = 1
        while (step <= miles + EPS) {
            out += step
            step = if (step == 1) 5 else step + 5
        }
        return out
    }

    /**
     * The rules' inputs from raw [coverage] (`segmentId → coveredM`, summed in its iteration order), the sticky
     * [completed] set and [islandWhole] (plan D6). Segments not counted, or in the derived quiet set, add nothing.
     */
    fun inputs(coverage: Map<String, Double>, completed: Collection<String>, islandWhole: Boolean, tables: TrailTables): TrailRewardInput {
        var metres = 0.0
        val covered = HashSet<String>()
        for ((id, m) in coverage) {
            val len = tables.segmentLengthM[id] ?: continue
            if (id in tables.quietSegments) continue
            val c = min(m, len)
            if (c <= 0.0) continue
            metres += c
            covered += id
        }
        val touched = tables.trailSegments.filter { (_, segs) -> segs.any { it in covered } }.keys.sorted()
        val parks = touched.mapNotNull { t -> tables.featurePark[t]?.takeIf { it in tables.parks } }.distinct().sorted()
        return TrailRewardInput(metres, touched, parks, completed.toSet(), islandWhole)
    }

    /** Every key [input] qualifies for (sorted). Completed ids the tables do not know, and quiet trails, earn nothing. */
    fun qualifying(input: TrailRewardInput, tables: TrailTables): Set<String> {
        val keys = HashSet<String>()
        val done = input.completed.filter { it in tables.gainDm && it !in tables.quietFeatures }
        val climbDm = done.sumOf { tables.gainDm.getValue(it) }
        for (m in CLIMB_STEPS_M) if (climbDm >= m * 10) keys += "climb:$m"
        for (t in done) if (tables.gainDm.getValue(t) >= STEEP_DM) keys += "high:$t"
        for (mi in mileSteps(input.nonQuietCoveredM / METRES_PER_MILE)) keys += "mile:$mi"
        for (p in input.touchedParks) keys += "park1:$p"
        val doneSet = done.toSet()
        for ((p, members) in tables.parkMembers) if (members.isNotEmpty() && members.all { it in doneSet }) keys += "parkc:$p"
        if (input.islandWhole) keys += ISLAND
        return keys.sorted().toSet()
    }

    /** Paid keys (plan D1): the device ledger [trailPaid] ∪ the `contentId`s of the Logbook's trails-kind entries. */
    fun paid(trailPaid: Set<String>, logbook: Logbook): Set<String> =
        trailPaid + logbook.entries.mapNotNull { e -> e.contentId?.takeIf { e.kind in KINDS } }

    /** The keys of [qualifying] not yet [paid], sorted. */
    fun owed(qualifying: Set<String>, paid: Set<String>): List<String> = qualifying.filter { it !in paid }.sorted()

    /** The Logbook kind of [key], or null for a key that is not a trails key. */
    fun kindOf(key: String): LogKind? = when (key.substringBefore(':')) {
        "climb" -> LogKind.climb
        "high" -> LogKind.high_ground
        "mile" -> LogKind.milestone
        "park1" -> LogKind.park_first
        "parkc" -> LogKind.park_complete
        ISLAND -> if (key == ISLAND) LogKind.island else null
        else -> null
    }

    /** Marks of [key]: +50 per step / trail / first park, +150 a finished park, +1,000 the island; 0 for a non-key. */
    fun marksOf(key: String): Int = when (kindOf(key)) {
        LogKind.park_complete -> PARK_COMPLETE_MARKS
        LogKind.island -> ISLAND_MARKS
        null -> 0
        else -> STEP_MARKS
    }

    /**
     * One Logbook row per key of [newKeys] (ux § 2.2), in results order, `contentId` = the key (the Logbook dedupes on
     * it and [paid] reads it back). [names]: a trail id's display name (falls back to the id). The persisted what of
     * climb / milestone is the step in its stored unit; parks and the high-ground trail name their subject.
     */
    fun entries(newKeys: Collection<String>, names: (String) -> String?, date: LocalDate, walkId: String?, newId: () -> String): List<LogEntry> =
        newKeys.distinct().mapNotNull { key -> kindOf(key)?.let { key to it } }
            .sortedWith(compareBy({ ROW_ORDER.indexOf(it.second) }, { stepOf(it.first) ?: 0 }, { it.first }))
            .map { (key, kind) ->
                val subject = key.substringAfter(':')
                val what = when (kind) {
                    LogKind.climb -> LogCopy.climbWhat(stepOf(key) ?: 0)
                    LogKind.milestone -> LogCopy.mileWhat(stepOf(key) ?: 0)
                    LogKind.high_ground -> names(subject) ?: subject
                    LogKind.island -> LogCopy.what(LogKind.island)
                    else -> subject
                }
                LogEntry(newId(), kind, what, LogCopy.why(kind), date, marksOf(key), walkId = walkId, contentId = key)
            }

    private fun stepOf(key: String): Int? = key.substringAfter(':', "").toIntOrNull()

    // --- climb / parks challenges (plan D5) ---------------------------------------------------------------------

    /** A climb / parks pack target in the compare unit: climb metres → decimetres. */
    fun compareTarget(metric: ChallengeMetric, packTarget: Int): Int = if (metric == ChallengeMetric.climb) packTarget * 10 else packTarget

    /**
     * The personal target (brief Q3, plan D5): `min(default, R)` with R = remaining(completed) + P, [default] in the
     * compare unit ([compareTarget]). climb: remaining = Σ dm of non-quiet trails ∉ [completed]; parks: remaining =
     * |{Play parks with a non-quiet trail ∉ [completed]} ∪ P's parks|. Every completion inside the window moves value
     * from remaining to P, so R is "what remained at the window start". 0 → no card, no claim.
     */
    fun challengeTarget(metric: ChallengeMetric, default: Int, completed: Set<String>, progress: TrailProgress, tables: TrailTables): Int =
        when (metric) {
            ChallengeMetric.climb -> {
                val remaining = tables.gainDm.entries.filter { (t, _) -> t !in tables.quietFeatures && t !in completed }.sumOf { it.value }
                min(default, remaining + progress.climbDm)
            }
            ChallengeMetric.parks -> {
                val remaining = HashSet(progress.parks)
                for ((p, members) in tables.parkMembers) if (members.any { it !in completed }) remaining += p
                min(default, remaining.size)
            }
            else -> default
        }

    /** The claim rule (plan D5, review P2-6): P ≥ target > 0. The caller adds "this occurrence is unclaimed". */
    fun claims(target: Int, progress: Int): Boolean = target > 0 && progress >= target
}
