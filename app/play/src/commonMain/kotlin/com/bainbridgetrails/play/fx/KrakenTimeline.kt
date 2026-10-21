package com.bainbridgetrails.play.fx

import kotlin.math.PI
import kotlin.math.sin

/**
 * The kraken's hero timelines (effects §3.1, engine §2.4, PD-19), pure and allocation-free. Every function takes the
 * folded timeline time `t` (ms) and returns a 0..1 envelope (or a scale), so the Overlay tentacles, the Layer grip
 * stroke and the casing reveal read one clock. Times are effects §3.1's table; both timelines are one-shot (RUN never
 * loops) and end inside `FxCaps.overlayMaxMs`, and their reduced frame is their last frame.
 */
object KrakenTimeline {
    /** `kraken_attack` intro: converge 0–400, hold, tentacles 500–1100 (+90 ms stagger), splash 900–1300, reveal
     *  1100–1700, squeeze 1500–1800, hand-off (sink) 1800–2600. SPAWN = wind-up, RUN = reach + grip, SETTLE = sink. */
    val attack = FxTimeline(spawnMs = 500, runMs = 1_300, settleMs = 800, despawnMs = 0, runLoopMs = null, reducedAtMs = 2_600)

    /** `kraken_recede`: casing flicker 0–150, un-draw 150–750, tentacles slide back 200–900, pool 600–1400, calm rings
     *  900–2100 (stagger 150). */
    val recede = FxTimeline(spawnMs = 150, runMs = 750, settleMs = 1_300, despawnMs = 0, runLoopMs = null, reducedAtMs = 2_200)

    /** Tentacle stagger (ms). */
    const val STAGGER_MS = 90

    /** Tip overshoot of the reach (effects §3.1: 10 %). */
    const val OVERSHOOT = 1.10f

    private val tl = attack

    // ---- attack ----

    /** Converge rings: 0 → 1 over 0–400 (`emphasizedAccelerate`, the wind-up). Radius 48 → 8 dp is the caller's. */
    fun converge(t: Int): Float = tl.phase(t, 0, 400, FxEasing.EmphasizedAccelerate)

    /** Converge ring alpha: up to .7 with [converge], then gone by 700 (after the hold). */
    fun convergeAlpha(t: Int): Float = 0.7f * converge(t) * (1f - tl.phase(t, 500, 700))

    /** Ink pool bloom 0 → 1 over 250–700 (`emphasizedDecelerate`). */
    fun poolBloom(t: Int): Float = tl.phase(t, 250, 700, FxEasing.EmphasizedDecelerate)

    /**
     * Visible tentacle length (fraction of the water → road curve) for tentacle [k]: grows over 700 ms from
     * 500 + 90·k (`emphasizedDecelerate`) out to [OVERSHOOT] and settles back to 1, holds through the squeeze, then
     * sinks to 35 % (1800–2300, `emphasizedAccelerate`); 0 at the end. Pair with [tentacleAlpha].
     */
    fun tentacleLength(t: Int, k: Int): Float {
        if (t >= tl.totalMs) return 0f
        val start = 500 + STAGGER_MS * k
        val grow = overshootSettle(tl.phase(t, start, start + 700), OVERSHOOT)
        val sink = 1f - 0.65f * tl.phase(t, 1_800, 2_300, FxEasing.EmphasizedAccelerate)
        return grow * sink
    }

    /** Tentacle alpha: 1 until the sink reaches 35 %, then → 0 over 2300–2600. */
    fun tentacleAlpha(t: Int): Float = 1f - tl.phase(t, 2_300, 2_600)

    /** Tip curl (0..1): rises with the overshoot and relaxes as the tip settles (`overshootSettle` follow-through). */
    fun curl(t: Int, k: Int): Float {
        val start = 500 + STAGGER_MS * k
        return sin(PI * tl.phase(t, start + 300, start + 900)).toFloat()
    }

    /** Splash droplets' flight 0 → 1 over 900–1300 (ballistic; `medium4`). */
    fun splash(t: Int): Float = tl.phase(t, 900, 1_300)

    /** Squeeze scale about the tentacle bases: 1 → .94 → 1 over 1500–1800; the `thud` haptic is at 1500. */
    fun squeeze(t: Int): Float = 1f - 0.06f * sin(PI * tl.phase(t, 1_500, 1_800)).toFloat()

    /** Casing reveal (Layer `globalState`): 0 → 1 over 1100–1700, linear in t. Same as `KrakenInk.revealAt`. */
    fun reveal(t: Int): Float = tl.phase(t, 1_100, 1_700)

    /**
     * Grip envelope for the Layer grip stroke (PD-19: a `LineLayer`, never Canvas): 0 through SPAWN and until the
     * tentacles reach (900), up by 1100, down over 1800–2200, so 0 again before SETTLE ends. Same as `KrakenInk.gripAt`.
     */
    fun grip(t: Int): Float = tl.phase(t, 900, 1_100) * (1f - tl.phase(t, 1_800, 2_200))

    // ---- recede ----

    /** The grip loosens: casing alpha 1 → .6 → 1 over 0–150 (`standard`, `short3`). */
    fun recedeFlicker(t: Int): Float = 1f - 0.4f * sin(PI * recede.phase(t, 0, 150, FxEasing.Standard)).toFloat()

    /** Casing un-draw progress 0 → 1 over 150–750, linear in t (the reveal reversed). */
    fun recedeUndraw(t: Int): Float = recede.phase(t, 150, 750)

    /** Casing alpha while it lets go: [recedeFlicker] × what is left of the [recedeUndraw]. */
    fun recedeCasing(t: Int): Float = recedeFlicker(t) * (1f - recedeUndraw(t))

    /** Tentacles slide back along their own curves (200–900, `emphasizedAccelerate`): the visible part starts here. */
    fun retract(t: Int): Float = recede.phase(t, 200, 900, FxEasing.EmphasizedAccelerate)

    /** Pool dissipates 600–1400 (`standardDecelerate`): 0 → 1 (α .2 → 0, radius ×1 → ×1.3 is the caller's). */
    fun poolFade(t: Int): Float = recede.phase(t, 600, 1_400, FxEasing.StandardDecelerate)

    /** Calm ring [i] (0..2) expanding from the anchor: 900 + 150·i over 900 ms (`emphasizedDecelerate`). */
    fun calmRing(t: Int, i: Int): Float = recede.phase(t, 900 + 150 * i, 1_800 + 150 * i, FxEasing.EmphasizedDecelerate)

    /** Calm ring alpha: .8 → 0 as it expands; 0 before it starts. */
    fun calmRingAlpha(t: Int, i: Int): Float {
        if (t < 900 + 150 * i) return 0f
        return 0.8f * (1f - calmRing(t, i))
    }

    /**
     * `emphasizedDecelerate` out to [peak] over the first 2/3, then `standard` back to 1 (PlayMotion.overshootSettle's
     * recipe with the card's peak).
     */
    fun overshootSettle(f: Float, peak: Float): Float {
        if (f <= 0f) return 0f
        if (f >= 1f) return 1f
        val split = 2f / 3f
        return if (f < split) {
            peak * FxEasing.EmphasizedDecelerate.transform(f / split)
        } else {
            peak + (1f - peak) * FxEasing.Standard.transform((f - split) / (1f - split))
        }
    }
}
