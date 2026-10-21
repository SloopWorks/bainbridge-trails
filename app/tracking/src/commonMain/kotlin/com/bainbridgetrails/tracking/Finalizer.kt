package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.coverage.keptFixes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class TrailResult(val trailId: String, val name: String, val before: Double, val after: Double, val status: TrailStatus)

data class SessionSummary(
    val sessionId: String,
    val islandBefore: Double,
    val islandAfter: Double,
    val trails: List<TrailResult>,
    val verified: Boolean,
    val droppedVehicle: Int,
    val droppedSteps: Int,
    val newlyCompleted: List<String>,
    /** The walk was recorded with approximate (reduced-accuracy) location: trails may not match. */
    val approximateLocation: Boolean = false,
    /** Start event wall clock (epoch ms). */
    val startedAt: Long = 0,
    /** Stop event wall clock (or last fix when merged before the Stop). */
    val endedAt: Long = 0,
    /** Gated, filtered GPS path length. */
    val distanceM: Double = 0.0,
    /** Distinct matched ticks of this walk on the goal's mapped segments × tick length (roads: `rseg-` only, RP-18). */
    val matchedM: Double = 0.0,
    /**
     * Counted roads/trails complete (by coverage or sticky, TL-7) just before and just after this walk's merge, read
     * under the coverage lock: the walk's own numbers for milestones (roads product.md §5.3), whatever sync does later.
     */
    val completedBefore: Int = 0,
    val completedAfter: Int = 0,
    /** After the walk: complete counted rows per category / counted rows per category (rows without a category skipped). */
    val completedByCategory: Map<String, Int> = emptyMap(),
    val countedByCategory: Map<String, Int> = emptyMap(),
)

/**
 * Gates a finished session, matches it to the trail network and merges it into [coverage].
 * Raw session files are kept ([LocalProgress.pruneRawTracks] deletes them after the retention period).
 *
 * Matching runs outside [CoverageStore.writeLock]; reading the pre-walk state, the walk record write and the
 * coverage merge all happen inside it, so a concurrent sync merge is never mistaken for this walk's rise and
 * sync never sees this walk's bits without its walk record (the record is written first).
 * [onWork] is a test hook invoked inside the background context; [afterMatch] runs after matching, before the lock.
 *
 * [onGated] (Play, docs/play/product.md §3.6.2, PD-12) is read on every [finalize]; when it returns a hook, the hook
 * gets the walk's gated fixes after the coverage fix filter ([keptFixes]: no mock, inaccurate, spike or too-fast fix)
 * and the summary, after the merge and outside the coverage lock. Null (Play off, the default) does no extra work. A
 * hook failure never fails the walk.
 *
 * [playPending] (branch review round 1 P1-5 / P1-6, PD-24): when a hook will run, the session's play-pending marker is
 * written before the merge and cleared once the hook returned (or failed); a process death in between leaves it, and
 * [resumePlay] re-runs the hook for finalized sessions still marked (claims are idempotent through `found`). Sync
 * leaves marked walks out of its uploads, so a walk never goes up before its Play element exists.
 */
