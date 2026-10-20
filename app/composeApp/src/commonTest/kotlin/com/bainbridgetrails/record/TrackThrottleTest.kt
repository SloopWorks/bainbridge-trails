package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.Fix
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackThrottleTest {
    private val lat = 47.61
    private fun fix(t: Long, eastM: Double) = Fix(t, -122.56 + eastM / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180)), lat)

    @Test
    fun firstFixAndResetRebuild() {
        assertTrue(TrackThrottle.shouldRebuild(emptyList(), listOf(fix(0, 0.0))))
        assertTrue(TrackThrottle.shouldRebuild(listOf(fix(0, 0.0)), emptyList()))
        assertTrue(TrackThrottle.shouldRebuild(listOf(fix(0, 0.0), fix(1_000, 1.0)), listOf(fix(9_000, 0.0))), "new session")
        assertFalse(TrackThrottle.shouldRebuild(emptyList(), emptyList()))
    }

    @Test
    fun waitsForFiveSecondsOrTenMetres() {
        val drawn = listOf(fix(0, 0.0))
        assertFalse(TrackThrottle.shouldRebuild(drawn, drawn + fix(2_000, 2.6)))
        assertFalse(TrackThrottle.shouldRebuild(drawn, drawn + fix(2_000, 2.6) + fix(4_000, 5.2)))
        assertTrue(TrackThrottle.shouldRebuild(drawn, drawn + fix(2_000, 2.6) + fix(5_000, 6.0)), "5 s elapsed")
        assertTrue(TrackThrottle.shouldRebuild(drawn, drawn + fix(1_000, 10.5)), "moved 10 m")
        assertFalse(TrackThrottle.shouldRebuild(drawn, drawn))
    }
}
