package com.bainbridgetrails.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.map_attribution_a11y
import com.bainbridgetrails.resources.map_error_title
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.ErrorState
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
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
internal const val FOLLOW_MIN_ZOOM = 15.0

/** Credits for everything the map draws (see data/dist/ATTRIBUTION.md). */
private const val ATTRIBUTION = "© OpenStreetMap contributors, Protomaps, USGS"

/** How the credit line shows (ux.md §6.6). */
enum class AttributionMode {
    /** Full text for 5 s after the first map render of the session, then an ⓘ button that re-expands it. */
    Timed,

    /** ⓘ only (mini maps). */
    InfoOnly,

    /** Full text always (debug spikes, Record). */
    Full,
}

/** Rendered style JSON per style (asset URLs are fixed for the process once [MapAssets.install] ran). Main only. */
private val RenderedStyles = HashMap<MapStyle, String>()

/** Session flag: the full credit line was already shown once (Timed mode). */
private var attributionShown = false

/** Hoisted handle to a [TrailMap]'s live [MapState] (null while the style loads): hit-testing and camera moves. */
@Stable
class TrailMapController {
    var mapState: MapState? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberTrailMapController(): TrailMapController = remember { TrailMapController() }

/** North-up chart (ux.md §6.3 Decision): no rotate / tilt gestures. [onUserPan] fires when a pan gesture starts. */
fun chartInteractions(onTap: ((ClickEvent) -> Unit)? = null, onUserPan: (() -> Unit)? = null): MapInteractions =
    MapInteractions {
        camera {
            rotate { enabled = false }
            pitch { enabled = false }
            if (onUserPan != null) pan { onStart(onUserPan) }
        }
        if (onTap != null) {
            callbacks {
                click {
                    onEvent { e ->
                        onTap(e)
                        ClickResult.Consume
                    }
                }
            }
        }
    }

/**
 * Map of the island rendered from bundled PMTiles with [style].
 *
 * Changing [style] restyles the live map in place (camera kept): the previous style stays on screen
 * until the new template is rendered, then `rememberMapState` pushes the new [BaseStyle] (it calls
 * `updateBaseStyle` on every recomposition, so only the initial *camera* is first-composition-only).
 * [content] adds sources/layers on top of the style; [follow] re-centres the camera whenever it changes.
 * [controller] exposes the live [MapState]; [interactions] default to a north-up chart; [chromePadding] keeps the
 * logo / credit clear of sheets and buttons drawn over the map.
 */
@Composable
fun TrailMap(
    style: MapStyle,
    modifier: Modifier = Modifier,
    initialCamera: CameraPosition = IslandCamera,
    controlsTopPadding: Dp = 0.dp,
    follow: Position? = null,
    controller: TrailMapController? = null,
    interactions: MapInteractions = chartInteractions(),
    attribution: AttributionMode = AttributionMode.Full,
    chromePadding: PaddingValues = PaddingValues(0.dp),
    showScaleBar: Boolean = true,
    content: @Composable @MaplibreComposable () -> Unit = {},
) {
    val dataDir = rememberMapDataDir()
    var attempt by remember { mutableIntStateOf(0) }
    // A tab switch disposes the map; the rendered style is cached per process so coming back shows the map at once
    // (no Loading frame, no template re-read).
    val load by produceState<StyleLoad>(RenderedStyles[style]?.let { StyleLoad.Ready(it) } ?: StyleLoad.Loading, style, dataDir, attempt) {
        if (value is StyleLoad.Ready) return@produceState
        value = try {
            val urls = MapAssets.install(dataDir)
            // Res.readBytes reads on the calling thread (here Main): read and render the template on IO.
            val json = withContext(Dispatchers.IO) { renderStyleTemplate(Res.readBytes(style.assetPath).decodeToString(), urls) }
            RenderedStyles[style] = json
            StyleLoad.Ready(json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("map", "style load failed: ${e.message}")
            StyleLoad.Failed("Map data failed to load: ${e.message}")
        }
    }
    Box(modifier) {
        when (val l = load) {
            StyleLoad.Loading -> Box(Modifier.fillMaxSize().background(if (style.isDark) Color(0xFF1D2925) else Color(0xFFECEFE6)))
            is StyleLoad.Failed -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                ErrorState(
                    title = stringResource(Res.string.map_error_title),
                    retryLabel = stringResource(Res.string.common_retry),
                    onRetry = { attempt++ },
                )
            }
            is StyleLoad.Ready -> {
                val state = rememberMapState(baseStyle = BaseStyle.Json(l.json), initialCameraPosition = initialCamera, content = content)
                if (controller != null) {
                    DisposableEffect(state, controller) {
                        controller.mapState = state
                        onDispose { if (controller.mapState === state) controller.mapState = null }
                    }
                }
                LaunchedEffect(state, follow) {
                    val p = follow ?: return@LaunchedEffect
                    state.setCameraPosition(CameraPosition(center = p, zoom = maxOf(state.cameraPosition.zoom, FOLLOW_MIN_ZOOM)))
                }
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    cameraConstraints = IslandConstraints,
                    interactions = interactions,
                    overlay = { TrailMapOverlay(state, controlsTopPadding, style.isDark, attribution, chromePadding, showScaleBar) },
                )
            }
        }
    }
}

/**
 * Scale bar, MapLibre logo and a fixed credit line (no compass: rotation is off). Replaces the library's attribution
 * button, which shows each PMTiles archive's embedded attribution (the style's `attribution` is overridden by the
 * archive metadata) and so omitted Protomaps/USGS.
 */
@Composable
private fun TrailMapOverlay(
    state: MapState,
    controlsTopPadding: Dp,
    dark: Boolean,
    mode: AttributionMode,
    chromePadding: PaddingValues,
    showScaleBar: Boolean,
) {
    var expanded by remember { mutableStateOf(mode == AttributionMode.Full || (mode == AttributionMode.Timed && !attributionShown)) }
    if (mode == AttributionMode.Timed) {
        LaunchedEffect(Unit) {
            if (!attributionShown) {
                delay(5_000)
                attributionShown = true
                expanded = false
            }
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(chromePadding).padding(8.dp)) {
        if (showScaleBar) {
            DisappearingScaleBar(
                metersPerDp = { state.viewport?.metersPerDpAtCenter ?: 0.0 },
                zoom = { state.cameraPosition.zoom },
                modifier = Modifier.align(Alignment.TopStart).padding(top = controlsTopPadding),
            )
        }
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
            MaplibreLogo()
            if (mode != AttributionMode.Full) {
                val desc = stringResource(Res.string.map_attribution_a11y)
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable { expanded = !expanded }
                        .semantics { contentDescription = desc; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).background(BtTheme.colors.mapChrome, CircleShape), contentAlignment = Alignment.Center) {
                        BtIcon(BtIcons.Info, null, size = 18.dp, tint = BtTheme.colors.onMapChrome)
                    }
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    ATTRIBUTION,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background((if (dark) Color(0xFF13201B) else Color(0xFFFBFAF6)).copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 11.sp,
                    color = if (dark) Color(0xFFDCE6E1) else Color(0xFF26302B),
                )
            }
        }
    }
}
