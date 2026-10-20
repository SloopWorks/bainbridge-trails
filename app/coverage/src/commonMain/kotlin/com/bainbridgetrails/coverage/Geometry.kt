package com.bainbridgetrails.coverage

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Point in local metres: x = east, y = north. */
data class Xy(val x: Double, val y: Double)

/** Equirectangular projection about an origin; metres. */
class LocalProjection(
    private val originLon: Double = -122.53,
    private val originLat: Double = 47.63,
) {
    private val cosLat = cos(originLat * PI / 180.0)

    fun toXy(lon: Double, lat: Double): Xy = Xy(x(lon), y(lat))

    /** [toXy]'s x alone (no allocation). */
    internal fun x(lon: Double): Double = (lon - originLon) * DEG * cosLat * EARTH_R

    /** [toXy]'s y alone (no allocation). */
    internal fun y(lat: Double): Double = (lat - originLat) * DEG * EARTH_R

    /** Exact inverse of [toXy]: returns lon to lat. */
    fun toLonLat(p: Xy): Pair<Double, Double> =
        Pair(originLon + p.x / (DEG * cosLat * EARTH_R), originLat + p.y / (DEG * EARTH_R))

    private companion object {
        const val EARTH_R = 6_371_008.8
        const val DEG = PI / 180.0
    }
}

fun distanceToSegment(p: Xy, a: Xy, b: Xy): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Bearing 0..360, 0 = north (+y), clockwise. */
fun bearingDeg(a: Xy, b: Xy): Double = bearingDeg(a.x, a.y, b.x, b.y)

/** [bearingDeg] on raw coordinates (no [Xy] allocation). */
internal fun bearingDeg(ax: Double, ay: Double, bx: Double, by: Double): Double {
    val deg = atan2(bx - ax, by - ay) * 180.0 / PI
    val n = ((deg % 360.0) + 360.0) % 360.0
    return if (n >= 360.0) 0.0 else n
}

/** Undirected difference between two bearings, 0..90. */
fun axialDiffDeg(b1: Double, b2: Double): Double {
    val d = abs(b1 - b2) % 180.0
    return min(d, 180.0 - d)
}

/** [lengthM]: share of the line this tick stands for. */
data class Tick(val p: Xy, val bearingDeg: Double, val lengthM: Double)

/** Number of ticks [resample] puts on a line [totalM] long. */
internal fun ticksFor(totalM: Double, stepM: Double): Int = max(1, ceil(totalM / stepM - 1e-9).toInt())

/** Points every [stepM] along [line] at the centre of each step: offsets stepM/2, 3stepM/2, ...; always at least 1 point. */
fun resample(line: List<Xy>, stepM: Double): List<Tick> {
    require(line.size >= 2) { "line needs >= 2 points" }
    require(stepM > 0.0) { "stepM must be > 0" }
    val segLen = DoubleArray(line.size - 1) { hypot(line[it + 1].x - line[it].x, line[it + 1].y - line[it].y) }
    val total = segLen.sum()
    val n = ticksFor(total, stepM)
    val share = total / n
    val ticks = ArrayList<Tick>(n)
    var seg = 0
    var segStart = 0.0
    for (i in 0 until n) {
        val offset = (i + 0.5) * share
        while (seg < segLen.size - 1 && offset > segStart + segLen[seg]) {
            segStart += segLen[seg]
            seg++
        }
        val a = line[seg]
        val b = line[seg + 1]
        val t = if (segLen[seg] == 0.0) 0.0 else ((offset - segStart) / segLen[seg]).coerceIn(0.0, 1.0)
        ticks += Tick(Xy(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)), bearingDeg(a, b), share)
    }
    return ticks
}
