package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.rng.SplitMix64
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, allocation-free kraken tentacle rig (engine.md §2.4). [pose] fills [spine] (x, y dp per joint, anchor-local,
 * tentacle-major) and [width] (dp per joint) for timeline time t; all variation comes from [seed] (deterministic).
 * Tentacles rise from the water beside the road's middle vertex, reach for grip points along the road (Spawn,
 * emphasizedDecelerate), sway once (Run), retract (Settle, emphasizedAccelerate) and are gone by Despawn.
 */
class TentacleRig(val count: Int, val segments: Int, seed: Long) {
    init {
        require(count > 0 && segments >= 2) { "count > 0 and segments ≥ 2" }
    }

    val spine = FloatArray(count * segments * 2)
    val width = FloatArray(count * segments)

    // Per-tentacle variation, drawn once.
    private val gripFrac = FloatArray(count)
    private val phase = FloatArray(count)
    private val reach = FloatArray(count)
    private val spread = FloatArray(count)
    private val thickness = FloatArray(count)

    init {
        val rng = SplitMix64(seed.toULong())
        for (k in 0 until count) {
            gripFrac[k] = (0.15 + 0.7 * ((k + rng.nextDouble()) / count)).toFloat() // stays off the road's ends
            phase[k] = rng.nextDouble().toFloat()
            reach[k] = (0.85 + 0.3 * rng.nextDouble()).toFloat()
            spread[k] = ((k - (count - 1) / 2f) * SPREAD_DP + (rng.nextDouble() - 0.5) * 8.0).toFloat()
            thickness[k] = (5.0 + 3.0 * rng.nextDouble()).toFloat()
        }
    }

    /**
     * Pose at timeline time [t] (ms, folded) for the road polyline offsets [road] (x, y dp pairs, anchor-local) with
     * the water on the side ([waterDx], [waterDy]). [reduced] poses the final frame (`timeline.reducedAtMs`), so the
     * result does not depend on [t].
     */
    fun pose(t: Int, timeline: FxTimeline, road: FloatArray, waterDx: Float, waterDy: Float, reduced: Boolean) {
        val tt = if (reduced) timeline.reducedAtMs else t
        val ext = extension(tt, timeline)
        val n = road.size / 2
        val midX = if (n > 0) road[2 * (n / 2)] else 0f
        val midY = if (n > 0) road[2 * (n / 2) + 1] else 0f
        var wx = waterDx
        var wy = waterDy
        val wl = sqrt(wx * wx + wy * wy)
        if (wl < 1e-6f) {
            wx = 0f
            wy = 1f
        } else {
            wx /= wl
            wy /= wl
        }
        // Perpendicular to the water direction (along the shore).
        val px = -wy
        val py = wx
        val tau = tt / 1000f
        for (k in 0 until count) {
            val rootX = midX + wx * ROOT_DP + px * spread[k]
            val rootY = midY + wy * ROOT_DP + py * spread[k]
            // Grip target on the road polyline at a fractional vertex index.
            var gx = midX
            var gy = midY
            if (n >= 2) {
                val f = gripFrac[k] * (n - 1)
                val i0 = f.toInt().coerceAtMost(n - 2)
                val u = f - i0
                gx = road[2 * i0] + (road[2 * i0 + 2] - road[2 * i0]) * u
                gy = road[2 * i0 + 1] + (road[2 * i0 + 3] - road[2 * i0 + 1]) * u
            }
            val dx = (gx - rootX) * reach[k]
            val dy = (gy - rootY) * reach[k]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            val nx = -dy / len
            val ny = dx / len
            val amp = SWAY_DP * ext
            for (i in 0 until segments) {
                val s = i / (segments - 1f)
                val sway = sin(2 * PI * (s * 1.2 + phase[k]) + tau * 2.6).toFloat() * amp * s * (1f - 0.4f * s)
                val j = k * segments + i
                spine[2 * j] = rootX + dx * s * ext + nx * sway
                spine[2 * j + 1] = rootY + dy * s * ext + ny * sway
                width[j] = thickness[k] * (1f - 0.85f * s) * (0.4f + 0.6f * ext)
            }
        }
    }

    /** 0 → 1 over SPAWN, 1 through RUN, 1 → 0 over SETTLE, 0 after. */
    private fun extension(t: Int, tl: FxTimeline): Float {
        val settleStart = tl.spawnMs + tl.runMs
        val settleEnd = settleStart + tl.settleMs
        return when {
            t < tl.spawnMs -> tl.phase(t, 0, tl.spawnMs, FxEasing.EmphasizedDecelerate)
            t < settleStart -> 1f
            t < settleEnd -> 1f - tl.phase(t, settleStart, settleEnd, FxEasing.EmphasizedAccelerate)
            else -> 0f
        }
    }

    private companion object {
        const val ROOT_DP = 70f
        const val SPREAD_DP = 22f
        const val SWAY_DP = 14f
    }
}
