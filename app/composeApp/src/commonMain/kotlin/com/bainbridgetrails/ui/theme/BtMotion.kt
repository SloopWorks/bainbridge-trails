package com.bainbridgetrails.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/** ux.md §1.7. */
object BtEasing {
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/** Durations in ms. */
object BtDuration {
    const val short1 = 50
    const val short2 = 100
    const val short3 = 150
    const val short4 = 200
    const val medium1 = 250
    const val medium2 = 300
    const val medium3 = 350
    const val medium4 = 400
    const val long1 = 450
    const val long2 = 500
    const val long3 = 550
    const val long4 = 600
    const val extraLong1 = 700
    const val extraLong2 = 800
    const val extraLong3 = 900
    const val extraLong4 = 1000

    /** Recording dot pulse loop. */
    const val pulse = 1200
    /** Stagger between rows for progress fills (max [staggerRows] rows). */
    const val stagger = 30
    const val staggerRows = 8
}

/**
 * Motion settings for this composition (`BtTheme.motion`). When [reduced] (OS "remove animations" / Reduce Motion):
 * fills jump to the final value, no pulse/shimmer/counters, screen transitions crossfade short3.
 */
@Immutable
data class BtMotion(val reduced: Boolean) {
    /** A tween that becomes a snap under reduced motion. */
    fun <T> tweenOrSnap(durationMs: Int, easing: Easing = BtEasing.standard, delayMs: Int = 0): AnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, easing)
}

/** Reads the platform reduced-motion setting and updates when it changes (app resume / OS notification). */
@Composable
expect fun rememberReducedMotion(): Boolean
