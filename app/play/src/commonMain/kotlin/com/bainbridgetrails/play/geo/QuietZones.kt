package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.Place
import com.bainbridgetrails.play.config.QuietZone
import com.bainbridgetrails.play.schedule.GeoPoint
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.PlaceRef
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.TrailRef
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Island-scale geometry in local metres: an equirectangular projection about the island centre (the same model as
 * `:coverage`'s `LocalProjection`; error < 0.1 % over the island).
 */
object GeoMath {
    private const val EARTH_R = 6_371_008.8
    private const val DEG = PI / 180.0
    private const val ORIGIN_LAT = 47.63
    private const val ORIGIN_LON = -122.53
    private val COS_LAT = cos(ORIGIN_LAT * DEG)
    val M_PER_DEG_LAT = DEG * EARTH_R
    val M_PER_DEG_LON = DEG * EARTH_R * COS_LAT

    fun x(lon: Double): Double = (lon - ORIGIN_LON) * M_PER_DEG_LON
    fun y(lat: Double): Double = (lat - ORIGIN_LAT) * M_PER_DEG_LAT

    fun distanceM(a: GeoPoint, b: GeoPoint): Double = hypot(x(a.lon) - x(b.lon), y(a.lat) - y(b.lat))

    /** [p] moved [eastM] east and [northM] north. */
    fun offset(p: GeoPoint, eastM: Double, northM: Double): GeoPoint =
        GeoPoint(p.lat + northM / M_PER_DEG_LAT, p.lon + eastM / M_PER_DEG_LON)

    fun lineLengthM(line: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until line.size - 1) total += distanceM(line[i], line[i + 1])
        return total
    }

    /** The point at half of [line]'s length (its only vertex when it has one). */
    fun halfLengthPoint(line: List<GeoPoint>): GeoPoint {
        require(line.isNotEmpty()) { "empty line" }
        val half = lineLengthM(line) / 2
        var acc = 0.0
        for (i in 0 until line.size - 1) {
            val d = distanceM(line[i], line[i + 1])
            if (acc + d >= half && d > 0) {
                val t = (half - acc) / d
                return GeoPoint(line[i].lat + t * (line[i + 1].lat - line[i].lat), line[i].lon + t * (line[i + 1].lon - line[i].lon))
            }
            acc += d
        }
        return line.first()
    }

    /** Shortest distance from [p] to [line] (a single vertex counts as a point). */
    fun pointToLineM(p: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size == 1) return distanceM(p, line[0])
        val px = x(p.lon)
        val py = y(p.lat)
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            best = min(best, pointToSegment(px, py, x(line[i].lon), y(line[i].lat), x(line[i + 1].lon), y(line[i + 1].lat)))
        }
        return best
    }

    /** Shortest distance between two polylines (0 when they cross). */
    fun lineToLineM(a: List<GeoPoint>, b: List<GeoPoint>): Double {
        if (a.size >= 2 && b.size >= 2 && crosses(a, b)) return 0.0
        var best = Double.MAX_VALUE
        for (p in a) best = min(best, pointToLineM(p, b))
        for (p in b) best = min(best, pointToLineM(p, a))
        return best
    }

    private fun crosses(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
        for (i in 0 until a.size - 1) for (j in 0 until b.size - 1) {
            if (segmentsCross(a[i], a[i + 1], b[j], b[j + 1])) return true
        }
        return false
    }

    private fun segmentsCross(p1: GeoPoint, p2: GeoPoint, q1: GeoPoint, q2: GeoPoint): Boolean {
        fun orient(a: GeoPoint, b: GeoPoint, c: GeoPoint): Double =
            (x(b.lon) - x(a.lon)) * (y(c.lat) - y(a.lat)) - (y(b.lat) - y(a.lat)) * (x(c.lon) - x(a.lon))
        val d1 = orient(q1, q2, p1)
        val d2 = orient(q1, q2, p2)
        val d3 = orient(p1, p2, q1)
        val d4 = orient(p1, p2, q2)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/**
 * Quiet zones (PD-6, product §4.6.3, schedule.md §3.4): buffers of [Zone.bufferM] around every segment of a dataset
 * feature (or around an owner-tagged place), built from geometry, never typed circles. Inside one Play places, shows
 * and awards nothing except the remembrance card; a pool member that touches one is rejected.
 */
class QuietZones(val zones: List<Zone>) {

    /** [lines]: the source geometry (a place zone is one single-vertex line). */
    class Zone(val id: String, val feature: String, val bufferM: Double, val lines: List<List<GeoPoint>>) {
        private val minLat: Double
        private val maxLat: Double
        private val minLon: Double
        private val maxLon: Double

        init {
            var a = Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            var c = Double.MAX_VALUE
            var d = -Double.MAX_VALUE
            for (l in lines) for (p in l) {
                a = min(a, p.lat); b = max(b, p.lat); c = min(c, p.lon); d = max(d, p.lon)
            }
            val padLat = bufferM / GeoMath.M_PER_DEG_LAT
            val padLon = bufferM / GeoMath.M_PER_DEG_LON
            minLat = a - padLat; maxLat = b + padLat; minLon = c - padLon; maxLon = d + padLon
        }

        private fun near(p: GeoPoint) = p.lat in minLat..maxLat && p.lon in minLon..maxLon

        private fun near(line: List<GeoPoint>): Boolean {
            var a = Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            var c = Double.MAX_VALUE
            var d = -Double.MAX_VALUE
            for (p in line) {
                a = min(a, p.lat); b = max(b, p.lat); c = min(c, p.lon); d = max(d, p.lon)
            }
            return b >= minLat && a <= maxLat && d >= minLon && c <= maxLon
        }

        /** [p] is within [bufferM] of the source geometry. */
        fun contains(p: GeoPoint): Boolean = near(p) && lines.any { GeoMath.pointToLineM(p, it) <= bufferM }

        /** Some part of [line] is within [bufferM] of the source geometry. */
        fun touches(line: List<GeoPoint>): Boolean =
            line.isNotEmpty() && near(line) && lines.any { GeoMath.lineToLineM(line, it) <= bufferM }

        override fun toString(): String = "Zone($id, $feature, ${bufferM}m, ${lines.size} lines)"
    }

    /** The first zone containing [p], or null. */
    fun zoneAt(p: GeoPoint): Zone? = zones.firstOrNull { it.contains(p) }

    /** The first zone any of [segments] touches, or null. */
    fun touching(segments: List<com.bainbridgetrails.play.schedule.SegRef>): Zone? =
        zones.firstOrNull { z -> segments.any { z.touches(it.line) } }

    /**
     * Segment ids Play treats as quiet: every segment of [all] touching a zone, plus [quietFeatureSegments] (the quiet
     * features themselves: the zone features and, for trails, every trail of an excluded park). 55 trails / 62 roads
     * segments on today's data.
     */
    fun quietSegmentIds(
        all: Iterable<com.bainbridgetrails.play.schedule.SegRef>,
        quietFeatureSegments: Iterable<com.bainbridgetrails.play.schedule.SegRef>,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in all) if (zones.any { it.touches(s.line) }) out += s.id
        for (s in quietFeatureSegments) out += s.id
        return out
    }

    /** [unresolved]: specs whose feature is malformed or unknown (the validator reports them). */
    class Built(val zones: List<Zone>, val unresolved: List<QuietZone>)

    companion object {
        /** Builds the zones of [specs]: `trail:<id>` from its segments in [index], `place:<id>` from [places]. */
        fun build(specs: List<QuietZone>, places: Map<String, Place>, index: PlaceIndex): Built {
            val zones = ArrayList<Zone>()
            val unresolved = ArrayList<QuietZone>()
            for (spec in specs) {
                val lines: List<List<GeoPoint>> = when (val ref = Ref.parse(spec.feature)) {
                    is TrailRef -> index.segments(ref).map { it.line }.filter { it.isNotEmpty() }
                    is PlaceRef -> places[ref.id]?.let { listOf(listOf(GeoPoint(it.lat, it.lon))) }.orEmpty()
                    else -> emptyList()
                }
                if (lines.isEmpty() || spec.bufferM <= 0) unresolved += spec else zones += Zone(spec.id, spec.feature, spec.bufferM.toDouble(), lines)
            }
            return Built(zones, unresolved)
        }
    }
}