class Finalizer(
    private val dataset: Dataset,
    private val index: SegmentIndex,
    private val store: SessionStore,
    private val coverage: CoverageStore,
    private val params: CoverageParams = CoverageParams(),
    private val onWork: suspend () -> Unit = {},
    /** Walk history index (no coordinates), updated on every finalize; null = not kept. */
    private val walks: WalkIndex? = null,
    /** Wall clock for [WalkRecord.finalizedAt]. */
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val afterMatch: suspend () -> Unit = {},
    private val onGated: () -> (suspend (sessionId: String, kept: List<Fix>, summary: SessionSummary) -> Unit)? = { null },
    /** Play-pending markers; null = none kept (Play never wired). */
    private val playPending: PlayPendingMarkers? = null,
) {
    private val progress = Progress(dataset, index, params)
    private val mutex = Mutex()

    /** Ids already finalised after their Stop. Blocking file read: call off the main thread. */
    fun finalizedIds(): Set<String> = coverage.finalizedIds()

    /** Sessions whose results sheet was already shown (walk index). Blocking file read: call off the main thread. */
    fun shownIds(): Set<String> = walks?.shownIds().orEmpty()

    /** Records that [sessionId]'s results sheet was shown. Blocking file write: call off the main thread. */
    suspend fun markShown(sessionId: String) {
        walks?.markShown(sessionId)
    }

    suspend fun finalize(sessionId: String): SessionSummary = withContext(Dispatchers.Default) { mutex.withLock {
        val hook = onGated()
        // Before the merge: a crash between the merge and the hook's end leaves the marker for [resumePlay].
        if (hook != null) playPending?.add(sessionId)
        val (summary, gatedFixes) = finalizeLocked(sessionId)
        if (hook != null) runHook(hook, sessionId, gatedFixes, summary)
        summary
    } }

    /**
     * Re-runs the Play hook for finalized sessions whose play-pending marker survived (process death between the merge
     * and the hook's end, P1-5), from the walk record and the raw track. Play off (no hook): markers are kept for a
     * later launch. A marked session that was never merged is left to the normal finalize (or dropped once its track is
     * gone). Returns the sessions replayed. Blocking file reads run on Default.
     */
    suspend fun resumePlay(): List<String> = withContext(Dispatchers.Default) { mutex.withLock {
        val markers = playPending ?: return@withLock emptyList()
        val marked = markers.ids()
        if (marked.isEmpty()) return@withLock emptyList()
        val hook = onGated() ?: return@withLock emptyList()
        val done = coverage.finalizedIds()
        val stored = store.ids().toSet()
        val records = walks?.all()?.associateBy { it.sessionId }.orEmpty()
        val names = dataset.trails.associate { it.id to it.name }
        val out = ArrayList<String>()
        for (id in marked) {
            if (id !in done) {
                if (id !in stored) markers.remove(id)
                continue
            }
            val record = records[id]
            val session = if (id in stored) runCatching { store.load(id) }.getOrNull() else null
            if (record == null || session == null) {
                markers.remove(id) // nothing left to claim from (wiped or pruned)
                continue
            }
            val gate = Gating.gate(session, index.projection)
            val summary = SessionSummary(
                sessionId = id,
                islandBefore = record.islandBefore,
                islandAfter = record.islandAfter,
                trails = record.trails.map { TrailResult(it.trailId, names[it.trailId] ?: it.trailId, it.before, it.after, progress.status(it.after)) },
                verified = record.verified,
                droppedVehicle = gate.droppedVehicle,
                droppedSteps = gate.droppedSteps,
                newlyCompleted = record.newlyCompleted,
                approximateLocation = record.approximateLocation,
                startedAt = record.startedAt,
                endedAt = record.endedAt,
                distanceM = record.distanceM,
                matchedM = record.matchedM,
            )
            runHook(hook, id, gate.fixes, summary)
            out += id
        }
        out
    } }

    /** Runs [hook] over the kept fixes, then clears the marker; a cancellation (process going away) keeps it. */
    private suspend fun runHook(
        hook: suspend (String, List<Fix>, SessionSummary) -> Unit,
        sessionId: String,
        gatedFixes: List<Fix>,
        summary: SessionSummary,
    ) {
        try {
            hook(sessionId, keptFixes(gatedFixes, params, index.projection), summary)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Play is optional: its failure must not lose or change the walk (PF0-3), nor retry forever.
        }
        playPending?.remove(sessionId)
    }

    private suspend fun finalizeLocked(sessionId: String): Pair<SessionSummary, List<Fix>> {
        onWork()
        val session = store.load(sessionId)
        val gate = Gating.gate(session, index.projection)
        val walked = index.coverFixes(gate.fixes)
        val startedAt = session.startMs ?: session.fixes.minOfOrNull { it.timeMs } ?: startOf(sessionId)
        val endedAt = session.stopMs ?: session.fixes.maxOfOrNull { it.timeMs } ?: startedAt
        val distanceM = WalkIndex.distanceM(gate.fixes, index.projection, params)
        val matchedM = dataset.rules.matchedM(walked, index)
        afterMatch()

        val summary = CoverageStore.writeLock.withLock {
            val before = coverage.load()
            val sticky = coverage.completedIds()
            val after = before.union(walked)

            val rose = mutableListOf<TrailResult>()
            val completed = mutableListOf<String>()
            val nowComplete = HashSet<String>()
            var completedBefore = 0
            var completedAfter = 0
            val doneByCategory = HashMap<String, Int>()
            val countedByCategory = HashMap<String, Int>()
            for (t in dataset.trails) {
                // Roads (RP-3): uncounted roads never rise, complete or celebrate.
                if (!progress.counts(t.id)) continue
                val b = progress.trailFraction(before, t.id)
                val a = progress.trailFraction(after, t.id)
                if (progress.status(a) == TrailStatus.COMPLETE) nowComplete += t.id
                val doneBefore = t.id in sticky || progress.status(b) == TrailStatus.COMPLETE
                val doneAfter = doneBefore || t.id in nowComplete
                if (doneBefore) completedBefore++
                if (doneAfter) completedAfter++
                t.category?.let { cat ->
                    countedByCategory[cat] = (countedByCategory[cat] ?: 0) + 1
                    if (doneAfter) doneByCategory[cat] = (doneByCategory[cat] ?: 0) + 1
                }
                if (a > b) {
                    val status = progress.status(a)
                    rose += TrailResult(t.id, t.name, b, a, status)
                    // TL-7: celebrate only the walk that first completes a trail; sticky completions never repeat.
                    if (status == TrailStatus.COMPLETE && progress.status(b) != TrailStatus.COMPLETE && t.id !in sticky) completed += t.id
                }
            }
            val sorted = rose.sortedByDescending { it.after }
            val islandBefore = progress.islandFraction(before)
            val islandAfter = progress.islandFraction(after)
            // Walk record first: sync reads both under this lock, and a walk without new bits is harmless.
            walks?.upsert(
                WalkRecord(
                    sessionId = sessionId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    distanceM = distanceM,
                    matchedM = matchedM,
                    verified = gate.verified,
                    approximateLocation = session.approximateLocation,
                    islandBefore = islandBefore,
                    islandAfter = islandAfter,
                    trails = sorted.map { WalkTrail(it.trailId, it.before, it.after) },
                    newlyCompleted = completed,
                    finalizedAt = clock(),
                ),
            )
            coverage.merge(sessionId, after, gate.verified, finalized = session.finished, completed = nowComplete)
            SessionSummary(
                sessionId = sessionId,
                islandBefore = islandBefore,
                islandAfter = islandAfter,
                trails = sorted,
                verified = gate.verified,
                droppedVehicle = gate.droppedVehicle,
                droppedSteps = gate.droppedSteps,
                newlyCompleted = completed,
                approximateLocation = session.approximateLocation,
                startedAt = startedAt,
                endedAt = endedAt,
                distanceM = distanceM,
                matchedM = matchedM,
                completedBefore = completedBefore,
                completedAfter = completedAfter,
                completedByCategory = doneByCategory,
                countedByCategory = countedByCategory,
            )
        }
        return summary to gate.fixes
    }
}

