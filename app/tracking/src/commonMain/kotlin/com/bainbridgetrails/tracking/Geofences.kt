package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.TrailStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One entry of `trailheads.json` (`data/dist/trailheads.json`, synced into composeResources). */
@Serializable
data class Trailhead(
    val id: String,
    val lon: Double,
    val lat: Double,
    val name: String,
    @SerialName("trail_ids") val trailIds: List<String>,
)

@Serializable
private data class TrailheadsFile(val version: String = "", val trailheads: List<Trailhead>)

/** Opt-in trailhead geofences: which trailheads to register and what the arrival notification says. */
object Geofences {
    const val RADIUS_M = 75.0
    const val IOS_MAX = 20
    const val ANDROID_MAX = 100

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Trailhead> = json.decodeFromString(TrailheadsFile.serializer(), text).trailheads

    /** Up to [max] trailheads ordered by great-circle distance from (lon, lat), nearest first. */
    fun nearest(all: List<Trailhead>, lon: Double, lat: Double, max: Int): List<Trailhead> {
        if (max <= 0) return emptyList()
        return all.sortedBy { distanceM(lon, lat, it.lon, it.lat) }.take(max)
    }

    /** "You're at <name> — <k> of <n> trails here complete"; n = trailIds.size, k = COMPLETE among them. */
    fun arrivalText(th: Trailhead, coverage: CoverageState, progress: Progress): String {
        val n = th.trailIds.size
        val k = th.trailIds.count { progress.status(progress.trailFraction(coverage, it)) == TrailStatus.COMPLETE }
        return "You're at ${th.name} — $k of $n trails here complete"
    }

    /**
     * Whether trailhead geofences may be (re-)registered: the feature exists in this build, the user is opted in,
     * and background location is granted. Checked before loading AND again immediately before the platform add
     * call, so an opt-out during an in-flight re-arm wins.
     */
    fun shouldRegister(declared: Boolean, optedIn: Boolean, backgroundGranted: Boolean): Boolean =
        declared && optedIn && backgroundGranted

    /** Haversine distance in metres. */
    fun distanceM(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6_371_008.8
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dp = p2 - p1
        val dl = (lon2 - lon1) * PI / 180.0
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
