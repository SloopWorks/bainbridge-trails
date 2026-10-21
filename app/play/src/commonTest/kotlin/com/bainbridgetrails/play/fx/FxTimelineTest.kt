package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertEquals

class FxTimelineTest {
    // The kraken overlay timeline (engine.md §2.4).
    private val oneShot = FxTimeline(spawnMs = 700, runMs = 1_300, settleMs = 600, despawnMs = 400, reducedAtMs = 3_000)
    private val looping = FxTimeline(spawnMs = 200, runMs = 1_000, settleMs = 300, despawnMs = 100, runLoopMs = 1_000, reducedAtMs = 200)

    @Test
    fun `stageAt boundaries`() {
        assertEquals(3_000, oneShot.totalMs)
        assertEquals(FxStage.SPAWN, oneShot.stageAt(-5))
        assertEquals(FxStage.SPAWN, oneShot.stageAt(0))
        assertEquals(FxStage.SPAWN, oneShot.stageAt(699))
        assertEquals(FxStage.RUN, oneShot.stageAt(700))
        assertEquals(FxStage.RUN, oneShot.stageAt(1_999))
        assertEquals(FxStage.SETTLE, oneShot.stageAt(2_000))
        assertEquals(FxStage.SETTLE, oneShot.stageAt(2_599))
        assertEquals(FxStage.DESPAWN, oneShot.stageAt(2_600))
        assertEquals(FxStage.DESPAWN, oneShot.stageAt(2_999))
        assertEquals(FxStage.DONE, oneShot.stageAt(3_000))
        assertEquals(FxStage.DONE, oneShot.stageAt(1_000_000))
        assertEquals(3_000, oneShot.foldedMs(9_999))
    }

    @Test
    fun `foldedMs loops RUN`() {
        assertEquals(0, looping.foldedMs(0))
        assertEquals(199, looping.foldedMs(199))
        assertEquals(200, looping.foldedMs(200))
        assertEquals(1_199, looping.foldedMs(1_199))
        assertEquals(200, looping.foldedMs(1_200))
        assertEquals(700, looping.foldedMs(200 + 7 * 1_000 + 500))
        assertEquals(FxStage.RUN, looping.stageAt(86_400_000L * 30))
    }

    @Test
    fun `dismiss jumps to SETTLE without cutting`() {
        // Looping effect dismissed mid-RUN: SETTLE starts at the dismissal, then DESPAWN, then DONE — never straight to DONE.
        val d = 5_432L
        assertEquals(FxStage.RUN, looping.stageAt(d - 1, dismissedAtMs = d))
        assertEquals(FxStage.SETTLE, looping.stageAt(d, dismissedAtMs = d))
        assertEquals(1_200, looping.foldedMs(d, dismissedAtMs = d))
        assertEquals(1_350, looping.foldedMs(d + 150, dismissedAtMs = d))
        assertEquals(FxStage.DESPAWN, looping.stageAt(d + 300, dismissedAtMs = d))
        assertEquals(FxStage.DONE, looping.stageAt(d + 400, dismissedAtMs = d))
        // One-shot dismissed in SPAWN: also plays its full SETTLE + DESPAWN.
        assertEquals(FxStage.SETTLE, oneShot.stageAt(100, dismissedAtMs = 100))
        assertEquals(FxStage.DESPAWN, oneShot.stageAt(700, dismissedAtMs = 100))
        assertEquals(FxStage.DESPAWN, oneShot.stageAt(1_099, dismissedAtMs = 100))
        assertEquals(FxStage.DONE, oneShot.stageAt(1_100, dismissedAtMs = 100))
        // Dismissed while already settling: no jump backwards.
        assertEquals(oneShot.foldedMs(2_300), oneShot.foldedMs(2_300, dismissedAtMs = 2_100))
    }

    /** CelebrationScreen.kt / IslandCelebration.kt: `((t - from) / (to - from)).coerceIn(0f, 1f)`. */
    private fun celebrationPhase(t: Float, from: Int, to: Int): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    @Test
    fun `phase equals CelebrationScreen phase`() {
        for (t in -100..3_100 step 7) {
            for ((from, to) in listOf(0 to 700, 700 to 1_100, 2_000 to 2_400, 2_600 to 3_000)) {
                assertEquals(celebrationPhase(t.toFloat(), from, to), oneShot.phase(t, from, to), "t=$t [$from,$to]")
            }
        }
    }
}
