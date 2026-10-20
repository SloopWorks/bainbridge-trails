package com.bainbridgetrails.map

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlin.math.floor
import kotlin.math.roundToLong

/** A trailhead with a display name (decisions.md TL-4: names from trailheads.json). */
data class Trailhead(val name: String?, val lat: Double, val lon: Double)

/** How much of a named trail counts toward the island total (decisions.md TL-6). */
enum class IslandCredit { FULL, PARTIAL, NONE }

/**
 * Trail facts the map and detail screens show beyond [com.bainbridgetrails.coverage.TrailMeta]. [roadEnds] = the
 * row's public road ends at the shore (roads dataset; road_ends.json), nearest the water first.
 */
data class TrailFacts(
    val id: String,
    val park: String?,
    val manager: String?,
    val gainM: Double?,
    val officialMapUrl: String?,
    val officialLengthMi: Double?,
    val computedLengthMi: Double,
    val trailheads: List<Trailhead>,
    val roadEnds: List<RoadEnd> = emptyList(),
) {
    /** Official length when the agency publishes one, else computed (product F4 step 3). */
    val lengthMi: Double get() = officialLengthMi ?: computedLengthMi
    val isOfficialLength: Boolean get() = officialLengthMi != null
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FactsFile(@JsonNames("roads") val trails: List<FactsRow>)

@Serializable
private data class FactsRow(
    val id: String,
    val park: String? = null,
    val manager: String? = null,
    @SerialName("gain_m") val gainM: Double? = null,
    @SerialName("official_map_url") val officialMapUrl: String? = null,
    @SerialName("official_length_mi") val officialLengthMi: Double? = null,
    @SerialName("computed_length_mi") val computedLengthMi: Double = 0.0,
    val trailheads: List<PointGeom> = emptyList(),
)

@Serializable
private data class PointGeom(val coordinates: List<Double>)

@Serializable
private data class TrailheadsFile(val trailheads: List<TrailheadRow>)

@Serializable
private data class TrailheadRow(
    val id: String,
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    @SerialName("trail_ids") val trailIds: List<String> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses trails.json facts + trailheads.json. A trail's trailheads are the named trailheads.json entries that list it
 * (duplicate names numbered); a trail without any falls back to the unnamed points in trails.json.
 */
fun parseTrailFacts(
    trailsJson: String,
    trailheadsJson: String?,
    roadEnds: Map<String, List<RoadEnd>> = emptyMap(),
): Map<String, TrailFacts> {
    val rows = json.decodeFromString(FactsFile.serializer(), trailsJson).trails
    val named = HashMap<String, MutableList<TrailheadRow>>()
    if (trailheadsJson != null) {
        for (th in json.decodeFromString(TrailheadsFile.serializer(), trailheadsJson).trailheads) {
            for (t in th.trailIds) named.getOrPut(t) { ArrayList() } += th
        }
    }
    return rows.associate { r ->
        val heads = named[r.id]?.distinctBy { it.id }?.let { list ->
            val counts = list.groupingBy { it.name }.eachCount()
            val seen = HashMap<String?, Int>()
            list.map { th ->
                val n = (seen[th.name] ?: 0) + 1
                seen[th.name] = n
                val label = if (th.name != null && counts.getValue(th.name) > 1) "${th.name} ($n)" else th.name
                Trailhead(label, th.lat, th.lon)
            }
        } ?: r.trailheads.filter { it.coordinates.size >= 2 }.map { Trailhead(null, it.coordinates[1], it.coordinates[0]) }
        r.id to TrailFacts(r.id, r.park, r.manager, r.gainM, r.officialMapUrl, r.officialLengthMi, r.computedLengthMi, heads, roadEnds[r.id].orEmpty())
    }
}

/** Bundled facts of the active dataset ([DatasetProfile]), parsed once per process off Main. */
object TrailFactsRepository {
    private val lock = Mutex()
    private var cached: Map<String, TrailFacts>? = null

    suspend fun all(): Map<String, TrailFacts> = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val profile = DatasetProfile.current
            val trails = Res.readBytes("files/${profile.metadataFile}").decodeToString()
            val heads = if (profile == DatasetProfile.TRAILS) runCatching { Res.readBytes("files/trailheads.json").decodeToString() }.getOrNull() else null
            val ends = roadEndsFile(profile)?.let { f -> runCatching { Res.readBytes("files/$f").decodeToString() }.getOrNull() }
            parseTrailFacts(trails, heads, ends?.let(::parseRoadEnds).orEmpty())
        }.also { cached = it }
    }

    /** Cached facts if already loaded (no suspension), for synchronous callers that can wait. */
    val loaded: Map<String, TrailFacts>? get() = cached
}

