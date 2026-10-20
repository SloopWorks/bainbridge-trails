package com.bainbridgetrails.tracking

/**
 * Interval of the step heartbeat: while steps are available the recorder writes a [StepSample] at least this often,
 * even when the count has not changed (Android's step counter reports only on change; iOS may withhold updates).
 * Gating can then tell "no steps" from "no data".
 */
const val STEP_HEARTBEAT_MS = 30_000L

/** [StepSample.cumulativeSteps] value marking a heartbeat request from the timer; the writer resolves it. */
internal const val STEP_TICK = -1L

/** Timer side (Android): a placeholder carrying the time. The writer turns it into a sample via [StepHeartbeat.resolve]. */
fun stepTick(timeMs: Long) = SessionEvent.Steps(StepSample(timeMs, STEP_TICK))

/**
 * Writer side (single consumer, ruling R4): remembers the last cumulative count that passed through and turns a
 * [stepTick] into a sample holding it. Returns null for a tick before any count is known (nothing to assert).
 */
class StepHeartbeat {
    private var last: Long? = null

    fun resolve(event: SessionEvent): SessionEvent? {
        if (event !is SessionEvent.Steps) return event
        if (event.sample.cumulativeSteps == STEP_TICK) {
            val n = last ?: return null
            return SessionEvent.Steps(StepSample(event.sample.timeMs, n))
        }
        last = event.sample.cumulativeSteps
        return event
    }
}
