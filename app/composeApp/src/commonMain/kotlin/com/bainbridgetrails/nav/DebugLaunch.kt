package com.bainbridgetrails.nav

/**
 * Debug-binary launch options for simulator screenshots (iOS: `-btScreen me -btScroll 4 -btPage 2 -btDevSignIn 1`,
 * read in MainViewController). Release never sets them. [screen]: trails, me, leaderboard, profile, settings,
 * attribution, privacy, onboarding, trail:<trailId> (Trail detail), walk (newest walk) / walk:<sessionId>
 * (Walk detail), celebrate-island (island 100% celebration preview), fx (MapFx spike). [scroll]: first visible list item on that screen. [page]: onboarding page.
 */
object DebugLaunch {
    var screen: String? = null
    var scroll: Int = 0
    var page: Int = 0
    var devSignIn: Boolean = false
}
