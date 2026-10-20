package com.bainbridgetrails.map

import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.DatasetProfile

/**
 * Line encoding of the app-side status layers for one dataset (docs/roads/ux.md §2.2; replaces the v1
 * `TrailLineSpec` object): width stops (zoom → dp), dash patterns (× width), halo/casing extras, zoom gates and the
 * extra style layers a tap queries. One code path: [StatusLayers], the legend and the map tap all read the active
 * instance ([of] / [current]); the [TRAILS] values are the v1 encoding, unchanged.
 */
data class StatusLayerSpec(
    val widths: Map<TrailStatus, List<Pair<Int, Float>>>,
    /** null = solid. */
    val dashes: Map<TrailStatus, List<Float>?>,
    /** The app's not-started layer starts at this zoom (roads: below it the style's category bed means "not started"). */
    val notStartedMinZoom: Float,
    val notStartedOpacity: Float,
    /** Statuses drawn over a land-colored halo. */
    val haloStatuses: Set<TrailStatus>,
    val haloExtra: Float,
    val casingExtra: Float,
    /** Extra width of the Dark style's soft glow under Complete. */
    val glowExtra: Float,
    /** Opacity of everything else while one trail/road is selected. */
    val dimOpacity: Float,
    /** Unnamed not-counted hairlines (trails); the roads style draws uncounted roads itself. */
    val drawUnnamedHairlines: Boolean,
    /** Named trails outside the island total in status colors (TL-6); uncounted roads never show progress. */
    val drawNamedNotCounted: Boolean,
    /** Style layers a map tap also queries (roads: the counted bed, the only hit target for unwalked roads below z15). */
    val extraTappableLayerIds: Set<String>,
    /** Camera fit clamp for a selected trail / road. */
    val fitZoomRange: ClosedFloatingPointRange<Double>,
    /**
     * Selection outline and the selected line use at least this status' widths, so a road with no status line at
     * the current zoom (not started below z15) still reads as selected. null = the selected item's own status.
     */
    val selectionMinStatus: TrailStatus?,
    /** Road-end pins (roads; inert when the dataset has none). */
    val drawRoadEnds: Boolean,
) {
    fun dash(status: TrailStatus): List<Float>? = dashes[status]

    fun hasCasing(status: TrailStatus): Boolean = status == TrailStatus.NEARLY_DONE || status == TrailStatus.PARTWAY

    /** Width (dp) of [status] at [zoom], interpolated linearly between its stops and clamped at the ends. */
    fun widthAt(status: TrailStatus, zoom: Int): Float {
        val stops = widths.getValue(status)
        if (zoom <= stops.first().first) return stops.first().second
        if (zoom >= stops.last().first) return stops.last().second
        val i = stops.indexOfFirst { it.first >= zoom }
        if (stops[i].first == zoom) return stops[i].second
        val (z0, w0) = stops[i - 1]
        val (z1, w1) = stops[i]
        return w0 + (w1 - w0) * (zoom - z0) / (z1 - z0)
    }

    /** Width stops the selection treatment draws with for an item of [status] (see [selectionMinStatus]). */
    fun selectionWidths(status: TrailStatus): List<Pair<Int, Float>> {
        val own = widths.getValue(status)
        val min = selectionMinStatus?.let { widths.getValue(it) } ?: return own
        val zooms = (own.map { it.first } + min.map { it.first }).distinct().sorted()
        return zooms.map { z ->
            val o = if (z < own.first().first) 0f else widthAt(status, z)
            z to maxOf(o, widthAt(selectionMinStatus, z))
        }
    }

    /** Layer ids a tap queries: counted status lines (+ TL-6 lines when drawn) and [extraTappableLayerIds]. */
    val tappableLayerIds: Set<String>
        get() = buildSet {
            for (s in TrailStatus.entries) {
                add(statusLayerId(s))
                if (drawNamedNotCounted) add(namedNotCountedLayerId(s))
            }
            addAll(extraTappableLayerIds)
        }

    companion object {
        /** Named trails outside the island total (TL-6): status color, short even dash, slightly thinner. */
        val notCountedNamedDash: List<Float> = listOf(2.2f, 1.6f)
        const val NOT_COUNTED_NAMED_SCALE = 0.85f

        /** Roads dataset style layers (scripts/build-map-styles.py). */
        const val ROAD_BED_LAYER = "roads-ds-bed"
        const val ROAD_PRIVATE_LAYER = "roads-ds-private"
        const val ROAD_HOLDOUT_LAYER = "roads-ds-holdout"

        val TRAILS = StatusLayerSpec(
            widths = mapOf(
                TrailStatus.COMPLETE to listOf(11 to 1.8f, 13 to 3.2f, 15 to 5.6f, 17 to 9.0f),
                TrailStatus.NEARLY_DONE to listOf(11 to 1.3f, 13 to 2.3f, 15 to 3.8f, 17 to 6.2f),
                TrailStatus.PARTWAY to listOf(11 to 1.2f, 13 to 2.0f, 15 to 3.2f, 17 to 5.0f),
                TrailStatus.NOT_STARTED to listOf(11 to 1.1f, 13 to 1.7f, 15 to 2.6f, 17 to 4.0f),
            ),
            dashes = mapOf(TrailStatus.NOT_STARTED to listOf(0.01f, 1.9f), TrailStatus.PARTWAY to listOf(2.6f, 1.4f)),
            notStartedMinZoom = 0f,
            notStartedOpacity = 1f,
            haloStatuses = TrailStatus.entries.toSet(),
            haloExtra = 3.0f,
            casingExtra = 2.2f,
            glowExtra = 4f,
            dimOpacity = 0.45f,
            drawUnnamedHairlines = true,
            drawNamedNotCounted = true,
            extraTappableLayerIds = emptySet(),
            fitZoomRange = 12.0..16.5,
            selectionMinStatus = null,
            drawRoadEnds = false,
        )

        val ROADS = StatusLayerSpec(
            widths = mapOf(
                TrailStatus.COMPLETE to listOf(11 to 2.6f, 13 to 3.4f, 15 to 4.4f, 17 to 6.0f),
                TrailStatus.NEARLY_DONE to listOf(11 to 2.0f, 13 to 2.8f, 15 to 3.6f, 17 to 5.0f),
                TrailStatus.PARTWAY to listOf(11 to 1.8f, 13 to 2.4f, 15 to 3.0f, 17 to 4.2f),
                TrailStatus.NOT_STARTED to listOf(15 to 2.0f, 17 to 3.0f),
            ),
            dashes = mapOf(TrailStatus.NOT_STARTED to listOf(0.01f, 2.2f), TrailStatus.PARTWAY to listOf(2.6f, 1.4f)),
            notStartedMinZoom = 15f,
            notStartedOpacity = 0.8f,
            haloStatuses = setOf(TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE, TrailStatus.COMPLETE),
            haloExtra = 2.0f,
            casingExtra = 2.0f,
            glowExtra = 3f,
            dimOpacity = 0.45f,
            drawUnnamedHairlines = false,
            drawNamedNotCounted = false,
            // Uncounted roads stay tappable (product.md §6, AC E4): the preview says why they don't count.
            extraTappableLayerIds = setOf(ROAD_BED_LAYER, ROAD_PRIVATE_LAYER, ROAD_HOLDOUT_LAYER),
            fitZoomRange = 13.0..17.0,
            selectionMinStatus = TrailStatus.NEARLY_DONE,
            drawRoadEnds = true,
        )

        fun of(profile: DatasetProfile): StatusLayerSpec = when (profile) {
            DatasetProfile.TRAILS -> TRAILS
            DatasetProfile.ROADS -> ROADS
        }

        /** The spec of the dataset this process runs on. */
        val current: StatusLayerSpec get() = of(DatasetProfile.current)
    }
}

