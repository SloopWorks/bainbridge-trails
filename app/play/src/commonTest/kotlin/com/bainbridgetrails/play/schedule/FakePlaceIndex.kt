package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoPoint

/**
 * In-memory [PlaceIndex] for common tests (schedule.md §4.5: `:play` has no dataset dependency). Each feature is one
 * or more polylines in lon/lat; `stretch:<id>` resolves to the whole feature (the pack build trims stretches).
 */
class FakePlaceIndex(
    features: List<Feature>,
    private val trailheads: Map<String, GeoPoint> = emptyMap(),
) : PlaceIndex {
    class Feature(val info: FeatureInfo, val segments: List<SegRef>)

    private val byId: Map<String, Feature> = features.associateBy { it.info.id }

    override fun feature(id: String): FeatureInfo? = byId[id]?.info

    override fun features(goal: Goal): List<FeatureInfo> = byId.values.map { it.info }.filter { it.goal == goal }

    override fun segments(ref: FeatureRef): List<SegRef> = when (ref) {
        is TrailRef -> byId[ref.id]?.takeIf { it.info.goal == Goal.trails }?.segments.orEmpty()
        is RoadRef -> byId[ref.id]?.takeIf { it.info.goal == Goal.roads }?.segments.orEmpty()
        is StretchRef -> byId[ref.featureId]?.segments.orEmpty()
        is ParkRef -> byId.values.filter { it.info.park == ref.name }.flatMap { it.segments }
        is TrailheadRef, AllTrailheadsRef -> emptyList()
    }

    override fun point(ref: FeatureRef): GeoPoint? = (ref as? TrailheadRef)?.let { trailheads[it.id] }

    companion object {
        private var seq = 0

        fun line(vararg latLon: Double): List<GeoPoint> =
            latLon.toList().chunked(2).map { (lat, lon) -> GeoPoint(lat = lat, lon = lon) }

        fun trail(id: String, line: List<GeoPoint>, park: String? = null): Feature =
            Feature(FeatureInfo(id, Goal.trails, park = park), listOf(SegRef.fromLine("seg-${id}-${seq++}", line)))

        fun road(
            id: String,
            line: List<GeoPoint>,
            category: String = "residential",
            speedMph: Int? = 25,
            public: Boolean = true,
            ownership: String = "public_city",
            counted: Boolean = true,
        ): Feature = Feature(
            FeatureInfo(id, Goal.roads, counted, category, public, ownership, speedMph),
            listOf(SegRef.fromLine("rseg-${id}-${seq++}", line, speedMph, category)),
        )

        const val MEMORIAL = "trail-bainbridge-island-japanese-american-memorial-trail"
        const val CEMETERY = "trail-blakley-cemetary-trail"

        /** A small island: the two real quiet features (approximate geometry) plus safe and unsafe neighbours. */
        fun island(): FakePlaceIndex = FakePlaceIndex(
            listOf(
                trail(MEMORIAL, line(47.6146, -122.5103, 47.6152, -122.5100, 47.6157, -122.5097), park = "Pritchard Park"),
                trail(CEMETERY, line(47.5975, -122.5193, 47.5993, -122.5190, 47.6011, -122.5187)),
                trail("trail-pritchard-trail", line(47.6140, -122.5110, 47.6130, -122.5120), park = "Pritchard Park"),
                trail("trail-pritchard-far", line(47.6400, -122.5500, 47.6410, -122.5490), park = "Pritchard Park"),
                trail("trail-blakely-harbor-trail", line(47.5990, -122.5200, 47.5995, -122.5230), park = "Blakely Harbor Park"),
                trail("trail-grand-forest-a", line(47.6550, -122.5550, 47.6570, -122.5530), park = "Grand Forest"),
                trail("trail-grand-forest-b", line(47.6580, -122.5560, 47.6590, -122.5540), park = "Grand Forest"),
                trail("trail-west-loop-trail", line(47.6230, -122.5200, 47.6240, -122.5180)),
                road("road-safe-a", line(47.6900, -122.5250, 47.6920, -122.5240)),
                road("road-safe-coast", line(47.7000, -122.5050, 47.7020, -122.5030), speedMph = null),
                road("road-safe-arterial", line(47.6300, -122.5400, 47.6350, -122.5400), category = "arterial", speedMph = 25),
                road("road-fast-30", line(47.6600, -122.5100, 47.6620, -122.5080), speedMph = 30),
                road("road-collector-null", line(47.6700, -122.5100, 47.6720, -122.5080), category = "collector", speedMph = null),
                road("road-private", line(47.6800, -122.5100, 47.6820, -122.5080), speedMph = 20, public = false, ownership = "private"),
                road("road-state", line(47.6500, -122.5600, 47.6700, -122.5600), category = "state_route", speedMph = 50, ownership = "public_state"),
                road("road-taylor", line(47.6150, -122.5125, 47.6160, -122.5125)),
                road("road-uncounted", line(47.6850, -122.5300, 47.6860, -122.5290), counted = false),
            ),
            trailheads = mapOf("th-forest" to GeoPoint(lat = 47.6550, lon = -122.5560)),
        )
    }
}
