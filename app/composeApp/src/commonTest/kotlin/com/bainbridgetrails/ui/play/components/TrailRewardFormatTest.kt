package com.bainbridgetrails.ui.play.components

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.ui.Milestones
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/play/trails/ux.md § 1 (UX-3): climb stored in metres, trail miles in miles; miles setting shows ft + mi,
 * kilometres shows m + km; so-far values floor, steps/targets round to nearest; the clamp never shows an unfinished
 * step as reached nor an earned one as short.
 */
class TrailRewardFormatTest {
    private val mi = Units.MILES
    private val km = Units.KILOMETRES
    private val mile = 1609.344

    @Test
    fun `99_9 m shows 327 ft`() {
        assertEquals("327 ft", TrailRewardFormat.climb(99.9, mi).text)
        assertEquals("327 ft", TrailRewardFormat.climb(99.9, mi, targetM = 100.0).text)
        assertEquals("99 m", TrailRewardFormat.climb(99.9, km, targetM = 100.0).text)
    }

    @Test
    fun `100 m step shows 328 ft`() {
        assertEquals("328 ft", TrailRewardFormat.climbStep(100.0, mi).text)
        assertEquals("328 ft", TrailRewardFormat.climb(100.0, mi, targetM = 100.0).text)
        assertEquals("100 m", TrailRewardFormat.climbStep(100.0, km).text)
    }

    @Test
    fun `250 m = 820 ft`() {
        assertEquals("820 ft", TrailRewardFormat.climbStep(250.0, mi).text)
        assertEquals("250 m", TrailRewardFormat.climbStep(250.0, km).text)
    }

    @Test
    fun `climb steps as shown in ft`() {
        assertEquals(
            listOf("328", "820", "1,640", "2,461", "3,281", "4,101"),
            listOf(100, 250, 500, 750, 1000, 1250).map { TrailRewardFormat.climbStep(it.toDouble(), mi).number },
        )
        assertEquals("1,250 m", TrailRewardFormat.climbStep(1250.0, km).text)
    }

    @Test
    fun `149_9 of 150 m shows 149 of 150 m`() {
        val (p, t) = TrailRewardFormat.climbProgress(149.9, 150.0, km)
        assertEquals("149 m" to "150 m", p.text to t.text)
        val (pf, tf) = TrailRewardFormat.climbProgress(149.9, 150.0, mi)
        assertEquals("491 ft" to "492 ft", pf.text to tf.text)
    }

    @Test
    fun `150 of 150 m`() {
        assertEquals("150 m" to "150 m", TrailRewardFormat.climbProgress(150.0, 150.0, km).let { it.first.text to it.second.text })
        assertEquals("492 ft" to "492 ft", TrailRewardFormat.climbProgress(150.0, 150.0, mi).let { it.first.text to it.second.text })
        // coerceAtMost(target) once done (ux § 7).
        assertEquals("150 m", TrailRewardFormat.climbProgress(171.3, 150.0, km).first.text)
        assertEquals("492 ft", TrailRewardFormat.climbProgress(171.3, 150.0, mi).first.text)
    }

    @Test
    fun `clamp never shows an unfinished step as reached`() {
        // 499.9 m = 1,640.09 ft floors to 1,640 = the shown 500 m step, but 4,999 dm < 5,000: one display unit less.
        assertEquals("1,639 ft", TrailRewardFormat.climb(499.9, mi, targetM = 500.0).text)
        assertEquals("1,639 ft", TrailRewardFormat.climbProgress(499.9, 500.0, mi).first.text)
        assertEquals("499 m", TrailRewardFormat.climb(499.9, km, targetM = 500.0).text)
        // 1,605 m = 1.605 km floors to 1.6 = the shown 1 mi step, not reached in miles.
        assertEquals("1.5 km", TrailRewardFormat.distance(1605.0, km, stepMi = 1).text)
    }

