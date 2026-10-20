package com.bainbridgetrails.ui

import com.bainbridgetrails.data.Units
import kotlin.math.floor
import kotlin.math.roundToLong

/** Formatting rules from docs/mvp/product.md (§2 F7, §6). */
object Format {
    private const val METRES_PER_MILE = 1609.344

    /**
     * A fraction as a percentage with one decimal, **rounded down** (decisions.md TL-5: never show 100% or 85%
     * before it is reached): "0%" for nothing, "<0.1%" for anything under 0.1%, "100%" only at exactly 1.
     * The server's `islandPct` uses the same rule.
     */
    fun percent(fraction: Double): String {
        if (fraction <= 0.0) return "0%"
        if (fraction >= 1.0) return "100%"
        // Tiny epsilon so binary noise (0.123 * 1000 = 122.99999…) does not drop a whole tenth.
        val tenths = floor(fraction * 1000 + 1e-9).toLong().coerceAtMost(999L)
        if (tenths == 0L) return "<0.1%"
        return "${tenths / 10}.${tenths % 10}%"
    }

    /**
     * Whole-number percentage for space-constrained spots (ProgressRing label), rounded down like [percent]:
     * "0%", "<1%" for anything under 1%, "100%" only at exactly 1.
     */
    fun percentWhole(fraction: Double): String {
        if (fraction <= 0.0) return "0%"
        if (fraction >= 1.0) return "100%"
        val whole = floor(fraction * 100 + 1e-9).toLong().coerceAtMost(99L)
        return if (whole == 0L) "<1%" else "$whole%"
    }

    /** Spoken duration for screen readers, minute precision: "1 hour 8 minutes", "12 minutes", "0 minutes". */
    fun durationSpoken(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        val hours = if (h == 1L) "1 hour" else "$h hours"
        val mins = if (m == 1L) "1 minute" else "$m minutes"
        return when {
            h == 0L -> mins
            m == 0L -> hours
            else -> "$hours $mins"
        }
    }

    /** Distance in the user's units with one decimal, e.g. "2.4 mi" / "3.9 km". */
    fun distance(metres: Double, units: Units): String {
        val v = when (units) {
            Units.MILES -> metres / METRES_PER_MILE
            Units.KILOMETRES -> metres / 1000.0
        }
        return "${oneDecimal(v)} ${if (units == Units.MILES) "mi" else "km"}"
    }

    /** Trail length (dataset miles) in the user's units. */
    fun lengthMi(miles: Double, units: Units): String = distance(miles * METRES_PER_MILE, units)

    /** "1:05:09" / "12:04" (m:ss under an hour). */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val two = { v: Long -> v.toString().padStart(2, '0') }
        return if (s >= 3600) "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}" else "${s / 60}:${two(s % 60)}"
    }

    fun oneDecimal(v: Double): String {
        val t = (v * 10).roundToLong()
        val sign = if (t < 0) "-" else ""
        val a = kotlin.math.abs(t)
        return "$sign${a / 10}.${a % 10}"
    }
}
