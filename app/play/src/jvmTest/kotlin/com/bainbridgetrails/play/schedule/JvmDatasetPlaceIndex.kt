package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A [PlaceIndex] over the island map data and, when present, the pack build's derived stretches
 * (`data/play/derived/{trails,roads}.json`, committed). Two sources (review round 1 P1-7), so no dataset test ever skips:
 * - [Source.DATASETS]: the real files (`data/dist/{trails,coverage_segments,trailheads}.json`,
 *   `data/roads/dist/{roads,coverage_segments}.json`), gitignored, used when built here;
 * - [Source.FIXTURE]: the committed `src/jvmTest/resources/fixture/places.json` that
 *   `node scripts/build-play-content.mjs --fixture` writes from them (format: `FIXTURE` in that script). Every segment
 *   a feature references or a goal counts is lossless, so placements, lengths and chart squares are the datasets'
 *   exactly; the rest (read only by quiet-zone counts) is simplified. CI and fresh checkouts use it.
 * `-Pplay.noData=true` (system property `play.noData`) forces the fixture even when the datasets are built;
 * [JvmDatasetPlaceIndexParityTest] asserts both sources answer alike. Paths are relative to the Gradle test working
 * directory (`app/play`).
 */
class JvmDatasetPlaceIndex private constructor(
    val source: Source,
    /** The datasets' `version` strings (= `data/play/derived/<goal>.json` `dataset`). */
    val versions: Map<Goal, String?>,
    private val features: Map<String, FeatureInfo>,
    private val featureSegments: Map<String, List<String>>,
    private val segments: Map<Goal, Map<String, SegRef>>,
    private val included: Map<Goal, Set<String>>,
    /** Trailhead id → point. */
    val trailheads: Map<String, GeoPoint>,
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

    enum class Source { DATASETS, FIXTURE }

    @Serializable
    private class FeaturesFile(val version: String? = null, val trails: List<Row> = emptyList(), val roads: List<Row> = emptyList())

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
        const val FIXTURE = "/fixture/places.json"
        private fun derived(goal: Goal) = File(ROOT, "data/play/derived/${goal.name}.json")
        private val REQUIRED = listOf(TRAILS, TRAIL_SEGMENTS, ROADS, ROAD_SEGMENTS)

        /** `-Pplay.noData=true`: use the committed fixture even where the datasets are built. */
        val noData: Boolean get() = System.getProperty("play.noData") == "true"

        /** The real datasets, or null when they are not built here (or [noData]). */
        val datasets: JvmDatasetPlaceIndex? by lazy { if (!noData && REQUIRED.all { it.exists() }) loadDatasets() else null }

        /** The committed fixture (always available). */
        val fixture: JvmDatasetPlaceIndex by lazy { loadFixture() }

        /** The index dataset tests run on: the real datasets when present, else the committed fixture. */
        val index: JvmDatasetPlaceIndex by lazy {
            (datasets ?: fixture).also { println("JvmDatasetPlaceIndex: ${it.source} (datasets ${it.versions.values.joinToString()})") }
        }

        /** [index]. The name predates P1-7: dataset tests no longer skip, they fall back to the committed fixture. */
        @Suppress("UNUSED_PARAMETER")
        fun loadOrSkip(test: String): JvmDatasetPlaceIndex = index

        private fun loadDatasets(): JvmDatasetPlaceIndex {
            val trails = json.decodeFromString(FeaturesFile.serializer(), TRAILS.readText())
            val roads = json.decodeFromString(FeaturesFile.serializer(), ROADS.readText())
            val segs = mapOf(
                Goal.trails to json.decodeFromString(SegmentsFile.serializer(), TRAIL_SEGMENTS.readText()).segments,
                Goal.roads to json.decodeFromString(SegmentsFile.serializer(), ROAD_SEGMENTS.readText()).segments,
            )
            val trailheads = if (TRAILHEADS.exists()) {
                json.decodeFromString(TrailheadsFile.serializer(), TRAILHEADS.readText()).trailheads
                    .associate { it.id to GeoPoint(lat = it.lat, lon = it.lon) }
            } else {
                emptyMap()
            }
            val versions = mapOf(Goal.trails to trails.version, Goal.roads to roads.version)
            return assemble(Source.DATASETS, versions, mapOf(Goal.trails to trails.trails, Goal.roads to roads.roads), segs, trailheads)
        }

        private fun loadFixture(): JvmDatasetPlaceIndex {
            val text = JvmDatasetPlaceIndex::class.java.getResource(FIXTURE)?.readText()
                ?: error("$FIXTURE missing from the jvmTest classpath: run node scripts/build-play-content.mjs --fixture")
            val root = json.parseToJsonElement(text).jsonObject
            check(root.getValue("schema").jsonPrimitive.int == 1) { "$FIXTURE: unknown schema ${root["schema"]}" }
            val versions = root.getValue("versions").jsonObject.let { v ->
                Goal.entries.associateWith { (v[it.name] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            }
            val segs = Goal.entries.associateWith { decodeSegments(root.getValue("segments").jsonObject.getValue(it.name).jsonObject) }
            val rows = Goal.entries.associateWith { g ->
                decodeFeatures(root.getValue("features").jsonObject.getValue(g.name).jsonObject, segs.getValue(g))
            }
            val trailheads = root.getValue("trailheads").jsonArray.associate { t ->
                val a = t.jsonArray
                a[0].jsonPrimitive.content to GeoPoint(lat = a[1].jsonPrimitive.double, lon = a[2].jsonPrimitive.double)
            }
            return assemble(Source.FIXTURE, versions, rows, segs, trailheads)
        }

        /** `segments.<goal>`: rows of [id or id gap, flags, category index, polyline], deltas carried across rows. */
        private fun decodeSegments(block: JsonObject): List<Seg> {
            val categories = block.getValue("categories").jsonArray.map { it.jsonPrimitive.content }
            val out = ArrayList<Seg>()
            val pos = IntArray(2) // lat, lon in micro-degrees, carried across rows
            var prefix = ""
            var number = 0
            var width = 0
            for (row in block.getValue("rows").jsonArray) {
                val r = row.jsonArray
                val idEl = r[0].jsonPrimitive
                if (idEl.isString) {
                    val id = idEl.content
                    val digits = id.takeLastWhile { it.isDigit() }
                    prefix = id.dropLast(digits.length)
                    number = digits.toInt()
                    width = digits.length
                } else {
                    number += idEl.int
                }
                val flags = r[1].jsonPrimitive.int
                val cat = r[2].jsonPrimitive.int
                val coords = decodePolyline(r[3].jsonPrimitive.content, pos)
                    .map { (lat, lon) -> listOf(lon / 1e6, lat / 1e6) } // division: the same double as parsing the 6-decimal text
                out += Seg(prefix + number.toString().padStart(width, '0'), flags and 1 != 0, coords, categories.getOrNull(cat))
            }
            return out
        }

        /** Google encoded polyline: [lat, lon] integer pairs, starting from (and advancing) [pos]. */
        private fun decodePolyline(s: String, pos: IntArray): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            fun next(): Int {
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = s[i++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                return if (result and 1 != 0) (result ushr 1).inv() else result ushr 1
            }
            while (i < s.length) {
                pos[0] += next()
                pos[1] += next()
                out += pos[0] to pos[1]
            }
            return out
        }

        /** `features.<goal>`: tuples over `columns`; `segments` are indices into the goal's segment rows. */
        private fun decodeFeatures(block: JsonObject, segs: List<Seg>): List<Row> {
            val columns = block.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
            return block.getValue("rows").jsonArray.map { row ->
                val v = columns.zip(row.jsonArray).toMap()
                fun str(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                fun prim(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                Row(
                    id = str("id")!!,
                    segmentIds = v.getValue("segments").jsonArray.map { e ->
                        val p = e.jsonPrimitive
                        if (p.isString) p.content else segs[p.int].id
                    },
                    park = str("park"),
                    counted = prim("counted")?.boolean ?: true,
                    category = str("category"),
                    public = prim("public")?.boolean,
                    ownership = str("ownership"),
                    speedMph = prim("speed_mph")?.int,
                )
            }
        }

        private fun assemble(
            source: Source,
            versions: Map<Goal, String?>,
            rows: Map<Goal, List<Row>>,
            segs: Map<Goal, List<Seg>>,
            trailheads: Map<String, GeoPoint>,
        ): JvmDatasetPlaceIndex {
            val features = LinkedHashMap<String, FeatureInfo>()
            val featureSegments = HashMap<String, List<String>>()
            for (goal in listOf(Goal.trails, Goal.roads)) for (r in rows.getValue(goal)) {
                features[r.id] = FeatureInfo(r.id, goal, r.counted, r.category, r.public, r.ownership, r.speedMph, r.park)
                featureSegments[r.id] = r.segmentIds
            }
            val roadSpeed = HashMap<String, Int?>()
            for (r in rows.getValue(Goal.roads)) for (s in r.segmentIds) roadSpeed[s] = r.speedMph
            val segments = HashMap<Goal, Map<String, SegRef>>()
            val included = HashMap<Goal, Set<String>>()
            for (goal in listOf(Goal.trails, Goal.roads)) {
                val list = segs.getValue(goal)
                segments[goal] = list.associate { s ->
                    val line = s.coords.map { GeoPoint(lat = it[1], lon = it[0]) }
                    s.id to SegRef.fromLine(s.id, line, if (goal == Goal.roads) roadSpeed[s.id] else null, s.category)
                }
                included[goal] = list.filter { it.included }.map { it.id }.toCollection(LinkedHashSet())
            }
            val stretches = Goal.entries.associateWith { goal ->
                derived(goal).takeIf { it.exists() }?.let { f ->
                    json.decodeFromString(DerivedFile.serializer(), f.readText()).stretches
                        .mapValues { (_, st) -> st.segments.map { it[0].content } }
                }
            }
            return JvmDatasetPlaceIndex(source, versions, features, featureSegments, segments, included, trailheads, stretches)
        }
    }
}
