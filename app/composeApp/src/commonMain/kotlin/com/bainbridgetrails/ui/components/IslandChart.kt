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
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.ui.theme.BtTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** One polyline in chart units (height 1, width [IslandGeometry.aspect]); [xy] = x0,y0,x1,y1,… */
@Immutable
class ChartLine(val id: String, val xy: FloatArray, val counted: Boolean)

/**
 * The island drawn small (ux.md §3.7 hero): [land] = every mapped segment (its fat-stroked union reads as the island's
 * outline), [trails] = segments that can carry status (included, or on a named trail). Built once per dataset.
 */
@Immutable
class IslandGeometry(val aspect: Float, val land: List<FloatArray>, val trails: List<ChartLine>) {
    companion object {
        /**
         * Projects lon/lat to an equirectangular plane at the island's mean latitude, normalizes to height 1 and
         * drops points closer than [tolerance] (chart units) to the previous kept one.
         */
        fun build(segments: List<SegmentGeom>, tolerance: Float = 0.0015f): IslandGeometry {
            if (segments.isEmpty()) return IslandGeometry(1f, emptyList(), emptyList())
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            for (s in segments) for (c in s.coords) {
                minLon = min(minLon, c[0]); maxLon = max(maxLon, c[0])
                minLat = min(minLat, c[1]); maxLat = max(maxLat, c[1])
            }
            val k = cos((minLat + maxLat) / 2 * PI / 180)
            val h = (maxLat - minLat).coerceAtLeast(1e-9)
            val aspect = ((maxLon - minLon) * k / h).toFloat()
            fun project(s: SegmentGeom): FloatArray {
                val out = ArrayList<Float>(s.coords.size * 2)
                var lx = Float.NaN; var ly = Float.NaN
                s.coords.forEachIndexed { i, c ->
                    val x = ((c[0] - minLon) * k / h).toFloat()
                    val y = ((maxLat - c[1]) / h).toFloat()
                    val last = i == s.coords.lastIndex
                    if (lx.isNaN() || last || abs(x - lx) + abs(y - ly) >= tolerance) {
                        out += x; out += y; lx = x; ly = y
                    }
                }
                return out.toFloatArray()
            }
            val land = ArrayList<FloatArray>(segments.size)
            val trails = ArrayList<ChartLine>()
            for (s in segments) {
                val xy = project(s)
                land += xy
                if (s.included || s.trailIds.isNotEmpty()) trails += ChartLine(s.id, xy, s.included)
            }
            return IslandGeometry(aspect, land, trails)
        }
    }
}

/**
 * Me hero drawing (ux.md §3.7): the island as the union of all mapped paths, ringed by three water lines (the Topo
 * coastline motif), with every counted segment in its own status color and map pattern (dotted / dashed / solid with
 * casing / thick). [water] must be the surface behind the chart (the rings are cut out of it).
 * [segmentStatus] = covered segments' own status (snapshot buckets); everything else is Not started.
 */
@Composable
fun IslandChart(
    geometry: IslandGeometry,
    segmentStatus: Map<String, TrailStatus>,
    water: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = BtTheme.colors
    val land = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val ring = colors.waterLine
    val notCounted = colors.notCounted
    Spacer(
        modifier
            .clearAndSetSemantics { this.contentDescription = contentDescription }
            .drawWithCache {
                val ringW = 1.dp.toPx()
                val landW = 7.dp.toPx()
                val ringGap = 4.dp.toPx()
                val pad = landW / 2 + 3 * ringGap + ringW
                val scale = min((size.width - 2 * pad) / geometry.aspect, size.height - 2 * pad).coerceAtLeast(1f)
                val ox = (size.width - geometry.aspect * scale) / 2
                val oy = (size.height - scale) / 2
                fun pathOf(lines: Iterable<FloatArray>): Path = Path().apply {
                    for (xy in lines) {
                        if (xy.size < 2) continue
                        moveTo(ox + xy[0] * scale, oy + xy[1] * scale)
                        if (xy.size == 2) lineTo(ox + xy[0] * scale + 0.1f, oy + xy[1] * scale)
                        var i = 2
                        while (i < xy.size) {
                            lineTo(ox + xy[i] * scale, oy + xy[i + 1] * scale); i += 2
                        }
                    }
                }
                val landPath = pathOf(geometry.land)
                val byStatus = HashMap<TrailStatus, MutableList<FloatArray>>()
                val uncounted = ArrayList<FloatArray>()
                for (t in geometry.trails) {
                    val s = segmentStatus[t.id]
                    if (s == null && !t.counted) uncounted += t.xy else byStatus.getOrPut(s ?: TrailStatus.NOT_STARTED) { ArrayList() } += t.xy
                }
                val paths = TrailStatus.entries.associateWith { pathOf(byStatus[it].orEmpty()) }
                val uncountedPath = pathOf(uncounted)

                fun stroke(w: Float, effect: PathEffect? = null): Stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
                val dotted = PathEffect.dashPathEffect(floatArrayOf(0.01f, 3.dp.toPx()))
                val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.5.dp.toPx()))
                val alphas = floatArrayOf(0.85f, 0.55f, 0.3f)
                onDrawBehind {
                    // Water lines: offset outlines of the land union, outermost first, each cut back to water.
                    for (i in 2 downTo 0) {
                        val w = landW + 2 * ringGap * (i + 1)
                        drawPath(landPath, ring.copy(alpha = alphas[i]), style = stroke(w + ringW * 2))
                        drawPath(landPath, water, style = stroke(w))
                    }
                    drawPath(landPath, ring, style = stroke(landW + ringW * 2))
                    drawPath(landPath, land, style = stroke(landW))
                    // Status lines, weakest first so progress sits on top (map width hierarchy, ux.md §0).
                    drawPath(uncountedPath, notCounted, style = stroke(1.dp.toPx()))
                    drawPath(paths.getValue(TrailStatus.NOT_STARTED), colors.notStarted.line, style = stroke(1.4.dp.toPx(), dotted))
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