/** TL-6: does this trail's ground count toward the island %? (By its segments' `included` flags.) */
fun Dataset.islandCredit(trailId: String): IslandCredit {
    val trail = trails.firstOrNull { it.id == trailId } ?: return IslandCredit.NONE
    val byId = segmentsById
    val flags = trail.segmentIds.mapNotNull { byId[it]?.included }
    return when {
        flags.isEmpty() || flags.none { it } -> IslandCredit.NONE
        flags.all { it } -> IslandCredit.FULL
        else -> IslandCredit.PARTIAL
    }
}

/** The [lon, lat] lines of a trail's segments, for bounds and the selection overlay. */
fun Dataset.trailLines(trailId: String): List<List<List<Double>>> {
    val trail = trails.firstOrNull { it.id == trailId } ?: return emptyList()
    val byId = segmentsById
    return trail.segmentIds.distinct().mapNotNull { byId[it]?.coords }
}

private val segmentIndexCache = HashMap<Dataset, Map<String, com.bainbridgetrails.coverage.SegmentGeom>>()

/** segment id -> geometry (memoized per dataset instance). */
val Dataset.segmentsById: Map<String, com.bainbridgetrails.coverage.SegmentGeom>
    get() = segmentIndexCache.getOrPut(this) { segments.associateBy { it.id } }

private const val METRES_PER_MILE = 1609.344
private const val FEET_PER_METRE = 3.28084

/** One decimal, half-up ("1.4"). */
private fun oneDecimal(v: Double): String {
    val t = (v * 10).roundToLong()
    return "${t / 10}.${t % 10}"
}

/**
 * Trail length in the user's units (decisions.md P2): one decimal; under 0.1 mi (0.1 km) in feet (metres), rounded to
 * 10 ft (5 m), no "~".
 */
fun formatTrailLength(miles: Double, units: Units): String = when (units) {
    Units.MILES -> if (miles < 0.1) "${roundTo(miles * METRES_PER_MILE * FEET_PER_METRE, 10)} ft" else "${oneDecimal(miles)} mi"
    Units.KILOMETRES -> {
        val km = miles * METRES_PER_MILE / 1000
        if (km < 0.1) "${roundTo(km * 1000, 5)} m" else "${oneDecimal(km)} km"
    }
}

/** Covered and total for "0.2 of 0.3 mi" (product detail_covered_value): covered without the unit (same as total), to the nearest tenth but rounded **down** when that would read as the full
 * length before the trail is fully walked. */
fun formatCovered(fraction: Double, miles: Double, units: Units): Pair<String, String> {
    val total = formatTrailLength(miles, units)
    val coveredMi = miles * fraction.coerceIn(0.0, 1.0)
    // Nearest tenth (0.29 → "0.3"), but never the full length before the trail is fully walked.
    val nearest = coveredAmount(coveredMi, miles, units) { oneDecimal(it) }
    if (fraction >= 1.0 || nearest != total.substringBefore(' ')) return nearest to total
    return coveredAmount(coveredMi, miles, units) { floorDecimal(it) } to total
}

private fun coveredAmount(coveredMi: Double, miles: Double, units: Units, tenths: (Double) -> String): String = when (units) {
    Units.MILES -> if (miles < 0.1) "${floorTo(coveredMi * METRES_PER_MILE * FEET_PER_METRE, 10)}" else tenths(coveredMi)
    Units.KILOMETRES -> {
        val km = coveredMi * METRES_PER_MILE / 1000
        if (miles * METRES_PER_MILE / 1000 < 0.1) "${floorTo(km * 1000, 5)}" else tenths(km)
    }
}

/** Elevation in the user's units, whole numbers: "38 m" / "125 ft". */
fun formatElevation(metres: Double, units: Units): String = when (units) {
    Units.MILES -> "${(metres * FEET_PER_METRE).roundToLong()} ft"
    Units.KILOMETRES -> "${metres.roundToLong()} m"
}

private fun roundTo(v: Double, step: Int): Long = maxOf(step.toLong(), (v / step).roundToLong() * step)
private fun floorTo(v: Double, step: Int): Long = (floor(v / step + 1e-9).toLong() * step)
private fun floorDecimal(v: Double): String {
    val t = floor(v * 10 + 1e-9).toLong()
    return "${t / 10}.${t % 10}"
}
