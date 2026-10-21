package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.time.SunDay
import com.bainbridgetrails.play.time.TideEvent
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** A half-open instant span `[start, end)`. */
data class Span(val start: Instant, val end: Instant) {
    operator fun contains(t: Instant): Boolean = t >= start && t < end
    val isEmpty: Boolean get() = end <= start

    /** The overlap with [other], or null when they do not overlap. */
    fun intersect(other: Span): Span? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (e > s) Span(s, e) else null
    }
}

/**
 * Why an active occurrence can or cannot be sensed and claimed right now (schedule.md §3.4, PD-5, PD-8).
 * [VIEW]: never sensed (cards, challenges, ambient decor).
 */
enum class Gate { OPEN, SLEEPING, TIDE_HIGH, TIDE_UNKNOWN, VIEW }

/**
 * One placement of an occurrence (schedule.md §4.5). [key] is the claim id: the occurrence key, plus `#n` for the
 * members of a weekly treasure set or a kraken attack (`treasure-weekly-roads@2026-10-26#07`, `kraken-roads@2026-10-29#1`).
 * [ref] is what was placed (`place:…`, `trail:…`, `road:…`, `stretch:…`); [featureId] the trail or road it lies on
 * (null for a place); [segmentIds] the segments to walk (whole features and stretches) or the one segment a point was
 * jittered on; [point] the marker; [square] its chart square id.
 */
data class ResolvedPlacement(
    val key: String,
    val index: Int,
    val ref: String,
    val featureId: String?,
    val segmentIds: List<String>,
    val point: GeoPoint?,
    val square: String?,
    val tier: TierName? = null,
    val marks: Int = 0,
)

/**
 * One occurrence on one island day. [start]/[end] are the authored window (the same instants the Worker computes;
 * [end] null = a permanent item); [daySpans] = the window ∩ this day's daylight [civil dawn, civil dusk] ∩
 * `rules.playHours` (empty for items that are never sensed: [sensable] false); [resolvable] = [daySpans] further
 * narrowed by the tide gate for obstacles ([tideGated]). Outside [resolvable] a visible item sleeps.
 * [placements] are what the density caps kept for the day.
 */
data class Occurrence(
    val key: String,
    val itemId: String,
    val type: ItemType,
    val scope: Scope,
    val start: Instant,
    val end: Instant?,
    val hero: Boolean,
    val placements: List<ResolvedPlacement>,
    val marks: Int,
    val variant: Int?,
    val sensable: Boolean,
    val daySpans: List<Span>,
    val resolvable: List<Span>,
    val tideGated: Boolean,
    /** Not drawn on the map until found (eggs): exempt from the density caps. */
    val hidden: Boolean = false,
) {
    fun activeAt(t: Instant): Boolean = t >= start && (end == null || t < end)
}

/**
 * Everything Play shows for one island [date] and [goal] (schedule.md §5.2). [quietDay]: a `quietDays` window covers
 * the date, and only remembrance cards remain. [dropped]: placement keys removed by the density caps for the day.
 */
data class DayPlan(
    val date: LocalDate,
    val goal: Goal,
    val quietDay: Boolean,
    val sun: SunDay,
    val tides: List<TideEvent>?,
    val occurrences: List<Occurrence>,
    val dropped: List<String> = emptyList(),
)

/** In memory only; [currentFeature] (the trail or road being walked) only sorts nearby items first. */
data class PlayContext(val goal: Goal, val recording: Boolean = false, val currentFeature: String? = null)

/** [foundOcc]: claimed occurrence / placement keys. [resolved]: solved stamps. */
data class PlayUserState(
    val resolved: Map<String, Instant> = emptyMap(),
    val foundOcc: Set<String> = emptySet(),
)

/** An occurrence in its window now. [found]: its keys (occurrence or placement) the user already claimed. */
data class ActiveItem(val occurrence: Occurrence, val gate: Gate, val found: Set<String> = emptySet()) {
    val sleeping: Boolean get() = gate == Gate.SLEEPING
}

/** A coming occurrence for What's On (island items only). */
data class UpcomingItem(val key: String, val itemId: String, val type: ItemType, val start: Instant, val end: Instant?)

/** Display-only facts of the day. */
data class DayInfo(
    val date: LocalDate,
    val sun: SunDay,
    val tides: List<TideEvent>?,
    val nextFerryArrival: Instant?,
    val quietDay: Boolean,
)

data class PlayAgenda(
    val active: List<ActiveItem>,
    val upcoming: List<UpcomingItem>,
    val day: DayInfo,
    /** (v2) count of personal surprises in the next days; always 0 in v1. */
    val personalSurprises: Int = 0,
)
