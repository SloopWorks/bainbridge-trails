package com.bainbridgetrails.play.geo

import kotlin.math.roundToLong

/**
 * The chart square: Play's one grid (README glossary, PD-10), 0.01° lat × 0.015° lon anchored at 47.55 N, 122.62 W.
 * [row] counts squares north of the anchor, [col] squares east of it. Arithmetic is in integer micro-degrees (as the
 * pack build does: `floor((round(lat·1e6) − 47 550 000) / 10 000)`), so a coordinate on a grid line always lands in
 * the square to its north-east on every platform.
 */
data class ChartSquare(val row: Int, val col: Int) {
    /** `"row:col"`, the id used in the pack's aggregates and derived files. */
    val id: String get() = "$row:$col"

    val southWest: GeoPoint
        get() = GeoPoint(
            lat = (LAT0_MICRO + row.toLong() * D_LAT_MICRO) / 1e6,
            lon = (LON0_MICRO + col.toLong() * D_LON_MICRO) / 1e6,
        )

    companion object {
        const val LAT0 = 47.55
        const val LON0 = -122.62
        const val D_LAT = 0.01
        const val D_LON = 0.015
        private const val LAT0_MICRO = 47_550_000L
        private const val LON0_MICRO = -122_620_000L
        private const val D_LAT_MICRO = 10_000L
        private const val D_LON_MICRO = 15_000L

        fun of(lat: Double, lon: Double): ChartSquare = ChartSquare(
            ((lat * 1e6).roundToLong() - LAT0_MICRO).floorDiv(D_LAT_MICRO).toInt(),
            ((lon * 1e6).roundToLong() - LON0_MICRO).floorDiv(D_LON_MICRO).toInt(),
        )

        fun of(p: GeoPoint): ChartSquare = of(p.lat, p.lon)

        /** Parses `"row:col"`; null when malformed. */
        fun parse(id: String): ChartSquare? {
            val parts = id.split(':')
            if (parts.size != 2) return null
            return ChartSquare(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
        }

        /** The squares any vertex of [lines] falls in (the goal's squares: 62 trails / 83 roads on today's data). */
        fun covering(lines: Iterable<List<GeoPoint>>): Set<ChartSquare> {
            val out = HashSet<ChartSquare>()
            for (line in lines) for (p in line) out += of(p)
            return out
        }
    }
}
