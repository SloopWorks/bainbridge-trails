package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** PD-8 / product T-2: a kraken stretch is active only while the predicted Eagle Harbor tide is ≤ 2.44 m MLLW. */
class TideGateTest {
    private val max = RealPack.config.rules.krakenMaxTideM
    private val s = RealPack.scheduler()

    /** Every daylight minute of the kraken windows in the first [weeks] weeks of 2027, with the occurrence. */
    private fun krakenMinutes(weeks: Int): Sequence<Pair<Instant, Occurrence>> = sequence {
        for (i in 0 until weeks * 7) {
            val plan = s.dayPlan(LocalDate(2027, 1, 7).plus(i, DateTimeUnit.DAY), Goal.roads)
            val k = plan.occurrences.firstOrNull { it.itemId == "kraken-roads" } ?: continue
            for (span in k.daySpans) {
                var t = span.start
                while (t < span.end) {
                    yield(t to k)
                    t += 1.minutes
                }
            }
        }
    }

    @Test
    fun `inactive above 2_44 m`() {
        assertEquals(2.44, max)
        val (high, occ) = krakenMinutes(4).firstOrNull { (t, _) -> RealPack.tides.heightAt(t)!! > max } ?: fail("no high daylight tide")
        assertEquals(TideGate.State.HIGH, TideGate.state(RealPack.tides, high, max))
        assertTrue(occ.resolvable.none { high in it }, "not resolvable at $high")
        val a = s.agenda(high, PlayContext(Goal.roads))
        assertEquals(Gate.TIDE_HIGH, a.active.single { it.occurrence.itemId == "kraken-roads" }.gate)
        val (low, _) = krakenMinutes(4).first { (t, _) -> RealPack.tides.heightAt(t)!! <= max - 0.05 }
        assertEquals(Gate.OPEN, s.agenda(low, PlayContext(Goal.roads)).active.single { it.occurrence.itemId == "kraken-roads" }.gate)
    }

    @Test
    fun `low spans agree with the sampled tide`() {
        var low = 0
        var high = 0
        for ((t, occ) in krakenMinutes(8)) {
            val h = RealPack.tides.heightAt(t)!!
            val inside = occ.resolvable.any { t in it }
            if (h <= max - 0.005) { assertTrue(inside, "$t h=$h should be active"); low++ }
            if (h > max + 0.005) { assertTrue(!inside, "$t h=$h should be inactive"); high++ }
        }
        assertTrue(low > 1000 && high > 100, "both states sampled: $low low, $high high")
    }

    @Test
    fun `inactive without table`() {
        val noTable = RealPack.scheduler(tides = null)
        val k = noTable.dayPlan(LocalDate(2027, 1, 9), Goal.roads).occurrences.single { it.itemId == "kraken-roads" }
        assertTrue(k.tideGated && k.daySpans.isNotEmpty() && k.resolvable.isEmpty())
        val noon = Instant.parse("2027-01-09T20:00:00Z")
        assertEquals(TideGate.State.UNKNOWN, TideGate.state(null, noon, max))
        assertEquals(Gate.TIDE_UNKNOWN, noTable.agenda(noon, PlayContext(Goal.roads)).active.single { it.occurrence.itemId == "kraken-roads" }.gate)
        // a date past the bundled years (2026–2029) fails closed too
        val late = s.dayPlan(LocalDate(2030, 1, 5), Goal.roads).occurrences.single { it.itemId == "kraken-roads" }
        assertTrue(late.resolvable.isEmpty())
        assertNotNull(late.daySpans.firstOrNull())
    }
}
