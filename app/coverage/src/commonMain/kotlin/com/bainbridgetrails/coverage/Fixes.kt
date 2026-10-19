package com.bainbridgetrails.coverage

import kotlin.math.hypot

data class Fix(
    val timeMs: Long,
    val lon: Double,
    val lat: Double,
    val accuracyM: Double? = null,
    val isMock: Boolean = false,
)

private const val MIN_SPEED_BASIS_S = 10.0

/** Continuous runs of accepted fixes, in time order. Lines are drawn only within a piece. */
fun filterFixes(fixes: List<Fix>, params: CoverageParams, proj: LocalProjection): List<List<Xy>> {
    val kept = fixes.sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .filter { !it.isMock && (it.accuracyM ?: 0.0) <= params.maxAccuracyM }

    val pieces = ArrayList<List<Xy>>()
    var times = ArrayList<Long>()
    var pts = ArrayList<Xy>()

    fun flush() {
        if (pts.size >= 2) pieces.add(pts)
        times = ArrayList()
        pts = ArrayList()
    }

    for (f in kept) {
        val p = proj.toXy(f.lon, f.lat)
        if (pts.isNotEmpty()) {
            val last = pts.last()
            val gapS = (f.timeMs - times.last()) / 1000.0
            if (gapS > params.maxGapS || hypot(p.x - last.x, p.y - last.y) > params.maxJumpM) flush()
        }
        if (pts.isNotEmpty()) {
            // latest fix at least speedWindowS back; else piece start if >= 10 s elapsed
            var j = -1
            for (k in times.indices.reversed()) {
                if ((f.timeMs - times[k]) / 1000.0 >= params.speedWindowS) { j = k; break }
            }
            if (j < 0 && (f.timeMs - times[0]) / 1000.0 >= MIN_SPEED_BASIS_S) j = 0
            if (j >= 0) {
                val dt = (f.timeMs - times[j]) / 1000.0
                val speed = hypot(p.x - pts[j].x, p.y - pts[j].y) / dt
                if (speed > params.maxSpeedMps) {
                    flush()
                    continue
                }
            }
        }
        times.add(f.timeMs)
        pts.add(p)
    }
    flush()
    return pieces
}
