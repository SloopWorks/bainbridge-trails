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

