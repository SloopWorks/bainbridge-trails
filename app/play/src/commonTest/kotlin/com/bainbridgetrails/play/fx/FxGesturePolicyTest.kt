package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PD-19: Overlay-tier parts hide while the camera moves by gesture and fade back on CameraMoveEnded. */
class FxGesturePolicyTest {
    @Test
    fun `overlay hidden while gesture moves camera`() {
        assertFalse(FxGesturePolicy.overlayVisible(cameraMoving = true, reasonGesture = true))
        assertEquals(0f, FxGesturePolicy.overlayAlpha(cameraMoving = true, reasonGesture = true, msSinceGestureEnd = null))
    }

    @Test
    fun `programmatic fitTo keeps overlay`() {
        assertTrue(FxGesturePolicy.overlayVisible(cameraMoving = true, reasonGesture = false))
        assertEquals(1f, FxGesturePolicy.overlayAlpha(cameraMoving = true, reasonGesture = false, msSinceGestureEnd = null))
        assertTrue(FxGesturePolicy.overlayVisible(cameraMoving = false, reasonGesture = false))
    }

    @Test
    fun `fades back on move end`() {
        val fade = FxGesturePolicy.FADE_IN_MS
        assertEquals(0f, FxGesturePolicy.overlayAlpha(false, false, msSinceGestureEnd = 0))
        assertEquals(0.5f, FxGesturePolicy.overlayAlpha(false, false, msSinceGestureEnd = fade / 2), 1e-3f)
        assertEquals(1f, FxGesturePolicy.overlayAlpha(false, false, msSinceGestureEnd = fade))
        assertEquals(1f, FxGesturePolicy.overlayAlpha(false, false, msSinceGestureEnd = fade * 10))
        assertTrue(FxGesturePolicy.overlayVisible(cameraMoving = false, reasonGesture = true), "move ended")
    }
}