/**
 * Roads dataset category-bed and uncounted-road colors for one map style (mirrors `road_dataset` in
 * scripts/build-map-styles.py). The style draws them; the app needs them for legend swatches and the road-complete
 * celebration's "plain road" ghost (docs/roads/ux.md §2.7, §6.2).
 */
data class RoadBedColors(
    val stateRoute: Color,
    val arterial: Color,
    val collector: Color,
    val residential: Color,
    val private: Color,
    val holdout: Color,
    val mutedOpacity: Float = 0.85f,
) {
    /** Bed color of a `category` value (lanes, driveways and unknown fall back to residential). */
    fun of(category: String?): Color = when (category) {
        "state_route" -> stateRoute
        "arterial" -> arterial
        "collector" -> collector
        else -> residential
    }

    companion object {
        val Topo = RoadBedColors(Color(0xFF5F86A6), Color(0xFF85A6BE), Color(0xFFA6BFCB), Color(0xFFC2D1D1), Color(0xFF8E9890), Color(0xFFA2998A))
        val Light = RoadBedColors(Color(0xFF6E95B3), Color(0xFF93B2C6), Color(0xFFB3C8D1), Color(0xFFCDD9D8), Color(0xFF9AA29B), Color(0xFFADA596))
        val Dark = RoadBedColors(Color(0xFF6E90A8), Color(0xFF567A90), Color(0xFF42606F), Color(0xFF33505A), Color(0xFF5A6861), Color(0xFF5E5A4E))
    }
}

val MapStyle.roadBeds: RoadBedColors
    get() = when (this) {
        MapStyle.TOPO -> RoadBedColors.Topo
        MapStyle.MINIMAL_LIGHT -> RoadBedColors.Light
        MapStyle.DARK -> RoadBedColors.Dark
    }
