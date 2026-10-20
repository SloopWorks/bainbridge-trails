package com.bainbridgetrails.map

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.data.DatasetProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Road metadata from roads.json (docs/roads/data-spec.md) that the road screens show (docs/roads/product.md §4).
 * Values are the raw dataset enums; labels are the UI's job.
 */
data class RoadFacts(
    /** state_route / arterial / collector / residential / lane (/ driveway). */
    val category: String,
    /** Part of the island goal (public, counted category). */
    val counted: Boolean,
    /** public_city / public_state / public_parks / public_school / public_county / private / unknown. */
    val ownership: String?,
    /** paved / gravel / unpaved / dirt, or null (no data: the tile is hidden). */
    val surface: String?,
    val speedMph: Int?,
    val maintainer: String?,
    /** Total up + down along the road (m). */
    val climbTotalM: Double?,
    /** Public road ends at the shore, nearest the water first. */
    val roadEnds: List<RoadEnd> = emptyList(),
) {
    /** Why an uncounted road is out of the goal (lane wins over ownership: lanes never count). */
    val notCountedReason: RoadReason?
        get() = when {
            counted -> null
            category == "lane" || category == "driveway" -> RoadReason.LANE
            ownership == "private" -> RoadReason.PRIVATE
            else -> RoadReason.UNKNOWN
        }

    /** Safety line (product.md §4 step 4): state routes, else ≥ [FAST_MPH]. */
    val caution: RoadCaution?
        get() = when {
            category == "state_route" -> RoadCaution.StateRoute
            (speedMph ?: 0) >= FAST_MPH -> RoadCaution.Fast(speedMph!!)
            else -> null
        }

    /** Owner shown under the name (product RP-14: from the ownership enum; WSDOT maintainer wins). */
    val owner: RoadOwner?
        get() = when {
            maintainer.equals("WSDOT", ignoreCase = true) || ownership == "public_state" -> RoadOwner.STATE
            ownership == "public_city" -> RoadOwner.CITY
            ownership == "public_county" -> RoadOwner.COUNTY
            ownership == "public_parks" -> RoadOwner.PARKS
            ownership == "public_school" -> RoadOwner.SCHOOL
            else -> null
        }

    companion object {
        const val FAST_MPH = 40
    }
}

enum class RoadReason { PRIVATE, UNKNOWN, LANE }

enum class RoadOwner { CITY, STATE, COUNTY, PARKS, SCHOOL }

sealed interface RoadCaution {
    data object StateRoute : RoadCaution
    data class Fast(val mph: Int) : RoadCaution
}

/** A public road end at the shoreline (road_ends.json). */
data class RoadEnd(val roadId: String, val lat: Double, val lon: Double, val shoreDistanceM: Double, val cityConfirmed: Boolean)

@Serializable
private data class RoadEndsFile(@SerialName("road_ends") val roadEnds: List<RoadEndRow> = emptyList())

@Serializable
private data class RoadEndRow(
    @SerialName("road_id") val roadId: String,
    val coordinates: List<Double>,
    @SerialName("shore_distance_m") val shoreDistanceM: Double = 0.0,
    @SerialName("city_confirmed") val cityConfirmed: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

/** road_ends.json → road id → its ends, nearest the shore first. Malformed points are skipped. */
fun parseRoadEnds(text: String): Map<String, List<RoadEnd>> =
    json.decodeFromString(RoadEndsFile.serializer(), text).roadEnds
        .filter { it.coordinates.size >= 2 }
        .map { RoadEnd(it.roadId, lat = it.coordinates[1], lon = it.coordinates[0], it.shoreDistanceM, it.cityConfirmed) }
        .groupBy { it.roadId }
        .mapValues { (_, v) -> v.sortedBy { it.shoreDistanceM } }

/**
 * The road-ends document of [profile] in composeResources/files/, or null when the dataset has none. Copied there by
 * scripts/sync-app-assets.sh as `roads_road_ends.json` (from data/roads/dist/road_ends.json); missing = no pins.
 */
fun roadEndsFile(profile: DatasetProfile): String? = when (profile) {
    DatasetProfile.TRAILS -> null
    DatasetProfile.ROADS -> "roads_road_ends.json"
}

private const val METRES_PER_MILE = 1609.344

/**
 * Length of a road's **counted** segments in miles (product.md §4 step 5: partly private roads measure the public part),
 * or the whole road when none counts. Shared segments count once.
 */
fun Dataset.countedLengthMi(roadId: String): Double {
    val road = trails.firstOrNull { it.id == roadId } ?: return 0.0
    val byId = segmentsById
    val segs = road.segmentIds.distinct().mapNotNull { byId[it] }
    val counted = segs.filter { it.included }
    val use = counted.ifEmpty { segs }
    return use.sumOf { it.lengthM } / METRES_PER_MILE
}

/** All road ends of the dataset as (road id, end), for the map's pin layer. */
fun Map<String, TrailFacts>.allRoadEnds(): List<RoadEnd> = values.flatMap { it.road?.roadEnds.orEmpty() }
