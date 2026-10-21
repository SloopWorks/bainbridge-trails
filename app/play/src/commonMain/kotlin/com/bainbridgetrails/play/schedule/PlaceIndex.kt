package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint

/**
 * One dataset segment as Play sees it (schedule.md §5.2): its id, the point at half its length, its length, the road
 * attributes the safety rules read, and its geometry ([line], lon/lat vertices in order) for quiet-zone buffers and
 * chart squares.
 */
data class SegRef(
    val id: String,
    val midLat: Double,
    val midLon: Double,
    val lengthM: Double,
    val speedMph: Int? = null,
    val category: String? = null,
    val line: List<GeoPoint> = emptyList(),
) {
    companion object {
        /** A segment from its geometry: [lengthM] and the half-length point are computed from [line]. */
        fun fromLine(id: String, line: List<GeoPoint>, speedMph: Int? = null, category: String? = null): SegRef {
            val mid = GeoMath.halfLengthPoint(line)
            return SegRef(id, mid.lat, mid.lon, GeoMath.lineLengthM(line), speedMph, category, line)
        }
    }
}

/**
 * A trail (`trails.json`) or road (`roads.json`) row: the attributes the pack rules read. [public] and the road
 * fields are null for trails; [park] is null for roads and for trails outside a park.
 */
data class FeatureInfo(
    val id: String,
    val goal: Goal,
    val counted: Boolean = true,
    val category: String? = null,
    val public: Boolean? = null,
    val ownership: String? = null,
    val speedMph: Int? = null,
    val park: String? = null,
)

/**
 * A pack reference string, parsed (schedule.md §4.5): `place:<id>`, `pool:<id>`, or a [FeatureRef].
 * [raw] is the string as written.
 */
sealed interface Ref {
    val raw: String

    companion object {
        /** Null when [s] is not a well-formed ref. */
        fun parse(s: String): Ref? {
            val colon = s.indexOf(':')
            if (colon <= 0 || colon == s.lastIndex) return null
            val kind = s.substring(0, colon)
            val v = s.substring(colon + 1)
            return when (kind) {
                "place" -> PlaceRef(v)
                "pool" -> PoolRef(v)
                "trail" -> TrailRef(v)
                "road" -> RoadRef(v)
                "park" -> ParkRef(v)
                "trailhead" -> TrailheadRef(v)
                "trailheads" -> if (v == "*") AllTrailheadsRef else null
                "stretch" -> StretchRef(v)
                else -> null
            }
        }
    }
}

data class PlaceRef(val id: String) : Ref { override val raw: String get() = "place:$id" }

data class PoolRef(val id: String) : Ref { override val raw: String get() = "pool:$id" }

/** A dataset feature. [goal] is the dataset it lives in (null when it cannot be told from the ref). */
sealed interface FeatureRef : Ref { val goal: Goal? }

data class TrailRef(val id: String) : FeatureRef {
    override val raw: String get() = "trail:$id"
    override val goal: Goal get() = Goal.trails
}

data class RoadRef(val id: String) : FeatureRef {
    override val raw: String get() = "road:$id"
    override val goal: Goal get() = Goal.roads
}

/** Every trail whose `park` is [name]. */
data class ParkRef(val name: String) : FeatureRef {
    override val raw: String get() = "park:$name"
    override val goal: Goal get() = Goal.trails
}

data class TrailheadRef(val id: String) : FeatureRef {
    override val raw: String get() = "trailhead:$id"
    override val goal: Goal get() = Goal.trails
}

data object AllTrailheadsRef : FeatureRef {
    override val raw: String get() = "trailheads:*"
    override val goal: Goal get() = Goal.trails
}

/** The part of a trail or road within 60 m of the coastline, computed at pack build (≥ 100 m). */
data class StretchRef(val featureId: String) : FeatureRef {
    override val raw: String get() = "stretch:$featureId"
    override val goal: Goal? get() = when {
        featureId.startsWith("trail-") -> Goal.trails
        featureId.startsWith("road-") -> Goal.roads
        else -> null
    }
}

/**
 * Resolves pack references against the island datasets (schedule.md §4.5, §5.2). Implemented in composeApp over
 * `DatasetRepository` (+ `trailheads.json` and the pack build's derived stretches); `:play` never reads a dataset, and
 * its tests use a fake. Every method is total: an unknown id is an empty list or null, never an exception.
 */
interface PlaceIndex {
    /**
     * The segments [ref] covers, in the dataset of [FeatureRef.goal]: a trail's or road's segments, every trail of a
     * park, or a stretch's coastal segments. Empty when unknown. Trailhead refs have no segments (see [point]).
     */
    fun segments(ref: FeatureRef): List<SegRef>

    /** A trailhead's point; null for other refs or an unknown id. */
    fun point(ref: FeatureRef): GeoPoint?

    /** The trail or road row [id] (`trail-*` or `road-*`), or null. */
    fun feature(id: String): FeatureInfo?

    /** Every trail (or road, counted or not) of [goal]'s dataset. */
    fun features(goal: Goal): List<FeatureInfo>
}
