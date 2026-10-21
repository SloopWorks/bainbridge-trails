package com.bainbridgetrails.play.fx

/**
 * A ring of the last [capacity] samples (e.g. overlay draw ms) with nearest-rank percentiles (engine.md PF-7).
 * [add] never allocates; percentiles sort a preallocated scratch copy.
 */
class FxStatsWindow(val capacity: Int = 240) {
    init {
        require(capacity > 0)
    }

    private val ring = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var next = 0

    var count: Int = 0
        private set

    fun add(v: Float) {
        ring[next] = v
        next = if (next + 1 == capacity) 0 else next + 1
        if (count < capacity) count++
    }

    fun clear() {
        next = 0
        count = 0
    }

    /** Nearest-rank percentile, [p] in (0, 100]; 0 when empty. */
    fun percentile(p: Double): Float {
        if (count == 0) return 0f
        ring.copyInto(scratch, 0, 0, count)
        scratch.sort(0, count)
        val rank = kotlin.math.ceil(p / 100.0 * count).toInt().coerceIn(1, count)
        return scratch[rank - 1]
    }

    val p50: Float get() = percentile(50.0)
    val p95: Float get() = percentile(95.0)
    val p99: Float get() = percentile(99.0)
}
