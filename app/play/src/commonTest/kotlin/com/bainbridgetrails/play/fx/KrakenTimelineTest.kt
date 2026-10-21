package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KrakenTimelineTest {
    private val timelines = listOf(KrakenTimeline.attack, KrakenTimeline.recede)

    @Test
    fun `reducedAtMs is the final frame`() {
        for (tl in timelines) {
            assertEquals(tl.totalMs, tl.reducedAtMs, "reduced = the last frame")
            assertEquals(FxStage.DONE, tl.stageOfFolded(tl.reducedAtMs))
        }
        val end = KrakenTimeline.attack.reducedAtMs
        // The attack's final frame is the hand-off: no tentacle, no grip, casing in full, no converge rings.
        for (k in 0 until 3) assertEquals(0f, KrakenTimeline.tentacleLength(end, k))
        assertEquals(0f, KrakenTimeline.grip(end))
        assertEquals(1f, KrakenTimeline.reveal(end))
        assertEquals(0f, KrakenTimeline.convergeAlpha(end))
        // The recede's final frame: casing gone, rings gone.
        val r = KrakenTimeline.recede.reducedAtMs
        assertEquals(0f, KrakenTimeline.recedeCasing(r))
        for (i in 0 until 3) assertEquals(0f, KrakenTimeline.calmRingAlpha(r, i))
    }

    @Test
    fun `total ≤ overlayMaxMs`() {
        val max = FxCaps().overlayMaxMs
        for (tl in timelines) assertTrue(tl.totalMs <= max, "${tl.totalMs} > $max")
        assertEquals(2_600, KrakenTimeline.attack.totalMs, "effects §3.1: tentacles + splash ≤ 2.6 s")
        assertEquals(2_200, KrakenTimeline.recede.totalMs, "effects §3.1: recede 2 200 ms")
    }

    @Test
    fun `RUN never loops`() {
        for (tl in timelines) {
            assertNull(tl.runLoopMs)
            // A long-lived instance holds the last frame instead of wrapping back into RUN.
            assertEquals(tl.totalMs, tl.foldedMs(60_000))
            assertEquals(FxStage.DONE, tl.stageAt(60_000))
        }
    }

    @Test
    fun `grip envelope 0 at spawn and after settle`() {
        val tl = KrakenTimeline.attack
        assertEquals(0f, KrakenTimeline.grip(0))
        for (t in 0 until 900 step 50) assertEquals(0f, KrakenTimeline.grip(t), "no grip before the tentacles reach, t=$t")
        val settleEnd = tl.spawnMs + tl.runMs + tl.settleMs
        for (t in listOf(2_200, 2_400, settleEnd, 9_999)) assertEquals(0f, KrakenTimeline.grip(t), "t=$t")
        assertEquals(1f, KrakenTimeline.grip(1_500), "full grip at the squeeze")
        for (t in 0..settleEnd step 10) assertTrue(KrakenTimeline.grip(t) in 0f..1f)
    }

    @Test
    fun `tentacles stagger and tip overshoots then settles`() {
        // 90 ms stagger: tentacle 1 has not started when tentacle 0 has.
        assertTrue(KrakenTimeline.tentacleLength(560, 0) > 0f)
        assertEquals(0f, KrakenTimeline.tentacleLength(560, 1))
        val peak = (500..1_400 step 10).maxOf { KrakenTimeline.tentacleLength(it, 0) }
        assertTrue(peak in 1.05f..1.11f, "tip overshoots ~10 %: $peak")
        assertEquals(1f, KrakenTimeline.tentacleLength(1_500, 0), 1e-4f)
        // Sinks to 35 % then fades.
        assertEquals(0.35f, KrakenTimeline.tentacleLength(2_300, 0), 1e-3f)
        assertTrue(KrakenTimeline.tentacleAlpha(2_450) in 0.01f..0.99f)
    }

    @Test
    fun `squeeze dips to 94 percent`() {
        assertEquals(1f, KrakenTimeline.squeeze(1_400))
        val min = (1_500..1_800 step 5).minOf { KrakenTimeline.squeeze(it) }
        assertEquals(0.94f, min, 2e-3f)
        assertEquals(1f, KrakenTimeline.squeeze(1_800), 1e-4f)
    }
}
