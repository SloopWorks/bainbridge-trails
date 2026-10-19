package com.bainbridgetrails.tracking

/** Interval between [SessionEvent.Diag] events while recording. */
const val DIAG_INTERVAL_MS = 5 * 60 * 1000L

/** Battery percent from a level/scale pair (Android BatteryManager extras); null if unreadable. */
fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else (level * 100L / scale).toInt()

/**
 * Collects fix count and accuracy between diag ticks. Not thread-safe: feed and flush from one thread
 * (the recorders call [onFix] from their fix callback and [flush] from the same context, or guard externally).
 */
class DiagAccumulator {
    private var count = 0
    private var accSum = 0.0
    private var accCount = 0

    fun onFix(accuracyM: Double?) {
        count++
        if (accuracyM != null) {
            accSum += accuracyM
            accCount++
        }
    }

    /** Builds the Diag for the window since the last flush and resets the window. */
    fun flush(timeMs: Long, batteryPct: Int?): SessionEvent.Diag {
        val d = SessionEvent.Diag(timeMs, batteryPct, count, if (accCount == 0) null else accSum / accCount)
        count = 0
        accSum = 0.0
        accCount = 0
        return d
    }
}

/** [SessionEvent.Diag.fixCount] value marking a tick request from the timer; the writer swaps it for a real Diag. */
const val DIAG_TICK = -1

/** Timer side: a placeholder carrying the time and battery reading. The writer resolves it via [DiagAccumulator.resolve]. */
fun diagTick(timeMs: Long, batteryPct: Int?) = SessionEvent.Diag(timeMs, batteryPct, DIAG_TICK, null)

/**
 * Writer side (single consumer, ruling R4): counts fixes as they pass and turns a tick into the real Diag.
 * Returns the event to persist.
 */
fun DiagAccumulator.resolve(event: SessionEvent): SessionEvent = when {
    event is SessionEvent.FixEvent -> { onFix(event.accuracyM); event }
    event is SessionEvent.Diag && event.fixCount == DIAG_TICK -> flush(event.timeMs, event.batteryPct)
    else -> event
}
