package com.bainbridgetrails.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** One polyline in chart units (height 1, width [IslandGeometry.aspect]); [xy] = x0,y0,x1,y1,… */
@Immutable
class ChartLine(val id: String, val xy: FloatArray, val counted: Boolean)

/**
 * The island drawn small (ux.md §3.7 hero): [land] = the coastline as closed rings (outer first, then holes),
 * [trails] = segments that can carry status (included, or on a named trail). Built once per dataset ([forDataset]).
 */
@Immutable
class IslandGeometry(val aspect: Float, val land: List<FloatArray>, val trails: List<ChartLine>) {
    companion object {
        /**
         * Projects lon/lat to an equirectangular plane at the island's mean latitude, normalizes to height 1 and
         * drops points closer than [tolerance] (chart units) to the previous kept one. [coastline] = rings of
         * [lon, lat] (scripts/build-coastline.sh); the frame covers coastline and segments together.
         */
        fun build(segments: List<SegmentGeom>, coastline: List<List<DoubleArray>> = emptyList(), tolerance: Float = 0.0015f): IslandGeometry {
            if (segments.isEmpty() && coastline.isEmpty()) return IslandGeometry(1f, emptyList(), emptyList())
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            fun extend(lon: Double, lat: Double) {
                minLon = min(minLon, lon); maxLon = max(maxLon, lon)
                minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            }
            for (s in segments) for (c in s.coords) extend(c[0], c[1])
            for (r in coastline) for (c in r) extend(c[0], c[1])
            val k = cos((minLat + maxLat) / 2 * PI / 180)
            val h = (maxLat - minLat).coerceAtLeast(1e-9)
            val aspect = ((maxLon - minLon) * k / h).toFloat()
            fun project(coords: List<DoubleArray>, keepLast: Boolean): FloatArray {
                val out = FloatArray(coords.size * 2)
                var n = 0
                var lx = Float.NaN; var ly = Float.NaN
                coords.forEachIndexed { i, c ->
                    val x = ((c[0] - minLon) * k / h).toFloat()
                    val y = ((maxLat - c[1]) / h).toFloat()
                    val last = keepLast && i == coords.lastIndex
                    if (lx.isNaN() || (last && (x != lx || y != ly)) || abs(x - lx) + abs(y - ly) >= tolerance) {
                        out[n++] = x; out[n++] = y; lx = x; ly = y
                    }
                }
                return out.copyOf(n)
            }
            val land = coastline.map { project(it, keepLast = false) }.filter { it.size >= 6 }
            val trails = ArrayList<ChartLine>()
            for (s in segments) {
                if (!s.included && s.trailIds.isEmpty()) continue
                trails += ChartLine(s.id, project(s.coords.map { doubleArrayOf(it[0], it[1]) }, keepLast = true), s.included)
            }
            return IslandGeometry(aspect, land, trails)
        }

        /** Parses coastline.json (`{"rings": [[[lon, lat], …], …]}`); empty on any error (the chart then shows trails only). */
        fun parseCoastline(json: String): List<List<DoubleArray>> = runCatching {
            Json.parseToJsonElement(json).jsonObject.getValue("rings").jsonArray.map { ring ->
                ring.jsonArray.map { p -> p.jsonArray.let { doubleArrayOf(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
            }
        }.getOrDefault(emptyList())

        private val lock = Mutex()
        private var cached: Pair<Dataset, IslandGeometry>? = null

        /**
         * The geometry for [dataset], built once per process (Me and onboarding re-enter composition on every visit):
         * reads coastline.json on IO and projects on Default, never on Main.
         */
        suspend fun forDataset(dataset: Dataset): IslandGeometry = lock.withLock {
            cached?.takeIf { it.first === dataset }?.second ?: run {
                val coast = withContext(Dispatchers.IO) { runCatching { Res.readBytes("files/coastline.json").decodeToString() }.getOrNull() }
                withContext(Dispatchers.Default) { build(dataset.segments, coast?.let(::parseCoastline).orEmpty()) }
                    .also { cached = dataset to it }
            }
        }
    }
}

/**
 * Me hero drawing (ux.md §3.7): the island's coastline filled in [land], ringed by three water lines (the Topo
 * coastline motif), with every counted segment as a polyline in its status color and map pattern (solid hairline /
 * dashed / solid with casing / thick). [water] must be the surface behind the chart (the rings are cut out of it).
 * [segmentStatus] = covered segments' own status (snapshot buckets); everything else is Not started.
 *
 * Drawn straight into the display list (rendered on the render thread), not into a software bitmap on Main: the
 * old bitmap path stroked every mapped segment 8× with ~30 dp round caps and blocked Main ~1.5 s per Me visit.
 */
@Composable
fun IslandChart(
    geometry: IslandGeometry,
    segmentStatus: Map<String, TrailStatus>,
    water: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    land: Color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
) {
    val colors = BtTheme.colors
    val ring = colors.waterLine
    val notCounted = colors.notCounted
    Spacer(
        modifier
            .clearAndSetSemantics { this.contentDescription = contentDescription }
            .drawWithCache {
                val ringW = 1.dp.toPx()
                val ringGap = 4.dp.toPx()
                val pad = 3 * ringGap + ringW + 2.dp.toPx()
                val scale = min((size.width - 2 * pad) / geometry.aspect, size.height - 2 * pad).coerceAtLeast(1f)
                val ox = (size.width - geometry.aspect * scale) / 2
                val oy = (size.height - scale) / 2
                fun pathOf(lines: Iterable<FloatArray>, closed: Boolean = false): Path = Path().apply {
                    for (xy in lines) {
                        if (xy.size < 2) continue
                        moveTo(ox + xy[0] * scale, oy + xy[1] * scale)
                        if (xy.size == 2) lineTo(ox + xy[0] * scale + 0.1f, oy + xy[1] * scale)
                        var i = 2
                        while (i < xy.size) { lineTo(ox + xy[i] * scale, oy + xy[i + 1] * scale); i += 2 }
                        if (closed) close()
                    }
                }
                val landPath = pathOf(geometry.land, closed = true)
                val byStatus = HashMap<TrailStatus, MutableList<FloatArray>>()
                val uncounted = ArrayList<FloatArray>()
                for (t in geometry.trails) {
                    val s = segmentStatus[t.id]
                    if (s == null && !t.counted) uncounted += t.xy else byStatus.getOrPut(s ?: TrailStatus.NOT_STARTED) { ArrayList() } += t.xy
                }
                val paths = TrailStatus.entries.associateWith { pathOf(byStatus[it].orEmpty()) }
                val uncountedPath = pathOf(uncounted)

                fun stroke(w: Float, effect: PathEffect? = null): Stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
                val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.5.dp.toPx()))
                val alphas = floatArrayOf(0.85f, 0.55f, 0.3f)
                val hasLand = geometry.land.isNotEmpty()
                onDrawBehind {
                    if (hasLand) {
                        // Water lines at ringGap, 2·ringGap, 3·ringGap off the coast: a stroke centred on the coastline
                        // reaches w/2 outside it, so ring i is a (2d + ringW) stroke with a (2d − ringW) water cut-out.
                        for (i in 2 downTo 0) {
                            val d = ringGap * (i + 1)
                            drawPath(landPath, ring.copy(alpha = alphas[i]), style = stroke(2 * d + ringW))
                            drawPath(landPath, water, style = stroke(2 * d - ringW))
                        }
                        drawPath(landPath, land)
                        drawPath(landPath, ring, style = stroke(ringW))
                    }
                    // Status lines, weakest first so progress sits on top (map width hierarchy, ux.md §0).
                    drawPath(uncountedPath, notCounted, style = stroke(0.8.dp.toPx()))
                    drawPath(paths.getValue(TrailStatus.NOT_STARTED), colors.notStarted.line, style = stroke(1.2.dp.toPx()))
                    drawPath(paths.getValue(TrailStatus.PARTWAY), colors.partway.line, style = stroke(1.8.dp.toPx(), dashed))
                    drawPath(paths.getValue(TrailStatus.NEARLY_DONE), colors.nearlyDoneCasing, style = stroke(3.4.dp.toPx()))
                    drawPath(paths.getValue(TrailStatus.NEARLY_DONE), colors.nearlyDone.line, style = stroke(2.dp.toPx()))
                    drawPath(paths.getValue(TrailStatus.COMPLETE), colors.complete.line, style = stroke(2.8.dp.toPx()))
                }
            },
    )
}

/** Status → segment ids flattened for [IslandChart] (snapshot buckets are status → ids). */
fun Map<TrailStatus, List<String>>.bySegment(): Map<String, TrailStatus> = buildMap {
    for ((s, ids) in this@bySegment) for (id in ids) put(id, s)
}
