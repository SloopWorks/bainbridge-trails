package com.bainbridgetrails.coverage

/** Map matcher used by [SegmentIndex.coverFixes]. */
enum class Matcher { COMPETITIVE, HMM }

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
    /** COMPETITIVE = Spike 3 per-point matching; HMM = [HmmMatcher] (Spike 3b) */
    val matcher: Matcher = Matcher.COMPETITIVE,
    /** HMM: observation spacing along each piece's path */
    val hmmStepM: Double = 10.0,
    /** HMM: log emission = -d^2 / (2 sigma^2), d = distance to the candidate tick */
    val emissionSigmaM: Double = 10.0,
    /** HMM: log transition = -|route - straight| / beta */
    val transitionBetaM: Double = 5.0,
    /**
     * HMM: the OFF (off-network) state's emission equals a candidate's at this distance. Consequence: a walk parallel to
     * a lone trail closer than ~this (and within bufferM) is credited to the trail; beyond it, OFF wins (tests
     * offNetworkBesideTrailCreditsNothing at 27.5 m, besideTrailThresholdDocumented at 15 m).
     */
    val offNetworkM: Double = 25.0,
    /** HMM: nats charged entering or leaving OFF */
    val offSwitchPenalty: Double = 3.0,
    /** HMM: [TickGraph] junction radius */
    val junctionM: Double = 3.0,
    /** HMM: nearest distinct segments kept per observation */
    val maxCandidates: Int = 8,
)
