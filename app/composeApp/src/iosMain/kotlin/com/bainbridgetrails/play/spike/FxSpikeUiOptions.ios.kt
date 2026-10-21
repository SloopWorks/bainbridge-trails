package com.bainbridgetrails.play.spike

import org.maplibre.compose.map.MapUiOptions

/** DEBUG SPIKE (S4): no render-mode choice on iOS. */
internal actual fun fxSpikeUiOptions(texture: Boolean): MapUiOptions = MapUiOptions.Standard