/** Session ids are "s<startMs>-<rand>"; 0 when the id carries no start time. */
private fun startOf(id: String): Long = id.removePrefix("s").substringBefore('-').toLongOrNull() ?: 0L

/**
 * Play-pending markers at `<dir>/play-pending.json` (branch review round 1 P1-5 / P1-6, PD-24): session ids whose Play
 * hook was due and has not finished. Written by [Finalizer] (under its lock), read by sync from any thread. Atomic tmp +
 * move; an unreadable file reads as empty. Holds session ids only while a hook is outstanding (normally milliseconds).
 */
class PlayPendingMarkers(dir: String) {
    private val path = Path(dir, "play-pending.json")
    private val tmp = Path(dir, "play-pending.json.tmp")

    init {
        SystemFileSystem.createDirectories(Path(dir))
    }

    fun ids(): Set<String> {
        if (!SystemFileSystem.exists(path)) return emptySet()
        return runCatching {
            json.decodeFromString(PendingFile.serializer(), SystemFileSystem.source(path).buffered().use { it.readString() }).ids.toSet()
        }.getOrElse { emptySet() }
    }

    suspend fun add(sessionId: String) = lock.withLock {
        val cur = ids()
        if (sessionId !in cur) write(cur + sessionId)
    }

    suspend fun remove(sessionId: String) = lock.withLock {
        val cur = ids()
        if (sessionId in cur) write(cur - sessionId)
    }

    private fun write(next: Set<String>) {
        if (next.isEmpty()) {
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
            return
        }
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(PendingFile.serializer(), PendingFile(ids = next.sorted())))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, path)
    }

    @Serializable
    private data class PendingFile(val v: Int = 1, val ids: List<String> = emptyList())

    private companion object {
        val lock = Mutex()
        val json = Json { ignoreUnknownKeys = true }
    }
}
