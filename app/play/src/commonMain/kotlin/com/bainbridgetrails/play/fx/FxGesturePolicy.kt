package com.bainbridgetrails.play.fx

/**
 * PD-19 (spike S4): Overlay-tier parts slip up to one frame of camera motion against the map, so they are hidden (or
 * handed to their Layer LOD) while the camera moves by gesture and fade back after `CameraMoveEnded`. Programmatic
 * moves (`fitTo`, `animateCamera`) keep the overlay.
 */
object FxGesturePolicy {
    /** Fade-back duration after a gesture ends (BtDuration.short3). */
    const val FADE_IN_MS: Long = 150L

    fun overlayVisible(cameraMoving: Boolean, reasonGesture: Boolean): Boolean = !(cameraMoving && reasonGesture)

    /**
     * Overlay alpha: 0 during a gesture move; after it ends, ramps linearly 0 → 1 over [FADE_IN_MS]
     * ([msSinceGestureEnd] = ms since the last gesture-driven `CameraMoveEnded`, null when none is recent).
     */
    fun overlayAlpha(cameraMoving: Boolean, reasonGesture: Boolean, msSinceGestureEnd: Long?): Float {
        if (!overlayVisible(cameraMoving, reasonGesture)) return 0f
        if (msSinceGestureEnd == null) return 1f
        return (msSinceGestureEnd.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
    }
}
