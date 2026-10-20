package com.bainbridgetrails.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.DisappearingCompassButton
import org.maplibre.compose.overlay.DisappearingScaleBar
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** Island center and default zoom. */
internal val IslandCamera = CameraPosition(center = Position(longitude = -122.535, latitude = 47.645), zoom = 12.0)

/** Camera limits: Bainbridge Island bbox (same as the data pipeline), z10.5-18. */
internal val IslandConstraints = CameraConstraints {
    minZoom = 10.5
    maxZoom = 18.0
    boundingBox = BoundingBox(west = -122.62, south = 47.57, east = -122.47, north = 47.73)
}

private sealed interface StyleLoad {
    data object Loading : StyleLoad
    data class Ready(val json: String) : StyleLoad
    data class Failed(val message: String) : StyleLoad
}

/** Zoom the camera moves in to (at least) when it starts following a position. */
private const val FOLLOW_MIN_ZOOM = 15.0

/** Credits for everything the map draws (see data/dist/ATTRIBUTION.md). */
private const val ATTRIBUTION = "© OpenStreetMap contributors, Protomaps, USGS"

/**
 * Map of the island rendered from bundled PMTiles with [style].
 *
 * Changing [style] restyles the live map in place (camera kept): the previous style stays on screen
 * until the new template is rendered, then `rememberMapState` pushes the new [BaseStyle] (it calls
 * `updateBaseStyle` on every recomposition, so only the initial *camera* is first-composition-only).
 * [content] adds sources/layers on top of the style; [follow] re-centres the camera whenever it changes.
 */
@Composable
fun TrailMap(
    style: MapStyle,
    modifier: Modifier = Modifier,
    initialCamera: CameraPosition = IslandCamera,
    controlsTopPadding: Dp = 0.dp,
    follow: Position? = null,
    content: @Composable @MaplibreComposable () -> Unit = {},
) {
    val dataDir = rememberMapDataDir()
    val load by produceState<StyleLoad>(StyleLoad.Loading, style, dataDir) {
        value = try {
            val urls = MapAssets.install(dataDir)
            // Res.readBytes reads on the calling thread (here Main): read and render the template on IO.
            StyleLoad.Ready(withContext(Dispatchers.IO) { renderStyleTemplate(Res.readBytes(style.assetPath).decodeToString(), urls) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StyleLoad.Failed("Map data failed to load: ${e.message}")
        }
    }
    Box(modifier) {
        when (val l = load) {
            StyleLoad.Loading -> Box(Modifier.fillMaxSize().background(if (style.isDark) Color(0xFF1D2925) else Color(0xFFECEFE6)))
            is StyleLoad.Failed -> Text(l.message, Modifier.align(Alignment.Center).padding(16.dp))
            is StyleLoad.Ready -> {
                val state = rememberMapState(baseStyle = BaseStyle.Json(l.json), initialCameraPosition = initialCamera, content = content)
                LaunchedEffect(state, follow) {
                    val p = follow ?: return@LaunchedEffect
                    state.setCameraPosition(CameraPosition(center = p, zoom = maxOf(state.cameraPosition.zoom, FOLLOW_MIN_ZOOM)))
                }
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    cameraConstraints = IslandConstraints,
                    overlay = { TrailMapOverlay(state, controlsTopPadding, style.isDark) },
                )
            }
        }
    }
}

/**
 * Scale bar, compass, MapLibre logo and a fixed credit line. Replaces the library's attribution
 * button, which shows each PMTiles archive's embedded attribution (the style's `attribution` is
 * overridden by the archive metadata) and so omitted Protomaps/USGS.
 */
@Composable
private fun TrailMapOverlay(state: MapState, controlsTopPadding: Dp, dark: Boolean) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        DisappearingScaleBar(
            metersPerDp = { state.viewport?.metersPerDpAtCenter ?: 0.0 },
            zoom = { state.cameraPosition.zoom },
            modifier = Modifier.align(Alignment.TopStart).padding(top = controlsTopPadding),
        )
        DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd).padding(top = controlsTopPadding))
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
            MaplibreLogo()
            Text(
                ATTRIBUTION,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background((if (dark) Color(0xFF0E1F27) else Color.White).copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 11.sp,
                color = if (dark) Color(0xFFA9B8B1) else Color(0xFF333333),
            )
        }
    }
}
