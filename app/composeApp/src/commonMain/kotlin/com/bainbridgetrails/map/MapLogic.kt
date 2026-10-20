package com.bainbridgetrails.map

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Lon/lat bounds. */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    val centerLon: Double get() = (west + east) / 2
    val centerLat: Double get() = (south + north) / 2
}

/** Island home bounds (ux.md §6.3). */
val IslandBounds = GeoBounds(west = -122.600, south = 47.575, east = -122.480, north = 47.725)

/** Bounds of every [lon, lat] in [lines]; null when there are no coordinates. */
fun boundsOf(lines: List<List<List<Double>>>): GeoBounds? {
    var w = Double.POSITIVE_INFINITY
    var s = Double.POSITIVE_INFINITY
    var e = Double.NEGATIVE_INFINITY
    var n = Double.NEGATIVE_INFINITY
    for (line in lines) for (c in line) {
        if (c.size < 2) continue
        w = minOf(w, c[0]); e = maxOf(e, c[0])
        s = minOf(s, c[1]); n = maxOf(n, c[1])
    }
    return if (w.isInfinite()) null else GeoBounds(w, s, e, n)
}

/** Trail fit zoom range (ux.md §6.3). */
const val FIT_MIN_ZOOM = 12.0
const val FIT_MAX_ZOOM = 16.5

fun clampFitZoom(zoom: Double): Double = zoom.coerceIn(FIT_MIN_ZOOM, FIT_MAX_ZOOM)

/** Screen point in dp. */
data class ScreenPoint(val x: Double, val y: Double)

/** Shortest distance from [p] to the polyline [pts] (a single point counts as a degenerate line). */
fun distanceToPolyline(p: ScreenPoint, pts: List<ScreenPoint>): Double {
    if (pts.isEmpty()) return Double.POSITIVE_INFINITY
    if (pts.size == 1) return hypot(p.x - pts[0].x, p.y - pts[0].y)
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        best = minOf(best, hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy)))
    }
    return best
}

/** One rendered segment under a tap: the named trails it belongs to and its screen distance from the tap (dp). */
data class SegmentHit(val trailIds: List<String>, val distanceDp: Double)

/** What a map tap does (ux.md §6.1). */
sealed interface TapResult {
    /** Nothing named under the finger: dismiss the sheet, clear the selection. */
    data object None : TapResult
    data class Select(val trailId: String) : TapResult
    /** Too close to call: let the user pick (nearest first). */
    data class Choose(val trailIds: List<String>) : TapResult
}

/** The nearest trail wins outright when it is at least this much closer than the next (dp). */
const val TAP_CLEAR_WINNER_DP = 12.0

/** Hit square side around the tap (ux.md §6.1: 44dp, i.e. 22dp tolerance). */
const val TAP_HIT_SIZE_DP = 44.0

/** Product F2 step 5: the overlap list shows at most this many trails. */
const val TAP_MAX_CHOICES = 4

/**
 * Ranks the named trails under a tap: each trail's distance is its nearest hit segment; ties break by name ([nameOf]).
 * One trail → select it; several → select the nearest when it is [TAP_CLEAR_WINNER_DP] closer than the runner-up,
 * else offer the (at most [TAP_MAX_CHOICES]) nearest.
 */
fun rankTap(hits: List<SegmentHit>, nameOf: (String) -> String): TapResult {
    val best = HashMap<String, Double>()
    for (h in hits) for (t in h.trailIds) {
        val d = best[t]
        if (d == null || h.distanceDp < d) best[t] = h.distanceDp
    }
    if (best.isEmpty()) return TapResult.None
    val ranked = best.entries.sortedWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { nameOf(it.key) }).map { it.key }
    if (ranked.size == 1) return TapResult.Select(ranked[0])
    val d0 = best.getValue(ranked[0])
    val d1 = best.getValue(ranked[1])
    return if (d1 - d0 >= TAP_CLEAR_WINNER_DP) TapResult.Select(ranked[0]) else TapResult.Choose(ranked.take(TAP_MAX_CHOICES))
}

/** MapLibre world width at zoom 0, in dp (512-px tiles). */
private const val WORLD_DP = 512.0

/**
 * Screen offset (dp) of [lon],[lat] from the tap point [tapLon],[tapLat] on a north-up, untilted map at [zoom]
 * (Web Mercator). Hit ranking projects hundreds of vertices this way instead of one native call each.
 */
fun mercatorOffsetDp(tapLon: Double, tapLat: Double, lon: Double, lat: Double, zoom: Double): ScreenPoint {
    val world = WORLD_DP * 2.0.pow(zoom)
    fun x(l: Double) = (l + 180.0) / 360.0
    fun y(p: Double): Double {
        val s = sin(p * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }
    return ScreenPoint((x(lon) - x(tapLon)) * world, (y(lat) - y(tapLat)) * world)
}
