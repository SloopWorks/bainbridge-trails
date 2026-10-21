package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.RoadSafety
import com.bainbridgetrails.play.schedule.FeatureInfo

/** Why a road is not Play-safe; the first failing clause in rule order. */
enum class RoadSafetyFailure(val reason: String) {
    NOT_COUNTED("not a counted road"),
    CATEGORY("category not residential/collector/arterial"),
    NOT_PUBLIC("not public"),
    OWNERSHIP("ownership not public_*"),
    SPEED("speed limit above the maximum"),
    NULL_SPEED("no speed tag, allowed only for residential"),
}

/**
 * The one `roadSafety` rule (PD-7, product §4.0.2, schedule.md §3.4): a counted road whose category is in
 * [RoadSafety.categories] (never `state_route`), `public: true`, `ownership` starting [RoadSafety.ownershipPrefix], and
 * `speed_mph ≤` [RoadSafety.maxSpeedMph], with a null speed allowed only for [RoadSafety.nullSpeedOnly] categories.
 * Null = Play-safe. Computed from the dataset, never hard-coded (370 of 430 counted roads on 2026-10-20).
 */
fun RoadSafety.check(road: FeatureInfo): RoadSafetyFailure? {
    if (!road.counted) return RoadSafetyFailure.NOT_COUNTED
    val category = road.category
    if (category == null || category !in categories) return RoadSafetyFailure.CATEGORY
    if (requirePublic && road.public != true) return RoadSafetyFailure.NOT_PUBLIC
    if (road.ownership?.startsWith(ownershipPrefix) != true) return RoadSafetyFailure.OWNERSHIP
    val speed = road.speedMph
    if (speed == null) return if (category in nullSpeedOnly) null else RoadSafetyFailure.NULL_SPEED
    return if (speed <= maxSpeedMph) null else RoadSafetyFailure.SPEED
}

fun RoadSafety.passes(road: FeatureInfo): Boolean = check(road) == null

/** True when no road passing this rule could fail [floor] ("not tunable below it", schedule.md §4.3). */
fun RoadSafety.isAtLeastAsStrictAs(floor: RoadSafety): Boolean =
    floor.categories.containsAll(categories) &&
        maxSpeedMph <= floor.maxSpeedMph &&
        floor.nullSpeedOnly.containsAll(nullSpeedOnly) &&
        ownershipPrefix.startsWith(floor.ownershipPrefix) &&
        (requirePublic || !floor.requirePublic)
