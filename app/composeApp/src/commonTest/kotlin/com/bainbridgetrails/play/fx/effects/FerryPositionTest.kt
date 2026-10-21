package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FerryPositionTest {
    private val tt = FerryTimetable.parse(
        """{"route":"seattle-bainbridge","crossingMin":35,"validUntil":"2026-12-26","timetables":[{"id":"t","from":"2026-09-20","to":"2026-12-26",
           "weekday":{"fromSeattle":["07:55"],"fromBainbridge":["08:35"]},"weekend":{"fromSeattle":["07:55"],"fromBainbridge":["08:35"]}}]}""",
    )
    private val wed = LocalDate(2026, 10, 21)
    private fun at(h: Int, m: Int) = IslandTime.instantOf(wed, LocalTime(h, m))
    private val route = FerryCrossing.Route.Default

    @Test
    fun positionOnTimetable() {
        // Leaving Bainbridge at 08:35: 5 of 35 minutes out = 1/7 of the whole route from the terminal.
        val out = FerryCrossing.boats(tt, route, at(8, 35) + 5.minutes).single()
        assertTrue(!out.docked && out.outbound)
        assertTrue(abs(out.distanceM - route.totalM / 7) < 1.0, "distance ${out.distanceM} vs ${route.totalM / 7}")
        // Arriving from Seattle (07:55 + 35 = 08:30): at 08:25 it is 5/35 of the route out, inbound.
        val inbound = FerryCrossing.boats(tt, route, at(8, 25)).single()
        assertTrue(!inbound.outbound)
        assertTrue(abs(inbound.distanceM - route.totalM / 7) < 1.0)
        // Between 08:30 (arrival) and 08:35 (departure) the boat is docked at the slip.
        val docked = FerryCrossing.boats(tt, route, at(8, 32)).single()
        assertTrue(docked.docked)
        assertEquals(route.leg.first(), docked.at)
        // Mid-Sound (beyond the island leg inside the camera bounds) the boat is not drawn.
        assertTrue(FerryCrossing.boats(tt, route, at(8, 35) + 30.minutes).isEmpty())
    }

    @Test
    fun hiddenWhenExpired() {
        val after = IslandTime.instantOf(LocalDate(2027, 6, 2), LocalTime(8, 40))
        assertTrue(FerryCrossing.boats(tt, route, after).isEmpty())
    }

    @Test
    fun oneHzCap() {
        val gate = FerryCrossing.UpdateGate()
        var updates = 0
        var ms = 0L
        while (ms <= 10_000) {
            if (gate.due(ms)) updates++
            ms += 16
        }
        assertTrue(updates in 10..11, "$updates updates in 10 s of 60 Hz frames")
        val g = FerryCrossing.UpdateGate()
        assertTrue(g.due(0)); assertTrue(!g.due(999)); assertTrue(g.due(1_000)); assertTrue(!g.due(1_500))
        // Reduced motion: at most once a minute (product PF3-4).
        val r = FerryCrossing.UpdateGate(FerryCrossing.REDUCED_INTERVAL_MS)
        assertTrue(r.due(0)); assertTrue(!r.due(59_999)); assertTrue(r.due(60_000))
    }
}
