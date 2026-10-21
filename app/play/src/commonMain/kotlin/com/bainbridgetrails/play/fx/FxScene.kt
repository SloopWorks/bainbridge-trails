package com.bainbridgetrails.play.fx

/** One live effect: its spec, scene sequence number and spawn time (FxClock ms). [dismissedAtMs] is local ms. */
data class FxInstance(val spec: FxSpec, val seq: Long, val spawnedAtMs: Long, val dismissedAtMs: Long? = null) {
    fun localMs(nowMs: Long): Long = nowMs - spawnedAtMs
}

sealed interface SpawnResult {
    data class Spawned(val seq: Long) : SpawnResult

    /** Waiting for the HERO slot; [position] 1 = next in line. */
    data class Queued(val position: Int) : SpawnResult

    /** "duplicate", "cooldown", "cap" or "overlay-loop". */
    data class Rejected(val reason: String) : SpawnResult
}

/** What to draw at [nowMs]; [live] sorted by (band, priority, seq). */
data class FxFrame(val nowMs: Long, val live: List<FxLive>)

/**
 * One drawable item. [stage] is the instance's lifecycle stage; [t] is the timeline time to draw — the folded time,
 * or `reducedAtMs` (the final frame) when [reduced], [demoted] (over a cap) or [finalFrame] (HERO spawned out of view).
 */
data class FxLive(
    val instance: FxInstance,
    val stage: FxStage,
    val t: Int,
    val reduced: Boolean,
    val demoted: Boolean = false,
    val finalFrame: Boolean = false,
)

/**
 * The effect scene (engine.md PF-3): a pure, deterministic state machine. All times are FxClock ms (monotonic,
 * pause-aware); same input sequence ⇒ same frames. Not thread-safe: Main only.
 *
 * Arbitration: 1 HERO live (others queue FIFO and expire after [FxCaps.heroQueueTtlMs] in the queue), a global HERO
 * cooldown of [FxCaps.heroCooldownMs] after a HERO's DONE, ≤ [FxCaps.notice] NOTICE (more are rejected),
 * ≤ [FxCaps.ambientAnimated] animated AMBIENT and ≤ [FxCaps.overlay] animated Overlay-tier items (over a cap the
 * lowest-priority oldest is demoted to its final frame / Layer LOD). Overlay-tier items retire at
 * [FxCaps.overlayMaxMs] and never loop.
 */
class FxScene(private val caps: FxCaps = FxCaps()) {
    private class Entry(var instance: FxInstance, val finalFrame: Boolean) {
        var demoted = false
    }

    private class Pending(val spec: FxSpec, val queuedAtMs: Long)

    private val entries = ArrayList<Entry>()
    private val queue = ArrayList<Pending>()
    private val lastSpawnOfKind = HashMap<String, Long>()
    private var nextSeq = 1L
    private var lastHeroDoneAtMs: Long? = null
    private var lastNowMs = 0L
    private var lastReduced = false

    /** Bumped whenever the set of instances, their dismissal or demotion changes (not per frame). */
    var version: Long = 0
        private set

    val queuedCount: Int get() = queue.size

    val isAnimating: Boolean get() = !lastReduced && entries.any { animating(it, lastNowMs) }

    /**
     * Spawn [spec] at [nowMs]. A HERO whose anchor is out of view ([inView] false) is not queued: it jumps to its final
     * frame (its announcement still speaks) and never holds the HERO slot.
     */
    fun spawn(spec: FxSpec, nowMs: Long, inView: Boolean = true): SpawnResult {
        advance(nowMs)
        if (entries.any { it.instance.spec.id == spec.id } || queue.any { it.spec.id == spec.id }) {
            return SpawnResult.Rejected("duplicate")
        }
        if (spec.band == FxBand.OVERLAY && spec.timeline.runLoopMs != null) return SpawnResult.Rejected("overlay-loop")
        val last = lastSpawnOfKind[spec.kind]
        if (spec.cooldownMs > 0 && last != null && nowMs - last < spec.cooldownMs) return SpawnResult.Rejected("cooldown")
        when (spec.priority) {
            FxPriority.HERO -> if (inView && !heroSlotFree(nowMs)) {
                queue += Pending(spec, nowMs)
                version++
                return SpawnResult.Queued(queue.size)
            }
            FxPriority.NOTICE -> if (entries.count { it.instance.spec.priority == FxPriority.NOTICE } >= caps.notice) {
                return SpawnResult.Rejected("cap")
            }
            FxPriority.AMBIENT -> Unit
        }
        val seq = add(spec, nowMs, finalFrame = spec.priority == FxPriority.HERO && !inView)
        applyCaps(nowMs)
        return SpawnResult.Spawned(seq)
    }

    /** Dismiss a live effect (it jumps to SETTLE and plays out; never cut) or drop it from the HERO queue. */
    fun dismiss(id: String, nowMs: Long) {
        advance(nowMs)
        if (queue.removeAll { it.spec.id == id }) version++
        val e = entries.firstOrNull { it.instance.spec.id == id } ?: return
        if (e.instance.dismissedAtMs == null) {
            e.instance = e.instance.copy(dismissedAtMs = e.instance.localMs(nowMs).coerceAtLeast(0))
            version++
        }
    }

