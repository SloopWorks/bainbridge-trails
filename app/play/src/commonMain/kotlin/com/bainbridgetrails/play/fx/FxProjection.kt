package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Anchor-local Web-Mercator offsets in dp (north-up, untilted), engine.md PF-4: the same math as composeApp
 * map/MapLogic.kt `mercatorOffsetDp` (parity-tested within 1e-9). Allocation-free.
 */
object FxProjection {
    /** Width of the world in dp at zoom 0 (MapLibre's 512-px tiles). */
    const val WORLD_DP = 512.0
    /** Web Mercator sphere radius (WGS84 semi-major axis). */
    const val EARTH_RADIUS_M = 6_378_137.0

    private fun mx(lon: Double): Double = (lon + 180.0) / 360.0

    private fun my(lat: Double): Double {
        val s = sin(lat * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private fun world(zoom: Double): Double = WORLD_DP * 2.0.pow(zoom)

    /** x offset (dp, east positive) of [lon] from [anchorLon] at [zoom]. */
    fun offsetXDp(anchorLon: Double, lon: Double, zoom: Double): Double = (mx(lon) - mx(anchorLon)) * world(zoom)

    /** y offset (dp, south positive) of [lat] from [anchorLat] at [zoom]. */
    fun offsetYDp(anchorLat: Double, lat: Double, zoom: Double): Double = (my(lat) - my(anchorLat)) * world(zoom)

    /** Writes (x, y) dp of each of [points] relative to [anchor] into [out] (size ≥ 2·n). */
    fun offsetsDp(anchor: GeoPoint, points: List<GeoPoint>, zoom: Double, out: FloatArray) {
        require(out.size >= points.size * 2) { "out too small: ${out.size} < ${points.size * 2}" }
        val w = world(zoom)
        val ax = mx(anchor.lon)
        val ay = my(anchor.lat)
        for (i in points.indices) {
            val p = points[i]
            out[2 * i] = ((mx(p.lon) - ax) * w).toFloat()
            out[2 * i + 1] = ((my(p.lat) - ay) * w).toFloat()
        }
    }

    /** Ground distance [metres] at latitude [lat] as dp at [zoom]. */
    fun metresToDp(metres: Double, lat: Double, zoom: Double): Double =
        metres * world(zoom) / (2 * PI * EARTH_RADIUS_M * cos(lat * PI / 180.0))
}
