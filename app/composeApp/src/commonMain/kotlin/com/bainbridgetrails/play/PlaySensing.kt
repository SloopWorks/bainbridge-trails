package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.play.claim.ClaimEvaluator
import com.bainbridgetrails.play.claim.GatedFix
import com.bainbridgetrails.play.claim.GatedSummary
import com.bainbridgetrails.play.claim.SensingTracker
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxTimeline
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.DayPlan
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.ResolvedPlacement
import com.bainbridgetrails.tracking.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/** A live (provisional) find: [key] is the claim id (the placement key, else the occurrence key). */
data class SensedFind(val key: String, val occurrence: Occurrence, val placement: ResolvedPlacement?)

/**
 * The seam between live sensing / Finish and the claim rules (product § 3.6.1–3.6.2). Production uses
 * [TrackerSensingSink] (L1-6's `SensingTracker`); the Finish claim pass itself is `PlayRepository`'s (it needs the
 * scheduler's day plans and the claim queue). [None] senses nothing. Implementations own accuracy, radius, the
 * trailing-60 s speed rule, mock filtering and quiet zones.
 */
interface SensingSink {
    /** One new recording fix; [targets] = what may be sensed now (active, gate OPEN, sensable, not yet found). */
    fun onFix(fix: Fix, targets: List<ActiveItem>, now: Instant): List<SensedFind>

    /** A new recording session started (or the track was reset). */
    fun reset()

    /** The Finalizer's gated track for [sessionId] (Finalizer `onGated`, product § 3.6.2). */
    suspend fun onGated(sessionId: String, kept: List<Fix>, summary: SessionSummary)

    companion object {
        val None: SensingSink = object : SensingSink {
            override fun onFix(fix: Fix, targets: List<ActiveItem>, now: Instant): List<SensedFind> = emptyList()
            override fun reset() = Unit
            override suspend fun onGated(sessionId: String, kept: List<Fix>, summary: SessionSummary) = Unit
        }
    }
}

/**
 * Live sensing over `:play`'s [SensingTracker] (product § 3.6.1, PF5): one tracker per walk, fed every recording fix with
 * the current island day's plan ([plan]); its new claims are reported only when they are among [onFix]'s targets (gate
 * OPEN, sensable, not found, not killed). [evaluator] is the repository's live instance (rebuilt with the pack); a new
 * pack, a new session ([reset]) or Finish ([onGated]) starts a new tracker. Single-threaded use: the sensing collector.
 */
class TrackerSensingSink(
    private val evaluator: () -> ClaimEvaluator?,
    private val plan: () -> DayPlan?,
    private val found: () -> Set<String>,
    private val hints: () -> Map<String, Int>,
    /** [PlayClock.offsetMs]: fixes are sensed at Play time, so a time-travelled walk senses what the map shows. */
    private val offsetMs: () -> Long = { 0L },
) : SensingSink {
    @Volatile private var tracker: SensingTracker? = null
    private var trackerFor: ClaimEvaluator? = null

    override fun onFix(fix: Fix, targets: List<ActiveItem>, now: Instant): List<SensedFind> {
        val ev = evaluator() ?: return emptyList()
        val day = plan() ?: return emptyList()
        val t = tracker?.takeIf { trackerFor === ev } ?: SensingTracker(ev, found(), hints()).also {
            tracker = it
            trackerFor = ev
        }
        val claims = t.onFix(fix.toGated(offsetMs()), day) // fed every fix: the speed rule needs the trailing 60 s
        if (claims.isEmpty() || targets.isEmpty()) return emptyList()
        val byKey = HashMap<String, Pair<ActiveItem, ResolvedPlacement?>>()
        for (a in targets) {
            byKey[a.occurrence.key] = a to null
            for (p in a.occurrence.placements) byKey[p.key] = a to p
        }
        return claims.mapNotNull { c -> byKey[c.key]?.let { (a, p) -> SensedFind(c.key, a.occurrence, p) } }
    }

    override fun reset() {
        tracker = null
    }

    override suspend fun onGated(sessionId: String, kept: List<Fix>, summary: SessionSummary) {
        tracker = null // the walk is over; the Finish pass is the truth (PD-12)
    }
}

/**
 * A recording fix as `:play` sees it (no `:coverage` dependency there), at Play time: [offsetMs] is the clock's
 * [PlayClock.offsetMs] (0 in release, so the time is the fix's own).
 */
fun Fix.toGated(offsetMs: Long = 0L): GatedFix = GatedFix(timeMs + offsetMs, lat, lon, accuracyM, isMock)

/** The Finalizer's walk summary as `:play` sees it, its start and end at Play time (see [Fix.toGated]); unset stays 0. */
fun SessionSummary.toGated(offsetMs: Long = 0L): GatedSummary = GatedSummary(
    sessionId = sessionId,
    startedAtMs = if (startedAt > 0) startedAt + offsetMs else 0L,
    endedAtMs = if (endedAt > 0) endedAt + offsetMs else 0L,
    distanceM = distanceM,
    matchedM = matchedM,
    newlyCompleted = newlyCompleted,
    verified = verified,
    droppedVehicle = droppedVehicle,
)

/**
 * The graph-level live sensing observer (product § 3.6.1, PD-12): follows the recording's fixes
 * (`RecordingController.track`), so it works with the Record screen closed. Created and started only by
 * [PlayRepository] while Play is on; never exists while off (PD-1). Each new fix goes to the [SensingSink] with the
 * current [targets]; every find goes to [onFind] (the repository records it and spawns its find moment).
 */
class PlaySensing internal constructor(
    private val track: Flow<List<Fix>>,
    private val targets: () -> List<ActiveItem>,
    private val sink: SensingSink,
    private val now: () -> Instant,
    private val onFind: (SensedFind) -> Unit,
) {
    init {
        created++
    }

    /** Runs until cancelled. A shrinking track means a new session: the sink is reset. */
    suspend fun run() {
        var seen = 0
        track.collect { fixes ->
            if (fixes.size < seen) {
                sink.reset()
                seen = 0
            }
            if (fixes.size == seen) return@collect
            val t = targets()
            for (i in seen until fixes.size) {
                for (f in sink.onFix(fixes[i], t, now())) onFind(f)
            }
            seen = fixes.size
        }
    }

    companion object {
        /** Instances created in this process (PlayOffNothingCreatedTest: none while Play is off). */
        var created: Int = 0
            private set

        /**
         * The find moment for [find] (effects.md § 3.7 `treasure_found`: OVERLAY, HERO, 900 ms; others a NOTICE
         * `claim_pop`). Placeholder timelines until the effect registry lands (L2-4/L2-5).
         */
        fun findMoment(find: SensedFind): FxSpec? {
            val p = find.placement?.point ?: find.occurrence.placements.firstOrNull()?.point ?: return null
            val treasure = find.occurrence.type == ItemType.treasure
            return FxSpec(
                id = "find:${find.key}",
                kind = if (treasure) "treasure_found" else "claim_pop",
                anchor = FxAnchor.Point(GeoPoint(lon = p.lon, lat = p.lat)),
                zoom = 11.0..22.0,
                band = FxBand.OVERLAY,
                priority = if (treasure) FxPriority.HERO else FxPriority.NOTICE,
                timeline = if (treasure) FxTimeline(100, 500, 300, 0, reducedAtMs = 900) else FxTimeline(80, 320, 200, 0, reducedAtMs = 600),
                loop = false,
            )
        }
    }
}
