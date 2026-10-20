package com.bainbridgetrails.map

import kotlinx.coroutines.CancellationException
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Duration.Companion.milliseconds

/** long1 (450 ms) on the emphasized curve (ux.md §6.3). */
private val Emphasized = CameraAnimation.Ease {
    duration = 450.milliseconds
    easing = CubicBezier(0.2, 0.0, 0.0, 1.0)
}

/**
 * Fits [bounds] inside the map minus [padding], north-up, zoom clamped to [FIT_MIN_ZOOM]..[maxZoom]; animated unless
 * [animate] is false (reduced motion). Returns quietly when the map goes away mid-move.
 */
suspend fun MapState.fitTo(bounds: GeoBounds, padding: DpPadding, animate: Boolean, maxZoom: Double = FIT_MAX_ZOOM, minZoom: Double = FIT_MIN_ZOOM) {
    try {
        val cam = cameraForBounds(BoundingBox(bounds.west, bounds.south, bounds.east, bounds.north), CameraFit(fitPadding = padding))
        val target = cam.copy(zoom = cam.zoom.coerceIn(minZoom, maxZoom), bearing = 0.0, pitch = 0.0)
        moveTo(target, animate)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A fit can fail while the map detaches or the viewport is 0×0; the camera just stays.
    }
}

suspend fun MapState.moveTo(target: CameraPosition, animate: Boolean) {
    if (animate) animateCamera(target.toCameraUpdate(), Emphasized) else setCameraPosition(target)
}

/** Ground distance (m) from [p] to [b] (0 inside). Equirectangular, fine at island scale. */
fun distanceOutsideM(p: Position, b: GeoBounds): Double {
    val lon = p.longitude.coerceIn(b.west, b.east)
    val lat = p.latitude.coerceIn(b.south, b.north)
    val k = cos(p.latitude * PI / 180)
    val dx = (p.longitude - lon) * 111_320.0 * k
    val dy = (p.latitude - lat) * 110_574.0
    return kotlin.math.hypot(dx, dy)
}
