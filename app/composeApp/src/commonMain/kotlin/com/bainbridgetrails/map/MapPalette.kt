package com.bainbridgetrails.map

import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.coverage.TrailStatus

/**
 * Trail line colors for one map style (docs/spikes/map-render/styles.md palette; mirrors the `trails` entries in
 * scripts/build-map-styles.py). Used by the app-side status layers (TL-1) and the legend swatches, so the legend always
 * matches the map that is on screen (Light has its own column, unlike the app theme).
 */
data class MapPalette(
    val notStarted: Color,
    val partway: Color,
    val nearlyDone: Color,
    val complete: Color,
    /** Dark casing under Nearly done and Partway lines (non-text contrast, decisions.md P2). */
    val casing: Color,
    /** Land-colored halo under every counted trail, keeps trails apart from contours/roads. */
    val halo: Color,
    val haloOpacity: Float,
    /** Not-counted hairlines (included == false). */
    val notCounted: Color,
    val notCountedOpacity: Float,
    val notCountedBeach: Color,
    val notCountedClosed: Color,
    /** Selected-trail outline and the gap line between it and the trail. */
    val selection: Color,
    val selectionGap: Color,
    /** Complete gets a soft glow on Dark. */
    val completeGlow: Float = 0f,
) {
    fun of(status: TrailStatus): Color = when (status) {
        TrailStatus.NOT_STARTED -> notStarted
        TrailStatus.PARTWAY -> partway
        TrailStatus.NEARLY_DONE -> nearlyDone
        TrailStatus.COMPLETE -> complete
    }

    companion object {
        val Topo = MapPalette(
            notStarted = Color(0xFF6E6A64), partway = Color(0xFFC98A00), nearlyDone = Color(0xFFE2562A), complete = Color(0xFF9B1FA8),
            casing = Color(0xFF5A1F0C), halo = Color(0xFFECEFE6), haloOpacity = 0.85f,
            notCounted = Color(0xFF7D8679), notCountedOpacity = 0.75f, notCountedBeach = Color(0xFF4F8797), notCountedClosed = Color(0xFFA6564A),
            selection = Color(0xFF1E2A24), selectionGap = Color(0xFFFBFAF6),
        )
        val Light = MapPalette(
            notStarted = Color(0xFF66625C), partway = Color(0xFFC08300), nearlyDone = Color(0xFFDE4F22), complete = Color(0xFF931B9F),
            casing = Color(0xFF4A1A09), halo = Color(0xFFE8EBE7), haloOpacity = 1f,
            notCounted = Color(0xFF9AA19A), notCountedOpacity = 0.85f, notCountedBeach = Color(0xFF7FA6B3), notCountedClosed = Color(0xFFBB8077),
            selection = Color(0xFF1E2A24), selectionGap = Color(0xFFFBFAF6),
        )
        val Dark = MapPalette(
            notStarted = Color(0xFFA9A49B), partway = Color(0xFFF0B43C), nearlyDone = Color(0xFFFF7B47), complete = Color(0xFFD45CFF),
            casing = Color(0xFF0B1410), halo = Color(0xFF1D2925), haloOpacity = 0.8f,
            notCounted = Color(0xFF5E6E66), notCountedOpacity = 0.8f, notCountedBeach = Color(0xFF3F7486), notCountedClosed = Color(0xFF8A5048),
            selection = Color(0xFFDCE6E1), selectionGap = Color(0xFF0B1410), completeGlow = 0.35f,
        )
    }
}

val MapStyle.palette: MapPalette
    get() = when (this) {
        MapStyle.TOPO -> MapPalette.Topo
        MapStyle.MINIMAL_LIGHT -> MapPalette.Light
        MapStyle.DARK -> MapPalette.Dark
    }

/**
 * Line encoding per status (styles.md "Status encoding"): width stops z11/13/15/17 in px (= dp in MapLibre) and the
 * dash pattern (× width). Shared by the map layers and the legend swatches.
 */
object TrailLineSpec {
    val widths: Map<TrailStatus, List<Pair<Int, Float>>> = mapOf(
        TrailStatus.COMPLETE to listOf(11 to 1.8f, 13 to 3.2f, 15 to 5.6f, 17 to 9.0f),
        TrailStatus.NEARLY_DONE to listOf(11 to 1.3f, 13 to 2.3f, 15 to 3.8f, 17 to 6.2f),
        TrailStatus.PARTWAY to listOf(11 to 1.2f, 13 to 2.0f, 15 to 3.2f, 17 to 5.0f),
        TrailStatus.NOT_STARTED to listOf(11 to 1.1f, 13 to 1.7f, 15 to 2.6f, 17 to 4.0f),
    )

    /** null = solid. Not started: round dots; Partway: long dash. */
    fun dash(status: TrailStatus): List<Float>? = when (status) {
        TrailStatus.NOT_STARTED -> listOf(0.01f, 1.9f)
        TrailStatus.PARTWAY -> listOf(2.6f, 1.4f)
        else -> null
    }

    /** Named trails outside the island total (TL-6): status color, short even dash, slightly thinner. */
    val notCountedNamedDash: List<Float> = listOf(2.2f, 1.6f)
    const val NOT_COUNTED_NAMED_SCALE = 0.85f

    fun hasCasing(status: TrailStatus): Boolean = status == TrailStatus.NEARLY_DONE || status == TrailStatus.PARTWAY
    const val CASING_EXTRA = 2.2f
    const val HALO_EXTRA = 3.0f

    /** Opacity of every other trail while one is selected (ux.md §6.2). */
    const val DIM_OPACITY = 0.45f
}
