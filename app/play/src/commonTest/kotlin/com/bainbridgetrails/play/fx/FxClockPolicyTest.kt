package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.fx.FxClockPolicy.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FxClockPolicyTest {
    private val hero = FxSceneSummary(animatingHero = true, animatingNotice = false, animatingAmbient = false, animatingLayer = false)
    private val ambient = FxSceneSummary(animatingHero = false, animatingNotice = false, animatingAmbient = true, animatingLayer = true)
    private val none = FxSceneSummary(false, false, false, false)

    private fun mode(
        scene: FxSceneSummary,
        playOn: Boolean = true,
        resumed: Boolean = true,
        reduced: Boolean = false,
        idleMs: Long = 0,
        recording: Boolean = false,
    ) = FxClockPolicy.mode(playOn, resumed, reduced, scene, idleMs, recording)

    @Test
    fun `stopped when paused or reduced or play off`() {
        assertEquals(Mode.FULL, mode(hero))
        assertEquals(Mode.STOPPED, mode(hero, resumed = false))
        assertEquals(Mode.STOPPED, mode(hero, reduced = true))
        assertEquals(Mode.STOPPED, mode(hero, playOn = false))
        assertEquals(Mode.STOPPED, mode(none))
    }

    @Test
    fun `ambient-only idle 20s stops`() {
        assertEquals(Mode.AMBIENT_30HZ, mode(ambient, idleMs = 19_999))
        assertEquals(Mode.STOPPED, mode(ambient, idleMs = 20_000))
        // A HERO keeps the clock at display rate however long the user is idle.
        assertEquals(Mode.FULL, mode(hero.copy(animatingAmbient = true), idleMs = 60_000))
    }

    @Test
    fun `recording disables ambient but not hero`() {
        assertEquals(Mode.STOPPED, mode(ambient, recording = true))
        assertEquals(Mode.FULL, mode(hero, recording = true))
        assertEquals(Mode.FULL, mode(none.copy(animatingNotice = true), recording = true))
    }

    @Test
    fun `ambient publishes at most every 33ms at 120Hz frames`() {
        // Frames every 8.333 ms for 1 s; the clock runs every frame and publishes on elapsed frame time (spike #7).
        val frameNs = 1_000_000_000L / 120
        var lastPublished = -1L
        val published = mutableListOf<Long>()
        for (i in 0 until 120) {
            val now = i * frameNs
            val elapsed = if (lastPublished < 0) Long.MAX_VALUE else now - lastPublished
            if (FxClockPolicy.shouldPublish(Mode.AMBIENT_30HZ, elapsed)) {
                published += now
                lastPublished = now
            }
        }
        assertTrue(published.zipWithNext { a, b -> b - a }.all { it >= 33_000_000L }, "interval < 33 ms")
        assertTrue(published.size in 28..30, "published ${published.size} in 1 s")
        // FULL publishes every frame; STOPPED never.
        assertTrue(FxClockPolicy.shouldPublish(Mode.FULL, frameNs))
        assertTrue(!FxClockPolicy.shouldPublish(Mode.STOPPED, Long.MAX_VALUE))
    }

    @Test
    fun `mapMaxFps 30 only while layer animation runs`() {
        assertEquals(30, FxClockPolicy.mapMaxFps(Mode.AMBIENT_30HZ, ambient))
        assertEquals(30, FxClockPolicy.mapMaxFps(Mode.FULL, hero.copy(animatingLayer = true)))
        assertNull(FxClockPolicy.mapMaxFps(Mode.FULL, hero))            // overlay-only animation: map uncapped
        assertNull(FxClockPolicy.mapMaxFps(Mode.STOPPED, ambient))       // clock stopped: restore the default
    }
}
