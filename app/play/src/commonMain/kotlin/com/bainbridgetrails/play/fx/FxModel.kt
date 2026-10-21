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
    /**
     * A static Layer part (markers, fog, kraken casing / pool, found shells, lit pins): drawn on its rest frame, never
     * animating, never counted toward the animated-AMBIENT cap and never keeping the clock running. Build with [static].
     */
    val isStatic: Boolean = false,
) {
    init {
        require(!isStatic || (priority == FxPriority.AMBIENT && band.isLayerTier && timeline.runLoopMs != null)) {
            "static part $id must be a looping Layer-tier AMBIENT spec"
        }
    }

    companion object {
        /** RUN loops forever on one still frame (rest = 0): lives until dismissed, then leaves at once. */
        val STATIC_TIMELINE: FxTimeline = FxTimeline(spawnMs = 0, runMs = 1, settleMs = 0, despawnMs = 0, runLoopMs = 1, reducedAtMs = 0)

        /** The static/rest spawn path (engine PF-2): a Layer part with no motion of its own. */
        fun static(
            id: String,
            kind: String,
            anchor: FxAnchor,
            zoom: ClosedFloatingPointRange<Double>,
            band: FxBand,
            seed: Long = Fnv1a.hash64(id).toLong(),
        ): FxSpec = FxSpec(id, kind, anchor, zoom, band, FxPriority.AMBIENT, STATIC_TIMELINE, loop = true, seed = seed, isStatic = true)
    }
}

/**
 * The camera as Layer-tier drivers see it (FxHost reads it on camera moves): zoom and the visible lon/lat bounds.
 * [contains] pads the bounds by [PAD] of their size, so a lifted pin just off the bottom edge still counts.
 */
data class FxView(val zoom: Double, val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(p: GeoPoint): Boolean {
        val padLat = (north - south) * PAD
        val w = if (east >= west) east - west else east + 360.0 - west
        val padLon = w * PAD
        if (p.lat < south - padLat || p.lat > north + padLat) return false
        val lon0 = west - padLon
        val lon1 = east + padLon
        return if (east >= west) p.lon in lon0..lon1 else p.lon >= lon0 || p.lon <= lon1
    }

    /** Any part of [anchor] (a vertex, or an area covering the view) is on screen; Screen anchors always are. */
    fun contains(anchor: FxAnchor): Boolean = when (anchor) {
        is FxAnchor.Point -> contains(anchor.at)
        is FxAnchor.Line -> anchor.path.any { contains(it) } || crosses(anchor.path)
        is FxAnchor.Area -> anchor.rings.any { r -> r.any { contains(it) } } || anchor.rings.firstOrNull()?.let { covers(it) } == true
        is FxAnchor.Screen -> true
    }

    private fun crosses(path: List<GeoPoint>): Boolean = path.zipWithNext().any { (a, b) ->
        // A segment passing through without a vertex inside: its bounding box overlaps the view on both axes.
        minOf(a.lat, b.lat) <= north && maxOf(a.lat, b.lat) >= south && minOf(a.lon, b.lon) <= east && maxOf(a.lon, b.lon) >= west
    }

    private fun covers(ring: List<GeoPoint>): Boolean =
        ring.minOf { it.lon } <= west && ring.maxOf { it.lon } >= east && ring.minOf { it.lat } <= south && ring.maxOf { it.lat } >= north

    private companion object {
        const val PAD = 0.1
    }
}
