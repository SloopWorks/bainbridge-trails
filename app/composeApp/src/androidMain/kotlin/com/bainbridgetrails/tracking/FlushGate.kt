package com.bainbridgetrails.tracking

/**
 * Matches `onFlushCompleted` callbacks to flush requests for one sensor listener. Android completes a listener's
 * flushes in request order, but the callback carries no id. So each request gets a token, and the k-th completion
 * belongs to the k-th outstanding token. Only the request still being waited for fires; a late completion for an
 * abandoned request (timed out, or its heartbeat cancelled) is consumed without firing, so it cannot complete a
 * newer flush (e.g. Stop's) early.
 *
 * Ordering assumption: one `onFlushCompleted` per accepted `SensorManager.flush(listener)`, delivered in request
 * order. The framework documents one completion per flush, but not the order across several pending flushes, nor
 * that none is ever lost. So the gate resyncs (forgets every outstanding token) after [MAX_CONSECUTIVE_TIMEOUTS]
 * timeouts in a row, or when more than [MAX_OUTSTANDING] tokens are open. If a stale completion arrives right
 * after a resync, it can fire the next flush early: one heartbeat written a little early, which gating tolerates.
 *
 * Usage: [begin] before `SensorManager.flush`; [refused] if that returns false (no completion will come);
 * [timedOut] when the wait timed out; [abandon] when giving up otherwise (cancelled); [onCompleted] from
 * `onFlushCompleted`. Thread-safe.
 */
internal class FlushGate {
    class Ticket internal constructor(internal val token: Long, internal val onFlushed: () -> Unit)

    private var nextToken = 0L
    private val outstanding = ArrayDeque<Long>()
    private var waiting: Ticket? = null
    private var consecutiveTimeouts = 0

    /** Registers a new request; it becomes the only one whose completion fires [onFlushed]. */
    @Synchronized
    fun begin(onFlushed: () -> Unit): Ticket {
        if (outstanding.size > MAX_OUTSTANDING) outstanding.clear() // completions were lost: resync
        val t = Ticket(++nextToken, onFlushed)
        outstanding.addLast(t.token)
        waiting = t
        return t
    }

    /** The flush request was refused: no completion will arrive for [t]. */
    @Synchronized
    fun refused(t: Ticket) {
        outstanding.remove(t.token)
        if (waiting === t) waiting = null
    }

    /** Stops waiting for [t]; its completion, if it still arrives, is consumed silently. */
    @Synchronized
    fun abandon(t: Ticket) {
        if (waiting === t) waiting = null
    }

    /** The wait for [t] timed out. Abandons it; after [MAX_CONSECUTIVE_TIMEOUTS] in a row, assumes completions were lost. */
    @Synchronized
    fun timedOut(t: Ticket) {
        if (waiting === t) waiting = null
        if (++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
            outstanding.clear()
            consecutiveTimeouts = 0
        }
    }

    /** Call from `onFlushCompleted`. Runs the waiter's callback (outside the lock) only if this completion is its own. */
    fun onCompleted() {
        val fire = synchronized(this) {
            val token = outstanding.removeFirstOrNull() ?: return
            consecutiveTimeouts = 0
            val w = waiting
            if (w != null && w.token == token) {
                waiting = null
                w
            } else {
                null
            }
        } ?: return
        fire.onFlushed()
    }

    private companion object {
        const val MAX_CONSECUTIVE_TIMEOUTS = 2
        const val MAX_OUTSTANDING = 4
    }
}
