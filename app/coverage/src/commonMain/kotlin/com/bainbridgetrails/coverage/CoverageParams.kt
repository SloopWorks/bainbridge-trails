package com.bainbridgetrails.coverage

/** Provisional (owner, 2026-10-18): option B from docs/spikes/coverage/notes.md; re-check against real GPX walks before v1. */
data class CoverageParams(
    val bufferM: Double = 30.0,
    val tickM: Double = 5.0,
    /** null = no heading check */
    val headingToleranceDeg: Double? = null,
    /** min straight-line displacement for a heading; points without one are stationary and credit nothing */
    val headingBaselineM: Double = 15.0,
    /** a fix credits only segments within d* + matchMarginM of its nearest candidate segment (competitive map matching) */
    val matchMarginM: Double = 1.0,
    val maxAccuracyM: Double = 35.0,
    val maxSpeedMps: Double = 4.0,
    val speedWindowS: Double = 30.0,
    val maxGapS: Double = 60.0,
    val maxJumpM: Double = 100.0,
    /** single-fix GPS spike: both legs > spikeM while its neighbours are < spikeM / 2 apart (filterFixes rule 2b) */
    val spikeM: Double = 30.0,
    /** filterFixes rule 6: each fix moves to the mean of its piece's fixes within +/- smoothWindowS / 2; 0 = off */
    val smoothWindowS: Double = 10.0,
    val completionThreshold: Double = 0.85,
    val nearlyDoneThreshold: Double = 0.75,
)