    @Test
    fun `clamp never shows an earned step as short`() {
        // 750 m = 2,460.6 ft floors to 2,460 under the 2,461 ft step it earned.
        assertEquals("2,461 ft", TrailRewardFormat.climb(750.0, mi, targetM = 750.0).text)
        // 10 mi = 16.09 km floors to 16.0 under the 16.1 km step it earned.
        assertEquals("16.1 km", TrailRewardFormat.distance(10 * mile, km, stepMi = 10).text)
        // A sum carrying float noise is still 150.0 m in integer decimetres (D6).
        assertEquals("150 m", TrailRewardFormat.climb(149.99999999, km, targetM = 150.0).text)
        assertEquals("492 ft", TrailRewardFormat.climbProgress(149.99999999, 150.0, mi).first.text)
    }

    @Test
    fun `1_207 ft`() {
        assertEquals("1,207 ft", TrailRewardFormat.climb(368.0, mi).text)
        assertEquals("368 m", TrailRewardFormat.climb(368.0, km).text)
        assertEquals("0 ft", TrailRewardFormat.climb(0.0, mi).text)
    }

    @Test
    fun `10 mi step = 16_1 km`() {
        assertEquals("10 mi", TrailRewardFormat.mileStep(10, mi).text)
        assertEquals("16.1 km", TrailRewardFormat.mileStep(10, km).text)
        assertEquals("1.6 km", TrailRewardFormat.mileStep(1, km).text)
        assertEquals("8.0 km", TrailRewardFormat.mileStep(5, km).text)
        assertEquals("96.6 km", TrailRewardFormat.mileStep(60, km).text)
    }

    @Test
    fun `trail distance so far floors to one decimal`() {
        assertEquals("7.4 mi", TrailRewardFormat.distance(11_920.0, mi).text) // 7.407 mi
        assertEquals("11.9 km", TrailRewardFormat.distance(11_920.0, km).text)
        assertEquals("7.3 mi", TrailRewardFormat.distance(11_909.0, mi).text) // 7.39998 mi: never rounds up
        assertEquals("0.0 mi", TrailRewardFormat.distance(0.0, mi, stepMi = 1).text)
        assertEquals("1.0 mi", TrailRewardFormat.distance(mile, mi, stepMi = 1).text)
        assertEquals("0.9 mi", TrailRewardFormat.distance(1609.0, mi, stepMi = 1).text)
    }

    @Test
    fun `ladder equals Milestones distanceStep`() {
        val ladder = TrailRewardFormat.mileLadder(60 * mile + 500)
        assertEquals(listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60), ladder)
        assertEquals(ladder.indices.map(Milestones::distanceStep), ladder)
        assertEquals(listOf(1, 5), TrailRewardFormat.mileLadder(5 * mile))
        assertEquals(emptyList(), TrailRewardFormat.mileLadder(1609.0))
    }

    @Test
    fun `units follow the setting and speak in full`() {
        assertEquals(TrailUnit.FEET, TrailRewardFormat.climb(1.0, mi).unit)
        assertEquals(TrailUnit.METRES, TrailRewardFormat.climb(1.0, km).unit)
        assertEquals(TrailUnit.MILES, TrailRewardFormat.mileStep(1, mi).unit)
        assertEquals(TrailUnit.KILOMETRES, TrailRewardFormat.mileStep(1, km).unit)
        assertEquals(1, TrailRewardFormat.mileStep(1, mi).pluralQuantity)
        assertEquals(TrailValue.OTHER, TrailRewardFormat.distance(mile, mi).pluralQuantity) // "1.0 miles"
        assertEquals(TrailValue.OTHER, TrailRewardFormat.climbStep(250.0, mi).pluralQuantity)
        assertEquals(
            listOf("play_unit_feet_a11y", "play_unit_metres_a11y", "play_unit_miles_a11y", "play_unit_km_a11y"),
            TrailUnit.entries.map { it.spoken.key },
        )
    }
}
