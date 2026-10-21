package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.rng.Fnv1a

/** A WGS84 point (degrees). */
data class GeoPoint(val lon: Double, val lat: Double)

/** Where an effect is anchored (engine.md PF-3). */
sealed interface FxAnchor {
    data class Point(val at: GeoPoint) : FxAnchor
    data class Line(val path: List<GeoPoint>) : FxAnchor
    data class Area(val rings: List<List<GeoPoint>>) : FxAnchor
    data class Screen(val xFrac: Float, val yFrac: Float) : FxAnchor
}

/** Z-order bands relative to StatusLayers (engine.md PF-3). Declaration order is draw order. */
enum class FxBand { UNDER_STATUS, CASING, OVER_STATUS, OVER_LABELS, OVERLAY, MOMENT }

/** Arbitration classes; declaration order is rank (AMBIENT < NOTICE < HERO). */
enum class FxPriority { AMBIENT, NOTICE, HERO }

enum class FxStage { SPAWN, RUN, SETTLE, DESPAWN, DONE }

/** Immutable description of one effect instance (engine.md §2.1). */
data class FxSpec(
    /** Occurrence key + part, e.g. "kraken-roads@2026-10-29#0". */
    val id: String,
    /** Renderer key = snake_case effect id (effects.md §3), e.g. "kraken_attack". */
    val kind: String,
    val anchor: FxAnchor,
    val zoom: ClosedFloatingPointRange<Double>,
    val band: FxBand,
    val priority: FxPriority,
    val timeline: FxTimeline,
    val loop: Boolean,
    val cooldownMs: Long = 0,
    /** Per-instance variation, deterministic; never `String.hashCode()` (PD-10). */
    val seed: Long = Fnv1a.hash64(id).toLong(),
    /** String resource key, resolved through GoalVocabulary. */
    val announceKey: String? = null,
)