    /** Advances stages, retires DONE, promotes a queued HERO, applies caps; returns the draw list. */
    fun tick(nowMs: Long, reduced: Boolean): FxFrame {
        advance(nowMs)
        lastReduced = reduced
        val live = ArrayList<FxLive>(entries.size)
        for (e in entries) {
            val inst = e.instance
            val tl = inst.spec.timeline
            val local = inst.localMs(nowMs)
            val still = reduced || e.demoted || e.finalFrame
            live += FxLive(
                instance = inst,
                stage = tl.stageAt(local, inst.dismissedAtMs),
                t = if (still) tl.reducedAtMs else tl.foldedMs(local, inst.dismissedAtMs),
                reduced = reduced,
                demoted = e.demoted,
                finalFrame = e.finalFrame,
            )
        }
        live.sortWith(DRAW_ORDER)
        return FxFrame(nowMs, live)
    }

    /** Input to [FxClockPolicy]. A queued HERO counts as animating HERO (the clock must run to promote it). */
    fun summary(): FxSceneSummary {
        if (lastReduced) return FxSceneSummary(false, false, false, false)
        var hero = queue.isNotEmpty()
        var notice = false
        var ambient = false
        var layer = false
        for (e in entries) {
            if (!animating(e, lastNowMs)) continue
            when (e.instance.spec.priority) {
                FxPriority.HERO -> hero = true
                FxPriority.NOTICE -> notice = true
                FxPriority.AMBIENT -> ambient = true
            }
            if (e.instance.spec.band.isLayerTier) layer = true
        }
        return FxSceneSummary(hero, notice, ambient, layer)
    }

    // ---- internals ----

    private fun add(spec: FxSpec, nowMs: Long, finalFrame: Boolean): Long {
        val seq = nextSeq++
        entries += Entry(FxInstance(spec, seq, nowMs), finalFrame)
        lastSpawnOfKind[spec.kind] = nowMs
        version++
        return seq
    }

    private fun advance(nowMs: Long) {
        lastNowMs = nowMs
        // Retire DONE (in seq order, so the HERO cooldown anchor is deterministic).
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val doneLocal = doneLocalMs(e.instance) ?: continue
            if (e.instance.localMs(nowMs) >= doneLocal) {
                if (e.instance.spec.priority == FxPriority.HERO && !e.finalFrame) {
                    val doneAt = e.instance.spawnedAtMs + doneLocal
                    lastHeroDoneAtMs = maxOf(lastHeroDoneAtMs ?: doneAt, doneAt)
                }
                it.remove()
                version++
            }
        }
        // Expire queued HEROs, then promote the head when the slot is free.
        if (queue.removeAll { nowMs - it.queuedAtMs >= caps.heroQueueTtlMs }) version++
        if (queue.isNotEmpty() && heroSlotFree(nowMs, ignoreQueue = true)) {
            val head = queue.removeAt(0)
            add(head.spec, nowMs, finalFrame = false)
            applyCaps(nowMs)
        }
    }

    private fun heroSlotFree(nowMs: Long, ignoreQueue: Boolean = false): Boolean {
        if (!ignoreQueue && queue.isNotEmpty()) return false
        val liveHeroes = entries.count { it.instance.spec.priority == FxPriority.HERO && !it.finalFrame }
        if (liveHeroes >= caps.hero) return false
        val done = lastHeroDoneAtMs
        return done == null || nowMs - done >= caps.heroCooldownMs
    }

    /** Local ms at which [inst] is DONE, or null while it loops undismissed. */
    private fun doneLocalMs(inst: FxInstance): Long? {
        val tl = inst.spec.timeline
        val d = inst.dismissedAtMs
        val natural: Long? = when {
            d != null && tl.foldedMs(d) < tl.spawnMs + tl.runMs -> d + tl.settleMs + tl.despawnMs
            tl.runLoopMs != null && d == null -> null
            else -> tl.totalMs.toLong()
        }
        if (inst.spec.band != FxBand.OVERLAY) return natural
        return if (natural == null) caps.overlayMaxMs else minOf(natural, caps.overlayMaxMs)
    }

    private fun animating(e: Entry, nowMs: Long): Boolean {
        if (e.demoted || e.finalFrame) return false
        val local = e.instance.localMs(nowMs)
        val done = doneLocalMs(e.instance)
        return done == null || local < done
    }

    private fun applyCaps(nowMs: Long) {
        demoteOver(caps.overlay, nowMs) { it.instance.spec.band == FxBand.OVERLAY }
        demoteOver(caps.ambientAnimated, nowMs) { it.instance.spec.priority == FxPriority.AMBIENT }
    }

    /** Demote the lowest-priority, oldest animating entries matching [where] until at most [cap] animate. */
    private inline fun demoteOver(cap: Int, nowMs: Long, where: (Entry) -> Boolean) {
        val animatingMatches = entries.filter { where(it) && animating(it, nowMs) }
        val excess = animatingMatches.size - cap
        if (excess <= 0) return
        animatingMatches
            .sortedWith(compareBy({ it.instance.spec.priority.ordinal }, { it.instance.seq }))
            .take(excess)
            .forEach { it.demoted = true }
        version++
    }

    private companion object {
        val DRAW_ORDER: Comparator<FxLive> = compareBy(
            { it.instance.spec.band.ordinal },
            { it.instance.spec.priority.ordinal },
            { it.instance.seq },
        )
    }
}

/** Layer tier = rendered by MapLibre (every band below OVERLAY). */
val FxBand.isLayerTier: Boolean get() = this != FxBand.OVERLAY && this != FxBand.MOMENT
