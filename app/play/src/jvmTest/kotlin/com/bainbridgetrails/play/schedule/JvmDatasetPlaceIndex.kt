package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume
import java.io.File

/**
 * A [PlaceIndex] over the repo's real datasets (`data/dist/{trails,coverage_segments,trailheads}.json`,
 * `data/roads/dist/{roads,coverage_segments}.json`) and, when present, the pack build's derived stretches
 * (`data/play/derived/{trails,roads}.json`). The datasets are gitignored, so tests call [loadOrSkip]: a checkout
 * without them reports the test as skipped (JUnit assumption), like `:coverage`'s `assumeDataFiles`.
 * Paths are relative to the Gradle test working directory (`app/play`).
 */
class JvmDatasetPlaceIndex private constructor(
    private val features: Map<String, FeatureInfo>,
    private val featureSegments: Map<String, List<String>>,
    private val segments: Map<Goal, Map<String, SegRef>>,
    private val included: Map<Goal, Set<String>>,
    private val trailheads: Map<String, GeoPoint>,
    /** featureId → segment ids of its derived coastal stretch; null = no derived file for that goal. */
    private val stretches: Map<Goal, Map<String, List<String>>?>,
) : PlaceIndex {

    override fun feature(id: String): FeatureInfo? = features[id]

    override fun features(goal: Goal): List<FeatureInfo> = features.values.filter { it.goal == goal }

    override fun segments(ref: FeatureRef): List<SegRef> = when (ref) {
        is TrailRef -> segsOf(ref.id, Goal.trails)
        is RoadRef -> segsOf(ref.id, Goal.roads)
        is ParkRef -> features(Goal.trails).filter { it.park == ref.name }.flatMap { segsOf(it.id, Goal.trails) }
        is StretchRef -> {
            val goal = ref.goal ?: return emptyList()
            val derived = stretches[goal]
            when {
                features[ref.featureId]?.goal != goal -> emptyList()
                derived == null -> segsOf(ref.featureId, goal) // no derived file: the whole feature (a superset)
                else -> derived[ref.featureId].orEmpty().mapNotNull { segments.getValue(goal)[it] }
            }
        }
        is TrailheadRef, AllTrailheadsRef -> emptyList()
    }

    override fun point(ref: FeatureRef): GeoPoint? = (ref as? TrailheadRef)?.let { trailheads[it.id] }

    private fun segsOf(featureId: String, goal: Goal): List<SegRef> {
        if (features[featureId]?.goal != goal) return emptyList()
        val segs = segments.getValue(goal)
        return featureSegments[featureId].orEmpty().mapNotNull { segs[it] }
    }

    /** Every segment of [goal]'s dataset (the roads dataset also carries the trail segments it shares). */
    fun allSegments(goal: Goal): Collection<SegRef> = segments.getValue(goal).values

    /** Segments the goal counts (`included: true`). */
    fun includedSegments(goal: Goal): List<SegRef> = included.getValue(goal).map { segments.getValue(goal).getValue(it) }

    @Serializable
    private class FeaturesFile(val trails: List<Row> = emptyList(), val roads: List<Row> = emptyList())

    @Serializable
    private class Row(
        val id: String,
        @SerialName("segment_ids") val segmentIds: List<String>,
        val park: String? = null,
        val counted: Boolean = true,
        val category: String? = null,
        val public: Boolean? = null,
        val ownership: String? = null,
        @SerialName("speed_mph") val speedMph: Int? = null,
    )

    @Serializable
    private class SegmentsFile(val segments: List<Seg>)

    @Serializable
    private class Seg(
        val id: String,
        val included: Boolean = false,
        val coords: List<List<Double>>,
        val category: String? = null,
    )

    @Serializable
    private class TrailheadsFile(val trailheads: List<Trailhead>)

    @Serializable
    private class Trailhead(val id: String, val lat: Double, val lon: Double)

    @Serializable
    private class DerivedFile(val stretches: Map<String, Stretch> = emptyMap())

    @Serializable
    private class Stretch(val segments: List<List<kotlinx.serialization.json.JsonPrimitive>>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val ROOT = File("../..")
        val TRAILS = File(ROOT, "data/dist/trails.json")
        val TRAIL_SEGMENTS = File(ROOT, "data/dist/coverage_segments.json")
        val TRAILHEADS = File(ROOT, "data/dist/trailheads.json")
        val ROADS = File(ROOT, "data/roads/dist/roads.json")
        val ROAD_SEGMENTS = File(ROOT, "data/roads/dist/coverage_segments.json")
        private fun derived(goal: Goal) = File(ROOT, "data/play/derived/${goal.name}.json")
        private val REQUIRED = listOf(TRAILS, TRAIL_SEGMENTS, ROADS, ROAD_SEGMENTS)

        private val cached: JvmDatasetPlaceIndex? by lazy { if (REQUIRED.all { it.exists() }) load() else null }

        /** The index, or a skipped test (JUnit assumption) naming the missing files. */
        fun loadOrSkip(test: String): JvmDatasetPlaceIndex {
            val missing = REQUIRED.filterNot { it.exists() }
            Assume.assumeTrue(
                "$test: SKIPPED, missing ${missing.joinToString { it.path }} (build data/dist and data/roads/dist with pipeline/)",
                missing.isEmpty(),
            )
            return cached!!
        }

        private fun load(): JvmDatasetPlaceIndex {
            val trails = json.decodeFromString(FeaturesFile.serializer(), TRAILS.readText()).trails
            val roads = json.decodeFromString(FeaturesFile.serializer(), ROADS.readText()).roads
            val features = LinkedHashMap<String, FeatureInfo>()
            val featureSegments = HashMap<String, List<String>>()
            for ((goal, rows) in listOf(Goal.trails to trails, Goal.roads to roads)) for (r in rows) {
                features[r.id] = FeatureInfo(r.id, goal, r.counted, r.category, r.public, r.ownership, r.speedMph, r.park)
                featureSegments[r.id] = r.segmentIds
            }
            val roadSpeed = HashMap<String, Int?>()
            for (r in roads) for (s in r.segmentIds) roadSpeed[s] = r.speedMph
            val segments = HashMap<Goal, Map<String, SegRef>>()
            val included = HashMap<Goal, Set<String>>()
            for ((goal, file) in listOf(Goal.trails to TRAIL_SEGMENTS, Goal.roads to ROAD_SEGMENTS)) {
                val segs = json.decodeFromString(SegmentsFile.serializer(), file.readText()).segments
                segments[goal] = segs.associate { s ->
                    val line = s.coords.map { GeoPoint(lat = it[1], lon = it[0]) }
                    s.id to SegRef.fromLine(s.id, line, if (goal == Goal.roads) roadSpeed[s.id] else null, s.category)
                }
                included[goal] = segs.filter { it.included }.map { it.id }.toCollection(LinkedHashSet())
            }
            val trailheads = if (TRAILHEADS.exists()) {
                json.decodeFromString(TrailheadsFile.serializer(), TRAILHEADS.readText()).trailheads
                    .associate { it.id to GeoPoint(it.lat, it.lon) }
            } else {
                emptyMap()
            }
            val stretches = Goal.entries.associateWith { goal ->
                derived(goal).takeIf { it.exists() }?.let { f ->
                    json.decodeFromString(DerivedFile.serializer(), f.readText()).stretches
                        .mapValues { (_, st) -> st.segments.map { it[0].content } }
                }
            }
            return JvmDatasetPlaceIndex(features, featureSegments, segments, included, trailheads, stretches)
        }
    }
}
