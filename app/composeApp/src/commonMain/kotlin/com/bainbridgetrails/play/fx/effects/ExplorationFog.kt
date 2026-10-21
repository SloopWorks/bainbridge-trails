package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxGeoJson
import com.bainbridgetrails.play.geo.ChartSquare
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `exploration_fog` (effects §3.18, ux §3.5.3): one `UNDER_STATUS` FillLayer of `play.fog` over every chart square the
 * user has no counted coverage in, under the status halos, never inside a quiet zone. Each fogged square is one
 * instance (one polygon); a square touching a quiet zone is split into a [CELLS]×[CELLS] grid and only the cells clear of
 * every zone stay, as per-row rectangles. Static (no drift); the provisional lift is feature state `lifting` → α .25.
 */
object ExplorationFog : FxRenderer {
    override val kind: String = "exploration_fog"
    const val CELLS = 4
    private val FILL = FxLayerIds.of(kind, "fill")
    val layerIds: List<String> = listOf(FILL)
    private val timeline = loopTimeline(60_000)

    /** Fog specs for [squares] minus [lifted] (square ids), cut out of every [quiet] polygon (lon/lat rings). */
    fun specs(squares: Collection<ChartSquare>, lifted: Set<String>, quiet: List<List<GeoPoint>>): List<FxSpec> {
        val out = ArrayList<FxSpec>()
        for (sq in squares.sortedWith(compareBy({ it.row }, { it.col }))) {
            if (sq.id in lifted) continue
            val sw = sq.southWest
            val dLat = ChartSquare.D_LAT / CELLS
            val dLon = ChartSquare.D_LON / CELLS
            fun cellClear(r: Int, c: Int) = quiet.none { touches(sw.lon + c * dLon, sw.lat + r * dLat, dLon, dLat, it) }
            val keep = Array(CELLS) { r -> BooleanArray(CELLS) { c -> cellClear(r, c) } }
            var k = 0
            fun add(lon0: Double, lat0: Double, w: Double, h: Double) {
                val ring = listOf(GeoPoint(lon0, lat0), GeoPoint(lon0 + w, lat0), GeoPoint(lon0 + w, lat0 + h), GeoPoint(lon0, lat0 + h), GeoPoint(lon0, lat0))
                out += FxSpec("fog:${sq.id}#${k++}", kind, FxAnchor.Area(listOf(ring)), 0.0..22.0, FxBand.UNDER_STATUS, FxPriority.AMBIENT, timeline, loop = true)
            }
            if (keep.all { row -> row.all { it } }) {
                add(sw.lon, sw.lat, ChartSquare.D_LON, ChartSquare.D_LAT)
                continue
            }
            for (r in 0 until CELLS) {
                var c = 0
                while (c < CELLS) {
                    if (!keep[r][c]) { c++; continue }
                    val start = c
                    while (c < CELLS && keep[r][c]) c++
                    add(sw.lon + start * dLon, sw.lat + r * dLat, (c - start) * dLon, dLat)
                }
            }
        }
        return out
    }

    /** The chart square id a fog spec covers. */
    fun squareOf(spec: FxSpec): String = spec.id.removePrefix("fog:").substringBefore('#')

    /** The rectangle intersects or contains [poly], or lies inside it. */
    internal fun touches(lon0: Double, lat0: Double, w: Double, h: Double, poly: List<GeoPoint>): Boolean {
        val lon1 = lon0 + w
        val lat1 = lat0 + h
        if (poly.any { it.lon in lon0..lon1 && it.lat in lat0..lat1 }) return true
        val corners = listOf(GeoPoint(lon0, lat0), GeoPoint(lon1, lat0), GeoPoint(lon1, lat1), GeoPoint(lon0, lat1))
        if (corners.any { inside(it, poly) }) return true
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            for (j in poly.indices) if (cross(a, b, poly[j], poly[(j + 1) % poly.size])) return true
        }
        return false
    }

    private fun inside(p: GeoPoint, poly: List<GeoPoint>): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) c = !c
            j = i
        }
        return c
    }

    private fun cross(p1: GeoPoint, p2: GeoPoint, q1: GeoPoint, q2: GeoPoint): Boolean {
        fun o(a: GeoPoint, b: GeoPoint, c: GeoPoint) = (b.lon - a.lon) * (c.lat - a.lat) - (b.lat - a.lat) * (c.lon - a.lon)
        val d1 = o(q1, q2, p1)
        val d2 = o(q1, q2, p2)
        val d3 = o(p1, p2, q1)
        val d4 = o(p1, p2, q2)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        if (band != FxBand.UNDER_STATUS) return
        val json = remember(live) { fxGeoJson(live) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        FillLayer(
            id = FILL, source = source,
            color = const(ctx.palette.fog), antialias = const(false),
            opacity = switch(condition(feature.state("lifting").asBoolean(const(false)), const(0.25f)), fallback = const(1f)),
            opacityTransition = TransitionOptions(duration = if (ctx.reduced) Duration.ZERO else 900.milliseconds),
        )
    }
}
