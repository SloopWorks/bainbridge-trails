package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.fx.FxClockPolicy.Mode
import com.bainbridgetrails.play.fx.effects.ExplorationFog
import com.bainbridgetrails.play.fx.effects.FerryCrossing
import com.bainbridgetrails.play.fx.effects.FrogRockStamp
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.fx.effects.KrakenLurk
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureFound
import com.bainbridgetrails.play.fx.effects.TreasureGlint
import com.bainbridgetrails.play.fx.effects.live
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FxLayerDriverTest {
    private val at = GeoPoint(lat = 47.701, lon = -122.508)
    private val values = HashMap<String, Double>()
    private val sink = FxGlobalStateSink { k, v -> values[k] = v }

    @Test
    fun `rest values written on stop`() {
        val riddle = RiddleMarker()
        val registry = FxRendererRegistry(listOf(FrogRockStamp, KrakenLurk, TreasureGlint, riddle))
        val byKind = mapOf(
            FrogRockStamp.kind to listOf(live(FrogRockStamp.spec(), 1)),
            KrakenLurk.kind to listOf(live(KrakenLurk.spec("lurk", at), 2)),
            TreasureGlint.kind to listOf(live(TreasureGlint.spec("t#1", at), 3)),
            RiddleMarker.KIND to listOf(live(RiddleMarker.spec("r1", at, lit = false), 4)),
        )
        val driver = FxLayerDriver(registry, sink)
        // Run until the frog's eyes are shut and the lurk eye is up (mid-motion), then stop.
        val seedFrog = FrogRockStamp.spec().seed
        val shutAt = (0L..20_000L step 33).first { FrogRockStamp.blinkAt(it, seedFrog) == 1.0 }
        driver.onMode(Mode.AMBIENT_30HZ, byKind, shutAt)
        driver.frame(byKind, shutAt, view = null)
        assertEquals(1.0, values[FrogRockStamp.KEY_BLINK])
        assertTrue(values[TreasureGlint.KEY_T]!! >= 0.0)
        assertNotEquals(0, riddle.sway)
        driver.onMode(Mode.STOPPED, byKind, shutAt + 33)
        assertEquals(0.0, values[FrogRockStamp.KEY_BLINK], "frog eyes open at rest")
        assertEquals(KrakenLurk.REST_A, values[KrakenLurk.KEY_A])
        assertEquals(1.0, values[KrakenLurk.KEY_B], "second ring faded out")
        assertEquals(0.0, values[KrakenLurk.KEY_EYE])
        assertEquals(0.0, values[KrakenLurk.KEY_BLINK])
        assertEquals(TreasureGlint.REST_T, values[TreasureGlint.KEY_T], "glint matte")
        assertEquals(0, riddle.sway, "lantern hangs straight")
        assertEquals(RiddleMarker.REST_FLICKER, values[RiddleMarker.KEY_FLICKER])
        // Only the transition writes: STOPPED → STOPPED is a no-op.
        values.clear()
        driver.onMode(Mode.STOPPED, byKind, shutAt + 66)
        assertTrue(values.isEmpty())
    }

    @Test
    fun `kraken rest is the full casing unless the intro is in flight`() {
        val attack = KrakenInk.Attack(
            "k", listOf(KrakenInk.Stretch("s", listOf(listOf(GeoPoint(lat = 47.66, lon = -122.50), GeoPoint(lat = 47.66, lon = -122.49))))), GeoPoint(lat = 47.661, lon = -122.50), true,
        )
        val parts = KrakenInk.specs(attack, emptySet())
        assertTrue(parts.all { it.isStatic }, "casing and water are static parts")
        KrakenInk.writeRestState(parts.mapIndexed { i, s -> live(s, i.toLong()) }, 0, sink)
        assertEquals(1.0, values[KrakenInk.KEY_REVEAL])
        assertEquals(0.0, values[KrakenInk.KEY_GRIP])
        val intro = live(KrakenInk.introSpec(attack, emptySet())!!, 9)
        KrakenInk.writeRestState(listOf(intro), 1_000, sink) // t = 1 000: grip up, reveal not started
        assertEquals(KrakenInk.revealAt(1_000).toDouble(), values[KrakenInk.KEY_REVEAL])
        assertEquals(KrakenInk.gripAt(1_000).toDouble(), values[KrakenInk.KEY_GRIP])
    }

    @Test
    fun `ferry does not tick when clock stopped`() {
        val tt = FerryTimetable.parse(
            """{"route":"seattle-bainbridge","crossingMin":35,"validUntil":"2026-12-26","timetables":[{"id":"t","from":"2026-09-20","to":"2026-12-26",
               "weekday":{"fromSeattle":["07:55"],"fromBainbridge":["08:35"]},"weekend":{"fromSeattle":["07:55"],"fromBainbridge":["08:35"]}}]}""",
        )
        var now: Instant = IslandTime.instantOf(LocalDate(2026, 10, 21), LocalTime(8, 36))
        var realMs = 0L
        val ferry = FerryCrossing(tt, now = { now }, elapsedMs = { realMs })
        val byKind = mapOf(FerryCrossing.KIND to listOf(live(FerryCrossing.spec(), 1)))
        val driver = FxLayerDriver(FxRendererRegistry(listOf(ferry)), sink)
        val start = ferry.json
        // The clock is stopped: time passes, nothing drives the ferry, the boat does not move (no loop of its own).
        now += 5.minutes
        realMs += 300_000
        assertEquals(start, ferry.json)
        // A stopped gate publishes nothing, so FxHost never calls the driver's frame.
        val gate = FxFrameGate()
        assertEquals(0, gate.onFrame(1_000_000_000L, Mode.STOPPED) and FxFrameGate.LAYER)
        // The clock runs: the next driver frame moves it, and further frames within 1 s do not.
        driver.frame(byKind, 0, view = null)
        val moved = ferry.json
        assertNotEquals(start, moved)
        now += 10.seconds
        realMs += 500
        driver.frame(byKind, 33, view = null)
        assertEquals(moved, ferry.json, "≤ 1 Hz")
        realMs += 500
        driver.frame(byKind, 66, view = null)
        assertNotEquals(moved, ferry.json)
        // Stop: the rest frame shows the boat where it is now (and once a minute under reduced motion).
        now += 1.minutes
        val beforeStop = ferry.json
        driver.onMode(Mode.AMBIENT_30HZ, byKind, 66)
        driver.onMode(Mode.STOPPED, byKind, 99)
        assertNotEquals(beforeStop, ferry.json)
    }

    @Test
    fun `unchanged kinds keep their live list`() {
        val a = listOf(live(TreasureGlint.spec("t#1", at), 1))
        val b = listOf(live(FrogRockStamp.spec(), 2))
        val prev = mapOf("a" to a, "b" to b)
        val nextA = a.map { it.copy(t = 500) } // same instance set, a later frame
        val nextB = listOf(live(FrogRockStamp.spec(), 2), live(FrogRockStamp.spec("frog-2"), 3))
        val out = FxLayerDriver.reuseUnchanged(prev, mapOf("a" to nextA, "b" to nextB))
        assertTrue(out["a"] === a, "kind a's GeoJSON is not rebuilt")
        assertTrue(out["b"] === nextB)
    }

    @Test
    fun `fog, found shells and lit pins spawn static`() {
        val sq = com.bainbridgetrails.play.geo.ChartSquare.of(lat = 47.63, lon = -122.52)
        val fog = ExplorationFog.specs(listOf(sq), lifted = emptySet(), quiet = emptyList())
        assertTrue(fog.isNotEmpty() && fog.all { it.isStatic })
        assertTrue(TreasureFound.spentSpec("t", at).isStatic)
        assertTrue(RiddleMarker.spec("r", at, lit = true).isStatic)
        assertTrue(!RiddleMarker.spec("r", at, lit = false).isStatic, "an unlit lantern sways")
        assertTrue(!TreasureGlint.spec("g", at).isStatic && !FerryCrossing.spec().isStatic)
    }
}
