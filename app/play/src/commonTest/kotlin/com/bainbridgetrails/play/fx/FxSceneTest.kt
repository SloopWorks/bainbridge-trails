package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FxSceneTest {
    private val p = GeoPoint(-122.52, 47.63)
    private val oneShot3s = FxTimeline(spawnMs = 700, runMs = 1_300, settleMs = 600, despawnMs = 400, reducedAtMs = 3_000)
    private val long20s = FxTimeline(spawnMs = 1_000, runMs = 17_000, settleMs = 1_000, despawnMs = 1_000, reducedAtMs = 20_000)
    private val ambientLoop = FxTimeline(spawnMs = 300, runMs = 2_000, settleMs = 300, despawnMs = 200, runLoopMs = 2_000, reducedAtMs = 300)

    private fun spec(
        id: String,
        priority: FxPriority,
        band: FxBand = FxBand.OVER_STATUS,
        timeline: FxTimeline = oneShot3s,
        kind: String = "k_$id",
    ) = FxSpec(
        id = id, kind = kind, anchor = FxAnchor.Point(p), zoom = 0.0..22.0, band = band, priority = priority,
        timeline = timeline, loop = timeline.runLoopMs != null,
    )

    private fun FxFrame.ids() = live.map { it.instance.spec.id }

    @Test
    fun `second HERO queues`() {
        val s = FxScene()
        assertIs<SpawnResult.Spawned>(s.spawn(spec("h1", FxPriority.HERO), 0))
        val r = s.spawn(spec("h2", FxPriority.HERO), 100)
        assertEquals(SpawnResult.Queued(position = 1), r)
        assertEquals(SpawnResult.Queued(position = 2), s.spawn(spec("h3", FxPriority.HERO), 200))
        assertEquals(listOf("h1"), s.tick(500, reduced = false).ids())
        assertIs<SpawnResult.Rejected>(s.spawn(spec("h2", FxPriority.HERO), 600), "duplicate id while queued")
    }

    @Test
    fun `hero cooldown 8s`() {
        val s = FxScene()
        s.spawn(spec("h1", FxPriority.HERO), 0) // DONE at 3 000
        assertEquals(SpawnResult.Queued(1), s.spawn(spec("h2", FxPriority.HERO), 4_000))
        assertEquals(emptyList(), s.tick(10_999, reduced = false).ids())
        val f = s.tick(11_000, reduced = false)
        assertEquals(listOf("h2"), f.ids())
        assertEquals(11_000, f.live.single().instance.spawnedAtMs)
        // A HERO spawned after the cooldown with nothing live plays at once.
        val s2 = FxScene()
        s2.spawn(spec("a", FxPriority.HERO), 0)
        assertIs<SpawnResult.Spawned>(s2.spawn(spec("b", FxPriority.HERO), 11_000))
    }

    @Test
    fun `queued hero expires after 10s`() {
        val s = FxScene()
        s.spawn(spec("h1", FxPriority.HERO, timeline = long20s), 0) // DONE at 20 000
        assertEquals(SpawnResult.Queued(1), s.spawn(spec("h2", FxPriority.HERO), 1_000))
        s.tick(10_999, reduced = false)
        assertEquals(1, s.queuedCount)
        s.tick(11_000, reduced = false)
        assertEquals(0, s.queuedCount)
        assertEquals(emptyList(), s.tick(40_000, reduced = false).ids())
    }

    @Test
    fun `overlay part retires at overlayMaxMs`() {
        val s = FxScene()
        // A 5 s timeline in the OVERLAY band is cut off by the 3 s hand-off bound (PF-1).
        val t5 = FxTimeline(spawnMs = 1_000, runMs = 3_000, settleMs = 500, despawnMs = 500, reducedAtMs = 5_000)
        s.spawn(spec("o", FxPriority.NOTICE, band = FxBand.OVERLAY, timeline = t5), 1_000)
        assertEquals(listOf("o"), s.tick(3_999, reduced = false).ids())
        assertEquals(emptyList(), s.tick(4_000, reduced = false).ids())
        // An overlay RUN never loops.
        assertIs<SpawnResult.Rejected>(s.spawn(spec("loop", FxPriority.AMBIENT, band = FxBand.OVERLAY, timeline = ambientLoop), 5_000))
    }

    @Test
    fun `out-of-view HERO jumps to final frame`() {
        val s = FxScene()
        assertIs<SpawnResult.Spawned>(s.spawn(spec("far", FxPriority.HERO), 0, inView = false))
        val f = s.tick(100, reduced = false)
        val item = f.live.single()
        assertEquals(oneShot3s.reducedAtMs, item.t)
        assertTrue(item.finalFrame)
        assertFalse(s.isAnimating)
        // It never held the HERO slot: a second HERO plays at once.
        assertIs<SpawnResult.Spawned>(s.spawn(spec("near", FxPriority.HERO), 200))
    }

    @Test
    fun `overlay cap demotes oldest ambient`() {
        val s = FxScene()
        s.spawn(spec("a1", FxPriority.AMBIENT, band = FxBand.OVERLAY), 0)
        s.spawn(spec("a2", FxPriority.AMBIENT, band = FxBand.OVERLAY), 10)
        s.spawn(spec("a3", FxPriority.AMBIENT, band = FxBand.OVERLAY), 20)
        s.spawn(spec("n1", FxPriority.NOTICE, band = FxBand.OVERLAY), 30)
        val f = s.tick(40, reduced = false)
        val demoted = f.live.filter { it.demoted }.map { it.instance.spec.id }
        assertEquals(listOf("a1"), demoted)
        assertEquals(oneShot3s.reducedAtMs, f.live.first { it.instance.spec.id == "a1" }.t)
        assertEquals(3, f.live.count { it.instance.spec.band == FxBand.OVERLAY && !it.demoted })
        // ≤ 12 animated AMBIENT on the Layer tier: the 13th demotes the oldest.
        val s2 = FxScene()
        repeat(13) { s2.spawn(spec("amb$it", FxPriority.AMBIENT, timeline = ambientLoop), it.toLong()) }
        val f2 = s2.tick(20, reduced = false)
        assertEquals(listOf("amb0"), f2.live.filter { it.demoted }.map { it.instance.spec.id })
        // ≤ 4 NOTICE: the 5th is rejected.
        val s3 = FxScene()
        repeat(4) { assertIs<SpawnResult.Spawned>(s3.spawn(spec("n$it", FxPriority.NOTICE), 0)) }
        assertEquals(SpawnResult.Rejected("cap"), s3.spawn(spec("n4", FxPriority.NOTICE), 0))
    }

    private fun script(): List<FxFrame> {
        val s = FxScene()
        val frames = mutableListOf<FxFrame>()
        s.spawn(spec("amb", FxPriority.AMBIENT, band = FxBand.UNDER_STATUS, timeline = ambientLoop), 0)
        s.spawn(spec("hero", FxPriority.HERO, band = FxBand.OVERLAY), 50)
        s.spawn(spec("hero2", FxPriority.HERO, band = FxBand.MOMENT), 60)
        s.spawn(spec("note", FxPriority.NOTICE, band = FxBand.OVER_LABELS), 70)
        for (now in 0L..16_000L step 33) {
            if (now == 990L) s.dismiss("amb", now)
            frames += s.tick(now, reduced = now in 5_000L..5_500L)
        }
        return frames
    }

    @Test
    fun `same inputs same frames`() {
        val a = script()
        val b = script()
        assertEquals(a, b)
        // Draw order is (band, priority, seq).
        val f = a.first { it.live.size >= 3 }
        val keys = f.live.map { Triple(it.instance.spec.band.ordinal, it.instance.spec.priority.ordinal, it.instance.seq) }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), keys)
    }

    @Test
    fun `reduced marks every live item reduced and isAnimating=false`() {
        val s = FxScene()
        s.spawn(spec("amb", FxPriority.AMBIENT, timeline = ambientLoop), 0)
        s.spawn(spec("n", FxPriority.NOTICE), 0)
        s.spawn(spec("h", FxPriority.HERO, band = FxBand.OVERLAY), 0)
        val f = s.tick(500, reduced = true)
        assertEquals(3, f.live.size)
        assertTrue(f.live.all { it.reduced })
        assertTrue(f.live.all { it.t == it.instance.spec.timeline.reducedAtMs })
        assertFalse(s.isAnimating)
        assertEquals(FxSceneSummary(false, false, false, false), s.summary())
        s.tick(533, reduced = false)
        assertTrue(s.isAnimating)
    }

    private fun static(id: String, band: FxBand = FxBand.UNDER_STATUS) =
        FxSpec.static(id = id, kind = "k_static", anchor = FxAnchor.Point(p), zoom = 0.0..22.0, band = band)

    @Test
    fun `static parts do not animate or count toward caps`() {
        val s = FxScene()
        // 40 fog cells + 12 glints: the fog never animates and never demotes a glint.
        repeat(40) { assertIs<SpawnResult.Spawned>(s.spawn(static("fog$it"), it.toLong())) }
        assertFalse(s.isAnimating)
        assertEquals(FxSceneSummary(false, false, false, false), s.summary())
        repeat(12) { s.spawn(spec("amb$it", FxPriority.AMBIENT, timeline = ambientLoop), 100L + it) }
        val f = s.tick(200, reduced = false)
        assertEquals(52, f.live.size)
        assertTrue(f.live.none { it.demoted }, "a static part took an animated-AMBIENT slot")
        val fog = f.live.filter { it.instance.spec.isStatic }
        assertEquals(40, fog.size)
        assertTrue(fog.all { it.t == it.instance.spec.timeline.reducedAtMs }, "static parts draw their rest frame")
        assertTrue(s.summary().animatingAmbient && s.summary().animatingLayer)
        // The 13th animated AMBIENT still demotes the oldest animated one, never a static part.
        s.spawn(spec("amb12", FxPriority.AMBIENT, timeline = ambientLoop), 300)
        assertEquals(listOf("amb0"), s.tick(301, reduced = false).live.filter { it.demoted }.map { it.instance.spec.id })
        // Static parts live until dismissed, then leave at once.
        s.dismiss("fog0", 400)
        assertFalse(s.tick(401, reduced = false).live.any { it.instance.spec.id == "fog0" })
    }

    @Test
    fun `clock mode STOPPED with only static parts`() {
        val s = FxScene()
        s.spawn(static("markers", FxBand.OVER_LABELS), 0)
        s.spawn(static("fog", FxBand.UNDER_STATUS), 0)
        s.spawn(static("casing", FxBand.CASING), 0)
        s.tick(1_000, reduced = false)
        val mode = FxClockPolicy.mode(playOn = true, resumed = true, reduced = false, scene = s.summary(), msSinceInteraction = 0, recording = false)
        assertEquals(FxClockPolicy.Mode.STOPPED, mode)
        assertEquals(null, FxClockPolicy.mapMaxFps(mode, s.summary()))
        // One animated glint starts the 30 Hz ambient clock; static parts alone never do.
        s.spawn(spec("glint", FxPriority.AMBIENT, band = FxBand.OVER_LABELS, timeline = ambientLoop), 1_000)
        s.tick(1_033, reduced = false)
        assertEquals(
            FxClockPolicy.Mode.AMBIENT_30HZ,
            FxClockPolicy.mode(playOn = true, resumed = true, reduced = false, scene = s.summary(), msSinceInteraction = 0, recording = false),
        )
    }

    @Test
    fun `static spec is Layer-tier AMBIENT only`() {
        val ok = static("a")
        assertTrue(ok.isStatic && ok.priority == FxPriority.AMBIENT && ok.timeline.runLoopMs != null)
        assertFailsWith<IllegalArgumentException> { static("o", FxBand.OVERLAY) }
        assertFailsWith<IllegalArgumentException> { ok.copy(priority = FxPriority.HERO) }
    }
}
