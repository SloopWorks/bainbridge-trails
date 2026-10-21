package com.bainbridgetrails.play.fx

import kotlin.math.abs

/**
 * One cubic-bezier easing curve from (0,0) to (1,1) with control points ([x1],[y1]) and ([x2],[y2]); the same control
 * points as ui/theme/BtMotion.kt (BtEasing), solved like Compose's CubicBezierEasing (parity-tested at 101 samples).
 * Newton steps on x(t) with a bisection fallback; no allocation.
 */
class FxEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun transform(f: Float): Float {
        if (f <= 0f) return 0f
        if (f >= 1f) return 1f
        if (x1 == y1 && x2 == y2) return f // the diagonal: exact identity, like Compose's LinearEasing
        val x = f.toDouble()
        // Newton from t = x (x(t) is monotonic for x1, x2 in [0, 1]).
        var t = x
        repeat(8) {
            val err = bezier(t, x1.toDouble(), x2.toDouble()) - x
            if (abs(err) < EPS) return bezier(t, y1.toDouble(), y2.toDouble()).toFloat()
            val d = derivative(t, x1.toDouble(), x2.toDouble())
            if (abs(d) < 1e-6) return@repeat
            t -= err / d
            if (t < 0.0 || t > 1.0) return@repeat
        }
        // Bisection fallback (always converges).
        var lo = 0.0
        var hi = 1.0
        t = x
        repeat(60) {
            val v = bezier(t, x1.toDouble(), x2.toDouble())
            if (abs(v - x) < EPS) return bezier(t, y1.toDouble(), y2.toDouble()).toFloat()
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return bezier(t, y1.toDouble(), y2.toDouble()).toFloat()
    }

    override fun equals(other: Any?): Boolean =
        other is FxEasing && x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2

    override fun hashCode(): Int = ((x1.hashCode() * 31 + y1.hashCode()) * 31 + x2.hashCode()) * 31 + y2.hashCode()

    override fun toString(): String = "FxEasing($x1, $y1, $x2, $y2)"

    companion object {
        private const val EPS = 1e-7

        private fun bezier(t: Double, p1: Double, p2: Double): Double {
            val u = 1 - t
            return 3 * p1 * u * u * t + 3 * p2 * u * t * t + t * t * t
        }

        private fun derivative(t: Double, p1: Double, p2: Double): Double {
            val u = 1 - t
            return 3 * p1 * u * u + 6 * (p2 - p1) * u * t + 3 * (1 - p2) * t * t
        }

        val Linear = FxEasing(0f, 0f, 1f, 1f)
        val Emphasized = FxEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = FxEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = FxEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = FxEasing(0.2f, 0f, 0f, 1f)
        val StandardDecelerate = FxEasing(0f, 0f, 0f, 1f)
        val StandardAccelerate = FxEasing(0.3f, 0f, 1f, 1f)
    }
}
