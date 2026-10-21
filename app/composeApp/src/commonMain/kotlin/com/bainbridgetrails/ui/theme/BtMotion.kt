package com.bainbridgetrails.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.keyframes
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
 * Play motion (docs/play/ux.md §1.4, effects.md §1.7): compositions of [BtEasing] / [BtDuration] only, no new curve.
 * Both easings map 0 → 0 and 1 → 1 and leave the range on the way, so they work on any animated value.
 */
object PlayMotion {
    /** Peak of [overshootSettle]: 108 % of the change. */
    const val OVERSHOOT = 1.08f

    /** Dip of [anticipate]: −8 % of the change. */
    const val ANTICIPATE_DIP = -0.08f

    /** Share of the duration spent going out to the overshoot (2/3) / down into the dip (30 %). */
    private const val OVERSHOOT_SPLIT = 2f / 3f
    private const val ANTICIPATE_SPLIT = 0.3f

    /**
     * `emphasizedDecelerate` out to 108 % over the first 2/3, then `standard` back to 100 %. Chrome only (Play chip
     * bump, rank badge earn); never on map pins (Layer tier, spike S4).
     */
    val overshootSettle: Easing = Easing { t ->
        if (t < OVERSHOOT_SPLIT) {
            OVERSHOOT * BtEasing.emphasizedDecelerate.transform(t / OVERSHOOT_SPLIT)
        } else {
            OVERSHOOT + (1f - OVERSHOOT) * BtEasing.standard.transform((t - OVERSHOOT_SPLIT) / (1f - OVERSHOOT_SPLIT))
        }
    }

    /** `emphasizedAccelerate` dip to −8 % over the first 30 %, then `emphasized` to the target. Map heroes only (wind-up before a lift). */
    val anticipate: Easing = Easing { t ->
        if (t < ANTICIPATE_SPLIT) {
            ANTICIPATE_DIP * BtEasing.emphasizedAccelerate.transform(t / ANTICIPATE_SPLIT)
        } else {
            ANTICIPATE_DIP + (1f - ANTICIPATE_DIP) * BtEasing.emphasized.transform((t - ANTICIPATE_SPLIT) / (1f - ANTICIPATE_SPLIT))
        }
    }

    /**
     * A scale bump that ends where it starts (1 → 1.08 → 1) on [overshootSettle]'s timing: the Play chip on
     * `claim_pop`. Callers pass `BtTheme.motion.reduced` and get a snap (the chip crossfades its value instead).
     */
    fun bump(reduced: Boolean, durationMs: Int = BtDuration.short4): AnimationSpec<Float> =
        if (reduced) {
            snap()
        } else {
            keyframes {
                durationMillis = durationMs
                1f at 0 using BtEasing.emphasizedDecelerate
                OVERSHOOT at (durationMs * OVERSHOOT_SPLIT).toInt() using BtEasing.standard
            }
        }
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
