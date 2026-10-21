package com.bainbridgetrails.play.rng

/**
 * SplitMix64 (Steele, Lea, Flood, OOPSLA 2014; reference C: prng.di.unimi.it/splitmix64.c), schedule.md §2.3.
 * 64-bit exact on every target (ULong arithmetic wraps the same on JVM, Android and Kotlin/Native). Not thread-safe.
 */
class SplitMix64(seed: ULong) {
    private var state: ULong = seed

    /** Next 64 random bits: add the golden gamma, then [mix64]. */
    fun next(): ULong {
        state += GAMMA
        return mix64(state)
    }

    /** Uniform in [0, bound): Lemire's multiply-shift with rejection (unbiased). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        val b = bound.toULong()
        var m = (next() shr 32) * b
        var low = m and 0xFFFF_FFFFuL
        if (low < b) {
            val threshold = (0x1_0000_0000uL - b) % b
            while (low < threshold) {
                m = (next() shr 32) * b
                low = m and 0xFFFF_FFFFuL
            }
        }
        return (m shr 32).toInt()
    }

    /** Uniform in [0, 1) with 53 random bits. */
    fun nextDouble(): Double = (next() shr 11).toDouble() * TWO_POW_MINUS_53

    /**
     * [k] distinct members of [items] (all of them when k ≥ size): partial Fisher–Yates over a copy sorted by the
     * stable [id], so the order of [items] (pool order in the JSON) never changes the result.
     */
    fun <T> pick(k: Int, items: List<T>, id: (T) -> String): List<T> {
        val pool = items.sortedBy(id).toMutableList()
        val n = minOf(k.coerceAtLeast(0), pool.size)
        for (i in 0 until n) {
            val j = i + nextInt(pool.size - i)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        return pool.subList(0, n).toList()
    }

    companion object {
        const val GAMMA: ULong = 0x9E3779B97F4A7C15uL
        private const val TWO_POW_MINUS_53 = 1.0 / (1L shl 53)

        /** The SplitMix64 finalizer (also the seed mixer in schedule.md §2.2). */
        fun mix64(z0: ULong): ULong {
            var z = z0
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }
    }
}
