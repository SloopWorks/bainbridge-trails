package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one frame clock per map (engine PF-2). [frameMs] is the scene's own monotonic ms since the host started,
 * pause-aware (a stop never jumps timelines forward). Read it **only** in draw / layout phases, never in composition.
 * The loop itself lives in [FxHostState] (`withFrameNanos` every frame, published through [FxFrameGate]).
 */
@Stable
class FxClock internal constructor() {
    internal val frame = mutableLongStateOf(0L)

    /** Scene ms of the last published frame. Draw / layout phases only. */
    val frameMs: Long get() = frame.longValue

    /** Current policy mode (STOPPED while paused, reduced, idle or empty). */
    var mode: FxClockPolicy.Mode by mutableStateOf(FxClockPolicy.Mode.STOPPED)
        internal set
}

/**
 * Per-frame gate (spike S1 correction #7): the clock runs `withFrameNanos` every display frame and decides here what
 * to publish, gated on elapsed frame time, never `delay(33)`.
 * - scene time advances by the frame delta, clamped to [MAX_STEP_NANOS] (a hitch or a resume never jumps ahead);
 * - the overlay frame publishes per [FxClockPolicy.shouldPublish] (FULL: every frame, AMBIENT_30HZ: ≥ 33 ms);
 * - Layer-tier drivers (`globalState` writes) publish at most every 33 ms in every mode (PF-2, `globalStateMaxHz`).
 */
internal class FxFrameGate {
    private var lastNanos = -1L
    private var sinceOverlay = Long.MAX_VALUE / 2
    private var sinceLayer = Long.MAX_VALUE / 2
    private var sceneNanos = 0L

    val sceneMs: Long get() = sceneNanos / 1_000_000L

    /** The clock (re)starts: the next frame advances scene time by 0. */
    fun restart() {
        lastNanos = -1L
    }

    /** One display frame at [frameNanos]; returns [OVERLAY] / [LAYER] bits to publish. */
    fun onFrame(frameNanos: Long, mode: FxClockPolicy.Mode): Int {
        val dt = if (lastNanos < 0) 0L else (frameNanos - lastNanos).coerceIn(0L, MAX_STEP_NANOS)
        lastNanos = frameNanos
        sceneNanos += dt
        sinceOverlay += dt
        sinceLayer += dt
        var out = 0
        if (FxClockPolicy.shouldPublish(mode, sinceOverlay)) {
            sinceOverlay = 0
            out = out or OVERLAY
        }
        if (mode != FxClockPolicy.Mode.STOPPED && sinceLayer >= FxClockPolicy.AMBIENT_MIN_INTERVAL_NANOS) {
            sinceLayer = 0
            out = out or LAYER
        }
        return out
    }

    companion object {
        const val OVERLAY = 1
        const val LAYER = 2

        /** 100 ms: the largest step one frame may advance scene time. */
        const val MAX_STEP_NANOS = 100_000_000L
    }
}
