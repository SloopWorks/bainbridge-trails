package com.bainbridgetrails.play.spike

import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions

/** DEBUG SPIKE (S4): `fxTexture=1` renders the map into a TextureView instead of the default SurfaceView. */
internal actual fun fxSpikeUiOptions(texture: Boolean): MapUiOptions =
    if (texture) MapUiOptions(MapUiOptions.Standard) { renderMode = AndroidRenderMode.Texture } else MapUiOptions.Standard
