package com.bainbridgetrails.tracking

/**
 * Ruling R8: a fix taken before the session (re)started is a cached position from before the walk, not part of it,
 * and would add a jump to the track and distance. Fix times come from the location provider (GNSS-derived) while
 * the session start comes from the device wall clock, so the two can disagree by a few seconds; a fix up to
 * [CLOCK_SKEW_TOLERANCE_MS] before the start still counts. Cached positions are typically far older than that.
 */
object SessionFix {
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 3_000L

    fun isSessionFix(fixTimeMs: Long, sessionStartMs: Long): Boolean = fixTimeMs >= sessionStartMs - CLOCK_SKEW_TOLERANCE_MS
}
