package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxClockPolicy
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * `ferry_crossing` (effects §3.3, plan §2): the boat (`OVER_STATUS`) and its 400 m wake (`UNDER_STATUS`, `play.fog`
 * foam fading to the stern via `line-gradient`) on the timetable run, in one GeoJSON source shared by both bands
 * ([rememberSource]). The position is advanced only by the FxHost clock: from [writeGlobalState] at ≤ 1 Hz of real
 * time ([UpdateGate] on [elapsedMs]) while the clock runs, so it stops with every other part (idle, recording,
 * background); to "now" at rest ([writeRestState]), which FxHost also calls once a minute under reduced motion / power
 * save (product PF3-4, [FxClockPolicy.REDUCED_TICK_MS]). No coroutine of its own. Only the island leg inside the camera
 * bounds is drawn (route baked from the basemap's OSM ferry line; L4 bakes it into Play content later). Hidden when the
 * timetable is expired. [now] is the Play clock (the showcase passes a time-warped one). One instance per registry.
 */
class FerryCrossing(
    private val timetable: FerryTimetable?,
    private val route: Route = Route.Default,
    private val now: () -> Instant,
    private val elapsedMs: () -> Long = MonotonicMs(),
) : FxRenderer {
    override val kind: String = KIND
    private val gate = UpdateGate(INTERVAL_MS)

    /** The boats + wakes FeatureCollection; changes only when the host advances it. */
    var json: String by mutableStateOf(geoJson(boats(timetable, route, now()), route))
        private set

    private fun advance() {
        val next = geoJson(boats(timetable, route, now()), route)
        if (next != json) json = next
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        if (live.none { !it.reduced }) return
        if (gate.due(elapsedMs())) advance()
    }

    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) = advance()

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource =
        rememberGeoJsonSource(GeoJsonData.JsonString(json)) { lineMetrics = true }

    private class MonotonicMs : () -> Long {
        private val mark = TimeSource.Monotonic.markNow()

        override fun invoke(): Long = mark.elapsedNow().inWholeMilliseconds
    }

    /** The island leg ([leg], terminal slip first, lon/lat) and the whole route length to Seattle ([totalM]). */
    class Route(val leg: List<GeoPoint>, val totalM: Double) {
        val legM: DoubleArray = DoubleArray(leg.size).also { d -> for (i in 1 until leg.size) d[i] = d[i - 1] + metres(leg[i - 1], leg[i]) }
        val legLengthM: Double get() = legM.last()

        /** Point and bearing (deg, 0 = north) at [distanceM] from the slip along the leg. */
        fun at(distanceM: Double): Pair<GeoPoint, Double> {
            val d = distanceM.coerceIn(0.0, legLengthM)
            var i = 1
            while (i < leg.lastIndex && legM[i] < d) i++
            val a = leg[i - 1]
            val b = leg[i]
            val seg = (legM[i] - legM[i - 1]).coerceAtLeast(1e-9)
            val u = ((d - legM[i - 1]) / seg).coerceIn(0.0, 1.0)
            val p = if (d <= 0.0) leg.first() else GeoPoint(a.lon + (b.lon - a.lon) * u, a.lat + (b.lat - a.lat) * u)
            return p to bearing(a, b)
        }

        /** The leg between [from] and [to] metres (for the wake). */
        fun slice(from: Double, to: Double): List<GeoPoint> {
            val out = ArrayList<GeoPoint>()
            out += at(from).first
            for (i in leg.indices) if (legM[i] > from && legM[i] < to) out += leg[i]
            out += at(to).first
            return out
        }

        companion object {
            private val SEATTLE = GeoPoint(-122.3393, 47.6026)

            /** Basemap OSM ferry line (one direction), Winslow slip → east edge of the camera bounds. */
            val Default: Route = listOf(
                -122.509403 to 47.622505, -122.508271 to 47.622382, -122.507038 to 47.622114, -122.502633 to 47.621406,
                -122.501094 to 47.621135, -122.499157 to 47.620737, -122.498149 to 47.620364, -122.497376 to 47.619934,
                -122.496502 to 47.619207, -122.495987 to 47.618506, -122.495306 to 47.617649, -122.493836 to 47.614875,
                -122.492328 to 47.61212, -122.491427 to 47.610738, -122.490161 to 47.609458, -122.488455 to 47.608543,
                -122.486503 to 47.608008, -122.484545 to 47.607794, -122.475243 to 47.607472,
            ).map { (lon, lat) -> GeoPoint(lon, lat) }.let { leg ->
                val r = Route(leg, 0.0)
                Route(leg, r.legLengthM + metres(leg.last(), SEATTLE))
            }
        }
    }

    /** A boat to draw: [distanceM] from the slip; [outbound] = leaving Bainbridge. */
    data class Boat(val at: GeoPoint, val bearingDeg: Double, val distanceM: Double, val outbound: Boolean, val docked: Boolean)

    /** At most one update per [intervalMs] (1 Hz; [REDUCED_INTERVAL_MS] under reduced motion). */
    class UpdateGate(val intervalMs: Long = INTERVAL_MS) {
        private var last = Long.MIN_VALUE

        fun due(nowMs: Long): Boolean {
            if (last != Long.MIN_VALUE && nowMs - last < intervalMs) return false
            last = nowMs
            return true
        }
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.OVER_STATUS && band != FxBand.UNDER_STATUS) return
        if (source == null) return
        val p = ctx.palette
        val kindIs = feature["k"].asString(const(""))
        if (band == FxBand.UNDER_STATUS) {
            LineLayer(
                id = WAKE, source = source, filter = kindIs eq const("wake"), minZoom = 12.5f,
                gradient = interpolate(linear(), feature.lineProgress(), 0 to const(p.fog.copy(alpha = 0f)), 1 to const(p.fog)),
                width = interpolate(linear(), zoom(), 12.5 to const(1.dp), 14 to const(3.dp), 17 to const(6.dp)),
                cap = const(LineCap.Round),
            )
            return
        }
        val fig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.Ferry, p.ink) })
        val east = remember(p, fig) { boatPainter(fig, p.paper, mirror = false) }
        val west = remember(p, fig) { boatPainter(fig, p.paper, mirror = true) }
        SymbolLayer(
            id = BOAT, source = source, filter = kindIs eq const("boat"),
            iconImage = switch(
                feature["dir"].asString(const("e")),
                case("w", image(west, DpSize(32.dp, 32.dp))),
                fallback = image(east, DpSize(32.dp, 32.dp)),
            ),
            iconRotate = feature["rot"].asNumber(const(0f)),
            iconSize = interpolate(exponential(1.5f), zoom(), 10.5 to const(0.35f), 12.5 to const(0.6f), 14 to const(1f), 16 to const(1f), 18 to const(1.4f)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
        )
    }

    private fun boatPainter(fig: androidx.compose.ui.graphics.painter.Painter, paper: Color, mirror: Boolean) = FxDrawPainter(32f, 32f) {
        scale(if (mirror) -1f else 1f, 1f, pivot = androidx.compose.ui.geometry.Offset(16f, 16f)) {
            val hull = androidx.compose.ui.graphics.Path().apply {
                moveTo(4f, 19.3f); lineTo(28f, 19.3f); lineTo(25.3f, 24.7f); lineTo(6.7f, 24.7f); close()
                moveTo(8f, 19.3f); lineTo(8f, 14.7f); lineTo(23.3f, 14.7f); lineTo(23.3f, 19.3f); close()
            }
            drawPath(hull, paper)
            with(fig) { draw(Size(32f, 32f)) }
        }
    }

    companion object {
        const val KIND = "ferry_crossing"
        const val INTERVAL_MS = 1_000L
        const val REDUCED_INTERVAL_MS = FxClockPolicy.REDUCED_TICK_MS
        const val WAKE_M = 400.0
        private const val DOCK_GAP_MS = 60L * 60_000L
        private val BOAT = FxLayerIds.of(KIND, "boat")
        private val WAKE = FxLayerIds.of(KIND, "wake")
        val layerIds: List<String> = listOf(WAKE, BOAT)

        fun spec(id: String = "ferry-crossing", route: Route = Route.Default): FxSpec =
            FxSpec(id, KIND, FxAnchor.Line(route.leg), 10.5..22.0, FxBand.OVER_STATUS, FxPriority.AMBIENT, loopTimeline(60_000), loop = true)

        /** Boats on the island leg at [now] (empty when expired, between runs, or out on the Sound). */
        fun boats(tt: FerryTimetable?, route: Route, now: Instant): List<Boat> {
            if (tt == null) return emptyList()
            val crossMs = tt.crossingMin * 60_000L
            val today = IslandTime.dateOf(now)
            val out = ArrayList<Boat>()
            var lastArrive: Instant? = null
            var nextDepart: Instant? = null
            for (d in listOf(today.minus(1, DateTimeUnit.DAY), today)) {
                tt.departures(d, FerryTimetable.Terminal.BAINBRIDGE)?.forEach { s ->
                    val ms = (now - s.depart).inWholeMilliseconds
                    if (ms in 0 until crossMs) out += boat(route, ms.toDouble() / crossMs * route.totalM, outbound = true)
                    if (s.depart > now && (nextDepart == null || s.depart < nextDepart!!)) nextDepart = s.depart
                }
                tt.departures(d, FerryTimetable.Terminal.SEATTLE)?.forEach { s ->
                    val ms = (s.arrive - now).inWholeMilliseconds
                    if (ms in 1..crossMs) out += boat(route, ms.toDouble() / crossMs * route.totalM, outbound = false)
                    if (s.arrive <= now && (lastArrive == null || s.arrive > lastArrive!!)) lastArrive = s.arrive
                }
            }
            val visible = out.filter { it.distanceM <= route.legLengthM }
            if (out.isEmpty()) {
                val a = lastArrive
                val dpt = nextDepart
                if (a != null && dpt != null && (dpt - a).inWholeMilliseconds <= DOCK_GAP_MS) {
                    val (p, b) = route.at(0.0)
                    return listOf(Boat(p, (b + 180.0) % 360.0, 0.0, outbound = false, docked = true))
                }
            }
            return visible
        }

        private fun boat(route: Route, distanceM: Double, outbound: Boolean): Boat {
            val (p, b) = route.at(distanceM)
            return Boat(p, if (outbound) b else (b + 180.0) % 360.0, distanceM, outbound, docked = false)
        }

        /** Boats as Points (`rot`, `dir`) and wakes as LineStrings (tail → stern, for the gradient). */
        fun geoJson(boats: List<Boat>, route: Route): String = buildString {
            append("""{"type":"FeatureCollection","features":[""")
            var first = true
            fun sep() { if (!first) append(','); first = false }
            for (b in boats) {
                val east = b.bearingDeg in 0.0..180.0
                val rot = if (east) b.bearingDeg - 90.0 else b.bearingDeg - 270.0
                sep()
                append("""{"type":"Feature","properties":{"k":"boat","dir":"""").append(if (east) "e" else "w")
                append("""","rot":""").append(rot).append("""},"geometry":{"type":"Point","coordinates":[""")
                append(b.at.lon).append(',').append(b.at.lat).append("]}}")
                if (!b.docked) {
                    val wake = if (b.outbound) route.slice((b.distanceM - WAKE_M).coerceAtLeast(0.0), b.distanceM)
                    else route.slice(b.distanceM, (b.distanceM + WAKE_M).coerceAtMost(route.legLengthM)).asReversed()
                    if (wake.size >= 2) {
                        sep()
                        append("""{"type":"Feature","properties":{"k":"wake"},"geometry":{"type":"LineString","coordinates":[""")
                        wake.forEachIndexed { i, p -> if (i > 0) append(','); append('[').append(p.lon).append(',').append(p.lat).append(']') }
                        append("]}}")
                    }
                }
            }
            append("]}")
        }

        internal fun metres(a: GeoPoint, b: GeoPoint): Double {
            val k = 111_195.0
            return hypot((a.lon - b.lon) * k * cos(a.lat * PI / 180.0), (a.lat - b.lat) * k)
        }

        internal fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val dx = (b.lon - a.lon) * cos(a.lat * PI / 180.0)
            val dy = b.lat - a.lat
            return (atan2(dx, dy) * 180.0 / PI + 360.0) % 360.0
        }
    }
}
