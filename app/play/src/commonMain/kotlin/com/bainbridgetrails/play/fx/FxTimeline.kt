package com.bainbridgetrails.play.fx

/**
 * Lifecycle stage boundaries in ms from spawn (engine.md PF-3): SPAWN [0, spawn), RUN, SETTLE, DESPAWN, then DONE.
 * RUN may loop with [runLoopMs] (Layer tier only; an Overlay part never loops, overlayMaxMs = 3 000). [reducedAtMs] is
 * the final frame, shown under reduced motion and as the idle rest frame (PF-2).
 */
data class FxTimeline(
    val spawnMs: Int,
    val runMs: Int,
    val settleMs: Int,
    val despawnMs: Int,
    val runLoopMs: Int? = null,
    val reducedAtMs: Int,
) {
    init {
        require(spawnMs >= 0 && runMs >= 0 && settleMs >= 0 && despawnMs >= 0) { "negative stage length" }
        require(runLoopMs == null || runLoopMs in 1..runMs) { "runLoopMs must be in 1..runMs" }
        require(reducedAtMs in 0..totalMs) { "reducedAtMs outside the timeline" }
    }

    val totalMs: Int get() = spawnMs + runMs + settleMs + despawnMs
    private val settleStartMs: Int get() = spawnMs + runMs

    /** Stage at [localMs] (ms since spawn), honouring a dismissal at local [dismissedAtMs]. */
    fun stageAt(localMs: Long, dismissedAtMs: Long? = null): FxStage = stageOfFolded(foldedMs(localMs, dismissedAtMs))

    /** Stage of a folded timeline time (as returned by [foldedMs]). */
    fun stageOfFolded(t: Int): FxStage = when {
        t < spawnMs -> FxStage.SPAWN
        t < settleStartMs -> FxStage.RUN
        t < settleStartMs + settleMs -> FxStage.SETTLE
        t < totalMs -> FxStage.DESPAWN
        else -> FxStage.DONE
    }

    /**
     * Local time folded into the timeline: RUN repeats every [runLoopMs] while live (so RUN can last forever), a
     * finished timeline holds at [totalMs], and a dismissal jumps to the start of SETTLE and then plays SETTLE and
     * DESPAWN in full (never cuts). A dismissal during SETTLE/DESPAWN changes nothing.
     */
    fun foldedMs(localMs: Long, dismissedAtMs: Long? = null): Int {
        val l = localMs.coerceAtLeast(0)
        if (dismissedAtMs != null && l >= dismissedAtMs) {
            val atDismiss = undismissed(dismissedAtMs.coerceAtLeast(0))
            if (atDismiss < settleStartMs) {
                val since = l - dismissedAtMs.coerceAtLeast(0)
                return (settleStartMs + since).coerceAtMost(totalMs.toLong()).toInt()
            }
        }
        return undismissed(l)
    }

    private fun undismissed(l: Long): Int {
        val loop = runLoopMs
        return when {
            l < spawnMs -> l.toInt()
            loop != null -> spawnMs + ((l - spawnMs) % loop).toInt()
            else -> l.coerceAtMost(totalMs.toLong()).toInt()
        }
    }

    /** Same contract as CelebrationScreen's `phase(t, from, to)`, optionally eased. */
    fun phase(t: Int, from: Int, to: Int, easing: FxEasing = FxEasing.Linear): Float =
        easing.transform(((t - from).toFloat() / (to - from)).coerceIn(0f, 1f))
}
