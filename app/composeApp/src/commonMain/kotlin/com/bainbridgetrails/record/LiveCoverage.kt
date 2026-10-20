package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.TrailData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One "Trails on this walk" row: provisional progress while recording (decisions.md TL-8). */
data class LiveTrail(val trailId: String, val name: String, val before: Double, val after: Double, val status: TrailStatus)

/**
 * Provisional coverage while a walk records (decisions.md TL-8). Each [update] matches only the trailing
 * [windowMs] of the live track and unions the result into what earlier updates accumulated, starting from the
 * pre-walk state. Coverage is a union, so overlapping windows (an update every 30 s, a 10 min window) add up to
 * the whole walk at bounded cost per update. A gap longer than the window (the screen was hidden for a while)
 * is simply not shown until Finish; the final numbers always come from the Finalizer.
 *
 * Generic over the coverage representation [S] so the windowing/merge logic is testable without a dataset.
 * Not thread-safe: callers serialise [update] (see [LiveCoverageSession]).
 */
class LiveCoverage<S>(
    preWalk: S,
    /** (id, name) of every trail, in dataset order. */
    private val trails: List<Pair<String, String>>,
    private val cover: (List<Fix>) -> S,
    private val union: (S, S) -> S,
    private val fraction: (S, String) -> Double,
    private val status: (Double) -> TrailStatus,
    /** Trails already complete before the walk (TL-7 sticky): shown as complete whatever their fraction. */
    private val stickyCompleted: Set<String> = emptySet(),
    val windowMs: Long = WINDOW_MS,
) {
    private var merged: S = preWalk
    private val before: Map<String, Double> = trails.associate { (id, _) -> id to fraction(preWalk, id) }
    /** Trail id -> the update round that last saw the walk on it (for "most recently touched first"). */
    private val touched = HashMap<String, Int>()
    private var round = 0
    private var lastFix: Fix? = null
    private var rows: List<LiveTrail> = emptyList()

    /** Number of times matching actually ran (tests). */
    var matchCount: Int = 0
        private set

    /**
     * Matches the trailing window of [track] (time-ordered fixes of the current walk) and returns the rows:
     * every trail this walk touched, most recently touched first. Returns the previous rows when the track has not
     * grown since the last call.
     */
    fun update(track: List<Fix>): List<LiveTrail> {
        val last = track.lastOrNull() ?: return rows
        if (last == lastFix) return rows
        lastFix = last
        val window = window(track, windowMs)
        if (window.isEmpty()) return rows
        round++
        matchCount++
        val walked = cover(window)
        merged = union(merged, walked)
        val out = ArrayList<Pair<LiveTrail, Int>>()
        for ((id, name) in trails) {
            if (fraction(walked, id) > 0.0) touched[id] = round
            val seen = touched[id] ?: continue
            val b = before.getValue(id)
            val a = maxOf(b, fraction(merged, id))
            val s = if (id in stickyCompleted) TrailStatus.COMPLETE else status(a)
            out += LiveTrail(id, name, b, a, s) to seen
        }
        rows = out.sortedWith(compareByDescending<Pair<LiveTrail, Int>> { it.second }.thenByDescending { it.first.after })
            .map { it.first }
        return rows
    }

    companion object {
        /** TL-8: the trailing window matched per update. */
        const val WINDOW_MS = 10 * 60_000L

        /** TL-8: how often the Record screen asks for an update while visible and in the foreground. */
        const val INTERVAL_MS = 30_000L

        /**
         * The fixes of [track] no older than [windowMs] before its newest fix (fix time, not wall clock: a stalled
         * GPS feed still has a window). Mock fixes never count (finalising drops them too).
         */
        fun window(track: List<Fix>, windowMs: Long): List<Fix> {
            val newest = track.lastOrNull()?.timeMs ?: return emptyList()
            val from = newest - windowMs
            // The track is append-only and time-ordered: scan back from the end.
            var i = track.size
            while (i > 0 && track[i - 1].timeMs >= from) i--
            return track.subList(i, track.size).filter { !it.isMock }
        }

        fun forDataset(data: TrailData, preWalk: CoverageState, stickyCompleted: Set<String>): LiveCoverage<CoverageState> =
            LiveCoverage(
                preWalk = preWalk,
                // Roads (RP-3): only counted roads are listed; the finalizer never rises an uncounted one either.
                trails = data.dataset.trails.filter { data.progress.counts(it.id) }.map { it.id to it.name },
                cover = { data.index.coverFixes(it) },
                union = { a, b -> a.union(b) },
                fraction = { s, id -> data.progress.trailFraction(s, id) },
                status = { data.progress.status(it) },
                stickyCompleted = stickyCompleted,
            )
    }
}

/**
 * The live coverage of one recording session, kept for the process (it survives minimising the Record screen).
 * [refresh] runs matching on [compute], never on Main.
 */
class LiveCoverageSession(
    val sessionId: String,
    private val create: suspend () -> LiveCoverage<*>,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _rows = MutableStateFlow<List<LiveTrail>>(emptyList())
    val rows: StateFlow<List<LiveTrail>> = _rows.asStateFlow()
    private val mutex = Mutex()
    private var live: LiveCoverage<*>? = null

    suspend fun refresh(track: List<Fix>) = mutex.withLock {
        val l = live ?: create().also { live = it }
        _rows.value = withContext(compute) { l.update(track) }
    }

    /** The stored pre-walk state changed under the walk (local wipe): re-read it on the next [refresh]. */
    suspend fun invalidate() = mutex.withLock {
        live = null
        _rows.value = emptyList()
    }
}
