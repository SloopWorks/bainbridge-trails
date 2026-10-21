package com.bainbridgetrails.nav

import com.bainbridgetrails.play.PlayTideOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** `-btPlayClock` / `--es playClock`: island wall time (America/Los_Angeles), whatever the device zone. */
class DebugLaunchTest {
    @Test
    fun playClockParsesIslandTime() {
        // PDT (UTC−7) before the Nov 1 fall-back, PST (UTC−8) after.
        assertEquals(Instant.parse("2026-10-29T21:00:00Z"), DebugLaunch.parsePlayClock("2026-10-29T14:00"))
        assertEquals(Instant.parse("2026-11-05T22:00:00Z"), DebugLaunch.parsePlayClock("2026-11-05T14:00"))
        assertEquals(Instant.parse("2027-07-04T21:00:00Z"), DebugLaunch.parsePlayClock("2027-07-04T14:00"))
        // A spring-forward gap moves to the first valid instant (02:30 → 03:00 PDT).
        assertEquals(Instant.parse("2027-03-14T10:00:00Z"), DebugLaunch.parsePlayClock("2027-03-14T02:30"))
        assertNull(DebugLaunch.parsePlayClock("tomorrow"))
        assertNull(DebugLaunch.parsePlayClock(null))
    }

    @Test
    fun playTideAndConfigParse() {
        assertEquals(PlayTideOverride.LOW, DebugLaunch.parsePlayTide("low"))
        assertEquals(PlayTideOverride.HIGH, DebugLaunch.parsePlayTide("HIGH"))
        assertEquals(PlayTideOverride.EXPIRED, DebugLaunch.parsePlayTide("expired"))
        assertEquals(PlayTideOverride.NONE, DebugLaunch.parsePlayTide("sideways"))
        assertEquals(PlayTideOverride.NONE, DebugLaunch.parsePlayTide(null))
    }

    /** Android `--es screen settings`: a tab-shell screen, not a spike (it used to fall through to the Record spike). */
    @Test
    fun shellScreenOptionsAreNotSpikes() {
        assertEquals("settings", DebugLaunch.shellScreen(mapOf("screen" to "settings")))
        assertEquals("trail:t-1", DebugLaunch.shellScreen(mapOf("screen" to "trail:t-1", "playClock" to "x")))
        for (spike in listOf("record", "style", "showcase", "fx", "FX")) assertNull(DebugLaunch.shellScreen(mapOf("screen" to spike)))
        assertNull(DebugLaunch.shellScreen(mapOf("style" to "DARK")))
        assertNull(DebugLaunch.shellScreen(emptyMap()))
    }
}
