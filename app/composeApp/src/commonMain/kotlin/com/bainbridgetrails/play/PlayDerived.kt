package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.TrailTables
import com.bainbridgetrails.play.schedule.DerivedGoal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The pack build's derived table for the running goal (`files/play/derived-<goal>.json`), parsed once (AppGraph) and
 * handed to everything that reads it: quiet zone outlines (lon/lat rings) and quiet features, every chart square of a
 * counted segment ([squaresBySegment]; its keys are [includedSegments]), each kraken stretch's segment ids
 * ([stretchSegments]; null when the table has none, so a stretch resolves to its whole feature), the `roadSafety` pass
 * list ([roadSafety]) and the built-in `pool:play-safe-<goal>` ([playSafe]). Other absent keys read as empty.
 *
 * Play trails v1.1 (plan D1, D6; L2-2): the trails table also carries `featurePark` (feature id → park name),
 * `featureGainM` (feature id → gain, metres) and `parks` (the Play parks, excluded parks already removed); together with
 * the counted dataset they make the six trails rules' [TrailTables] ([trailTables]). The roads table has none: null.
 */
class PlayDerived(
    val quietZones: List<QuietZoneOutline>,
    val quietFeatures: Set<String>,
    val quietSegments: Set<String>,
    val squaresBySegment: Map<String, List<String>>,
    val stretchSegments: Map<String, List<String>>?,
    val goal: Goal? = null,
    val roadSafety: Set<String> = emptySet(),
    val playSafe: List<String> = emptyList(),
    val includedSegments: Set<String>? = null,
    val featurePark: Map<String, String>? = null,
    val featureGainM: Map<String, Double>? = null,
    val parks: Set<String>? = null,
) {
    /**
     * The trails rules' tables (plan D6) over the counted [dataset]: [TrailTables.segmentLengthM] = its included
     * segments, [TrailTables.trailSegments] = each trail's segment ids, quiet sets and park/gain tables from this file.
     * Null unless this is the trails table with `featurePark`, `featureGainM` and `parks` (roads: always null).
     */
    fun trailTables(dataset: Dataset): TrailTables? {
        if (goal != Goal.trails) return null
        val park = featurePark ?: return null
        val gain = featureGainM ?: return null
        val playParks = parks ?: return null
        return TrailTables(
            segmentLengthM = dataset.segments.filter { it.included }.associate { it.id to it.lengthM },
            trailSegments = dataset.trails.associate { it.id to it.segmentIds.distinct() },
            quietSegments = quietSegments,
            quietFeatures = quietFeatures,
            featurePark = park,
            featureGainM = gain,
            parks = playParks,
        )
    }

    /** The scheduler's view ([DerivedGoal]); null when the table names no goal (as `DerivedGoal.decode` fails). */
    fun derivedGoal(): DerivedGoal? = goal?.let { DerivedGoal(it, playSafe, includedSegments) }

    /** Every chart square a counted segment touches (the fog's whole island). */
    val squares: List<ChartSquare> by lazy {
        squaresBySegment.values.flatten().toSet().mapNotNull { ChartSquare.parse(it) }.sortedWith(compareBy({ it.row }, { it.col }))
    }

    /** Squares lifted by [covered] segment ids (counted, non-quiet coverage, as W-SQUARE counts them). */
    fun lifted(covered: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (id in covered) if (id !in quietSegments) squaresBySegment[id]?.let(out::addAll)
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val Empty = PlayDerived(emptyList(), emptySet(), emptySet(), emptyMap(), null)

        fun parse(text: String?): PlayDerived {
            if (text == null) return Empty
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Empty
            fun strings(key: String): Set<String> = (root[key] as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.content }.orEmpty()
            val zones = (root["quietZones"] as? JsonArray).orEmpty().mapNotNull { z ->
                val o = z as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val ring = (o["polygon"] as? JsonArray)?.mapNotNull { p ->
                    val a = p as? JsonArray ?: return@mapNotNull null
                    val lon = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    GeoPoint(lat = lat, lon = lon)
                }.orEmpty()
                if (ring.size >= 3) QuietZoneOutline(id, ring) else null
            }
            val squares = (root["segmentSquares"] as? JsonObject)?.mapValues { (_, v) ->
                (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            }.orEmpty()
            val stretches = (root["stretches"] as? JsonObject)?.mapValues { (_, v) ->
                ((v as? JsonObject)?.get("segments") as? JsonArray)?.mapNotNull { (it as? JsonArray)?.firstOrNull()?.let { s -> (s as? JsonPrimitive)?.content } }.orEmpty()
            }
            val goal = (root["goal"] as? JsonPrimitive)?.content?.let { g -> Goal.entries.firstOrNull { it.name == g } }
            val roadSafety = ((root["roadSafety"] as? JsonObject)?.get("ids") as? JsonArray)
                ?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.content }.orEmpty()
            val playSafe = goal?.let { g -> ((root["pools"] as? JsonObject)?.get("play-safe-${g.name}") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }.orEmpty()
            val included = (root["segmentSquares"] as? JsonObject)?.keys?.toSet()
            val featurePark = (root["featurePark"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap()
            val featureGainM = (root["featureGainM"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap()
            val parks = (root["parks"] as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.content }
            return PlayDerived(
                zones, strings("quietFeatures"), strings("quietSegments"), squares, stretches, goal, roadSafety, playSafe, included,
                featurePark, featureGainM, parks,
            )
        }
    }
}
