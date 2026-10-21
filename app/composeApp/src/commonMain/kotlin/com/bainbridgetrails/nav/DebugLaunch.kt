package com.bainbridgetrails.nav

import com.bainbridgetrails.play.DebugPlayClock
import com.bainbridgetrails.play.PlayClock
import com.bainbridgetrails.play.PlayOverrides
import com.bainbridgetrails.play.PlayTideOverride
import kotlin.time.Instant

/**
 * Debug-binary launch options for simulator screenshots (iOS: `-btScreen me -btScroll 4 -btPage 2 -btDevSignIn 1`,
 * read in MainViewController). Release never sets them. [screen]: trails, me, leaderboard, profile, settings,
 * attribution, privacy, onboarding, trail:<trailId> (Trail detail), walk (newest walk) / walk:<sessionId>
 * (Walk detail), celebrate-island (island 100% celebration preview), logbook / whats-on / challenges / ranks / stories (Play screens, Play on), fx (MapFx spike). [scroll]: first visible list item on that screen. [page]: onboarding page.
 *
 * Play (schedule.md § 4.1, plan.md L2-3; iOS `-btPlayClock 2026-10-29T14:00 -btPlayTide low -btPlayConfig bundled`,
 * Android debug `--es playClock … --es playTide … --es playConfig bundled`): [playClock] = island wall time the Play
 * clock starts at (it then ticks), [playTide] = `low|high|expired`, [playConfig] = `bundled` (no content fetch).
 */
object DebugLaunch {
    var screen: String? = null
    var scroll: Int = 0
    var page: Int = 0
    var devSignIn: Boolean = false
    var playClock: String? = null
    var playTide: String? = null
    var playConfig: String? = null

    /** Android spike screens (`--es screen record|style|showcase|fx`, SpikeLaunch); any other `screen` is a tab-shell screen. */
    private val SPIKE_SCREENS = setOf("record", "style", "showcase", "fx")

    /**
     * Android debug `--es screen settings` (and the other [screen] names): the tab-shell screen to open, or null for a
     * spike screen / no `screen`. Without this every `screen` extra opened a spike (unknown names: the Record spike).
     */
    fun shellScreen(options: Map<String, String>): String? =
        options["screen"]?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in SPIKE_SCREENS }

    /** Android: binds a tab-shell launch (`screen`, `scroll`, `page`, `devSignIn`); true when [options] named one. */
    fun applyShellOptions(options: Map<String, String>): Boolean {
        val shell = shellScreen(options) ?: return false
        screen = shell
        scroll = options["scroll"]?.toIntOrNull() ?: 0
        page = options["page"]?.toIntOrNull() ?: 0
        devSignIn = options["devSignIn"].let { it == "1" || it.equals("true", ignoreCase = true) }
        return true
    }

    /** `2026-10-29T14:00` read as island time (America/Los_Angeles); null when absent or unparseable. */
    fun parsePlayClock(text: String?): Instant? = PlayClock.parseIsland(text)

    fun parsePlayTide(text: String?): PlayTideOverride = when (text?.trim()?.lowercase()) {
        "low" -> PlayTideOverride.LOW
        "high" -> PlayTideOverride.HIGH
        "expired" -> PlayTideOverride.EXPIRED
        else -> PlayTideOverride.NONE
    }

    /** A debug Play clock carrying the Play launch options (debug entry points only; release binds the system clock). */
    fun playClockFromLaunch(): DebugPlayClock {
        val clock = DebugPlayClock(
            initial = PlayOverrides(tide = parsePlayTide(playTide), bundledConfig = playConfig?.trim()?.lowercase() == "bundled"),
        )
        parsePlayClock(playClock)?.let { clock.startAt(it) }
        return clock
    }
}
