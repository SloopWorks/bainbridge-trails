package com.bainbridgetrails.tracking

/**
 * Matches `onFlushCompleted` callbacks to flush requests for one sensor listener. Android completes a listener's
 * flushes in request order, but the callback carries no id. So each request gets a token, and the k-th completion
 * belongs to the k-th outstanding token. Only the request still being waited for fires; a late completion for an
 * abandoned request (timed out, or its heartbeat cancelled) is consumed without firing, so it cannot complete a
 * newer flush (e.g. Stop's) early.
 *
 * Usage: [begin] before `SensorManager.flush`; [refused] if that returns false (no completion will come);
 * [abandon] when giving up on waiting; [onCompleted] from `onFlushCompleted`. Thread-safe.
 */
internal class FlushGate {
    class Ticket internal constructor(internal val token: Long, internal val onFlushed: () -> Unit)

    private var nextToken = 0L
    private val outstanding = ArrayDeque<Long>()
    private var waiting: Ticket? = null

    /** Registers a new request; it becomes the only one whose completion fires [onFlushed]. */
    @Synchronized
    fun begin(onFlushed: () -> Unit): Ticket {
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

    /** Call from `onFlushCompleted`. Runs the waiter's callback (outside the lock) only if this completion is its own. */
    fun onCompleted() {
        val fire = synchronized(this) {
            val token = outstanding.removeFirstOrNull() ?: return
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
}
