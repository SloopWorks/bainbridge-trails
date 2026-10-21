package com.bainbridgetrails.play.fx

/** What animates in the scene right now (from [FxScene.summary]). [animatingLayer]: a Layer-tier part animates. */
data class FxSceneSummary(
    val animatingHero: Boolean,
    val animatingNotice: Boolean,
    val animatingAmbient: Boolean,
    val animatingLayer: Boolean,
)

/**
 * The one frame clock's policy (engine.md PF-2 + spike corrections #6, #7). The clock runs `withFrameNanos` every
 * frame while not STOPPED and publishes a new frame time when [shouldPublish] says so — gated on elapsed frame time,
 * never `delay(33)`.
 */
object FxClockPolicy {
    enum class Mode { STOPPED, AMBIENT_30HZ, FULL }

    /** 1 000 / 30 Hz, floored: frames at 30 Hz multiples of 60/120 Hz vsync pass. */
    const val AMBIENT_MIN_INTERVAL_NANOS: Long = 33_000_000L
    const val MAP_MAX_FPS_WHILE_LAYER_ANIMATES: Int = 30

    /**
     * [powerSave]: Battery Saver (Android) / Low Power (iOS) — the map half of reduced motion (effects.md § 5: ambient
     * off, heroes on their final frames, fog static), so the clock stops whatever the scene holds.
     */
    fun mode(
        playOn: Boolean,
        resumed: Boolean,
        reduced: Boolean,
        scene: FxSceneSummary,
        msSinceInteraction: Long,
        recording: Boolean,
        idleStopMs: Long = FxCaps().idleStopMs,
        powerSave: Boolean = false,
    ): Mode = when {
        !playOn || !resumed || reduced || powerSave -> Mode.STOPPED
        scene.animatingHero || scene.animatingNotice -> Mode.FULL
        // Ambient rests on its final frame while recording (battery first) and after 20 s idle.
        scene.animatingAmbient && !recording && msSinceInteraction < idleStopMs -> Mode.AMBIENT_30HZ
        else -> Mode.STOPPED
    }

    /** Reduced-motion cadence of real-time Layer parts (the ferry, product PF3-4). */
    const val REDUCED_TICK_MS: Long = 60_000L

    /**
     * Reduced motion / power save stop the clock, but parts that follow real time (the ferry's position) still step
     * once per [REDUCED_TICK_MS], gated like ambient: map resumed, not recording, not idle.
     */
    fun reducedTick(
        playOn: Boolean,
        resumed: Boolean,
        reduced: Boolean,
        powerSave: Boolean,
        recording: Boolean,
        msSinceInteraction: Long,
        idleStopMs: Long = FxCaps().idleStopMs,
    ): Boolean = playOn && resumed && (reduced || powerSave) && !recording && msSinceInteraction < idleStopMs

    /** Publish this frame? FULL: every frame; AMBIENT_30HZ: when ≥ 33 ms elapsed since the last publish. */
    fun shouldPublish(mode: Mode, elapsedSincePublishNanos: Long): Boolean = when (mode) {
        Mode.STOPPED -> false
        Mode.FULL -> true
        Mode.AMBIENT_30HZ -> elapsedSincePublishNanos >= AMBIENT_MIN_INTERVAL_NANOS
    }

    /**
     * MapLibre `RenderOptions.maximumFps` to apply: 30 while the clock runs and any Layer-tier part animates
     * (a 30 Hz `globalState` write does not cap MapLibre, spike correction #6); null = restore the default.
     */
    fun mapMaxFps(mode: Mode, scene: FxSceneSummary): Int? =
        if (mode != Mode.STOPPED && scene.animatingLayer) MAP_MAX_FPS_WHILE_LAYER_ANIMATES else null
}
