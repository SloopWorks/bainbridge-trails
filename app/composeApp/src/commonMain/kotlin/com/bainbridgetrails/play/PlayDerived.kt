package com.bainbridgetrails.play

import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.geo.ChartSquare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The pack build's derived table for the running goal (`files/play/derived-<goal>.json`) as the map and the walk
 * marks read it: quiet zone outlines (lon/lat rings) and quiet features, every chart square of a counted segment
 * ([squaresBySegment]), and each kraken stretch's segment ids ([stretchSegments]). Absent keys read as empty.
 */
class PlayDerived(
    val quietZones: List<QuietZoneOutline>,
    val quietFeatures: Set<String>,
    val quietSegments: Set<String>,
    val squaresBySegment: Map<String, List<String>>,
    val stretchSegments: Map<String, List<String>>,
) {
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

        val Empty = PlayDerived(emptyList(), emptySet(), emptySet(), emptyMap(), emptyMap())

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
                    GeoPoint(lon, lat)
                }.orEmpty()
                if (ring.size >= 3) QuietZoneOutline(id, ring) else null
            }
            val squares = (root["segmentSquares"] as? JsonObject)?.mapValues { (_, v) ->
                (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            }.orEmpty()
            val stretches = (root["stretches"] as? JsonObject)?.mapValues { (_, v) ->
                ((v as? JsonObject)?.get("segments") as? JsonArray)?.mapNotNull { (it as? JsonArray)?.firstOrNull()?.let { s -> (s as? JsonPrimitive)?.content } }.orEmpty()
            }.orEmpty()
            return PlayDerived(zones, strings("quietFeatures"), strings("quietSegments"), squares, stretches)
        }
    }
}
