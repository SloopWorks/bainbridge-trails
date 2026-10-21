package com.bainbridgetrails.play.fx

/** Scene caps and clock limits (engine.md PF-3, PF-2, §3 budgets). */
data class FxCaps(
    val hero: Int = 1,
    val notice: Int = 4,
    val ambientAnimated: Int = 12,
    val overlay: Int = 3,
    val heroCooldownMs: Long = 8_000,
    val heroQueueTtlMs: Long = 10_000,
    /** Overlay hand-off bound: an Overlay-tier part is DONE this long after its spawn (PF-1). */
    val overlayMaxMs: Long = 3_000,
    /** Ambient-only scenes stop the clock after this long without touch or camera move (PF-2). */
    val idleStopMs: Long = 20_000,
    /** Layer-tier `globalState` writes and AMBIENT publishes are capped at this rate in every mode. */
    val globalStateMaxHz: Int = 30,
)
