package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.CoverageResponse
import com.bainbridgetrails.net.CoverageUpload
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.PlayUploadResultDto
import com.bainbridgetrails.net.PlayWalkClaimsDto
import com.bainbridgetrails.net.WalkDto
import com.bainbridgetrails.net.isRetryable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Me's status line (F11.5): Idle+nothing pending = `sync_ok`, Pending = `sync_pending`, Error = `sync_failed`. */
sealed interface SyncState {
    data object SignedOut : SyncState

    /** [lastSyncedAt] null = never synced on this account yet. */
    data class Idle(val lastSyncedAt: Long?, val pendingWalks: Int) : SyncState

    /** [backfill] = first sync after sign-in (`sync_first_progress`). */
    data class Syncing(val backfill: Boolean) : SyncState

    /** Offline / server busy: will retry with backoff and on the next trigger. */
    data class Pending(val pendingWalks: Int, val error: ApiError) : SyncState

    /** A non-retryable failure (bad request, decode): retried only on the next trigger. */
    data class Error(val error: ApiError) : SyncState

    /** The server refused the upload as implausible (TL-2). Local progress stays; see [SyncRepository.rejectedNotice]. */
    data class Rejected(val error: ApiError) : SyncState
}

/** One finished local walk as sync needs it: the local session id and its duration (never its times). */
data class LocalWalk(val sessionId: String, val durationS: Long)

/** Local coverage and the finished walks that produced it, read together. */
data class LocalSnapshot(val state: CoverageState, val walks: List<LocalWalk>)

/** Local coverage as sync sees it (production: tracking's CoverageStore + WalkIndex under its write lock). */
interface LocalCoverage {
    /** Coverage + finished walks in one consistent read (a walk's bits never appear without the walk). */
    suspend fun snapshot(): LocalSnapshot

    /** Unions [state] and sticky [completed] into the store; true when anything changed. */
    suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean
}

/**
 * Play's share of one coverage upload (docs/play/product.md §3.10, PD-3): [playConfig] + one element per walk of the
 * upload's `walks[]` ([elements]; empty = both fields omitted, the pure body). [settle] is called once with the
 * outcome, unless the failure is retryable (then everything stays queued for the next pass).
 */
class PlayPart(
    val playConfig: String,
    val elements: List<PlayWalkClaimsDto>,
    val settle: suspend (PlayOutcome) -> Unit,
)

/** How an upload carrying a [PlayPart] ended. [stillPending]: walk ids still in sync's queue after this pass. */
sealed interface PlayOutcome {
    val stillPending: Set<String>

    /** 200: [result] is the response's `play` (null when the server sent none). */
    data class Answered(val result: PlayUploadResultDto?, override val stillPending: Set<String>) : PlayOutcome

    /** Not retried (422 implausible, walk limit, another 4xx): the sent claims are `local_only` with [code]. */
    data class Refused(val code: String, override val stillPending: Set<String>) : PlayOutcome
}

@Serializable
internal data class SyncFile(
    val v: Int = 1,
    /** The account the bookkeeping below belongs to; a different signed-in user triggers a backfill. */
    val userId: String? = null,
    /** Local session id → its random walk id + duration (per install; the session id embeds a time, so never sent). */
    val walkIds: Map<String, WalkDto> = emptyMap(),
    /** Walks not yet acknowledged by a 200 for [userId] (the persisted offline queue). */
    val pending: List<WalkDto> = emptyList(),
    /** Bits the server already has, or that it refused (never resent). Uploads send local minus this. */
    val baseline: Map<String, String> = emptyMap(),
    /** The account has had an accepted upload (server walk cap drops from 2000 to 200 per upload). */
    val acceptedOnce: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val rejectedNotice: Boolean = false,
)

/**
 * Coverage sync (F11, TL-2, TL-7). Upload = every unacknowledged walk `{id, durationS}` + local coverage bits the
 * server has not seen (bit-level delta against [SyncFile.baseline]); the response's segments and completed ids are
 * unioned into local storage. The first sync for an account (sign-in) is a backfill: every local walk, all bits.
 *
 * Triggers: [onSignedIn], [walkFinished], [onForeground] (> 15 min since the last attempt or work pending),
 * [syncNow] (manual). Retryable failures back off 30 s → 2 min → 10 min, then wait for the next trigger.
 * A 422 is not retried: the refused bits join the baseline, the walks leave the queue, [rejectedNotice] is set.
 * A 422 for an upload without walks keeps its bits out of the baseline (they go again with the next walk).
 *
 * One upload per pass, never batches: after an account's first accepted upload the server requires Σ durations of
 * new walks ≤ time since the last walk upload, so a second batch sent right after the first is always refused.
 * The first upload (backfill) carries up to [MAX_WALKS_FIRST] walks, later ones [MAX_WALKS_AFTER_FIRST] (also on
 * `too_many_walks`, e.g. an account already backfilled from another device). Known limitation: walks beyond the cap
 * (> 2000 local walks at first sign-in, > 200 queued later) are dropped from the queue uncredited; their coverage is
 * still sent. The longest walks are kept, since the coverage allowance grows with Σ durations.
 */
@OptIn(ExperimentalUuidApi::class)
class SyncRepository(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val queuePath: String,
    /** Signed-in user id, null when signed out. */
    private val currentUserId: () -> String?,
    /** Local coverage + finished local walks (StoreCoverage). */
    private val coverage: LocalCoverage,
    /** Bundled dataset version (`DatasetRepository.await().dataset.version`). */
    private val datasetVersion: suspend () -> String,
    /** Local coverage changed through a merge (refresh progress/map). */
    private val onCoverageChanged: () -> Unit = {},
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val backoffMs: List<Long> = listOf(30_000L, 120_000L, 600_000L),
    private val newWalkId: () -> String = { Uuid.random().toString() },
    /**
     * Which local segments may go up (roads: counted `rseg-` only, RD-7); null = all. Filtered before the baseline
     * delta, so a segment that never goes up never forces an upload either.
     */
    private val uploadable: suspend () -> ((String) -> Boolean)? = { null },
    /**
     * Play claims for an upload (docs/play/plan.md L2-9): called with local session id → walk id and the walk ids this
     * upload carries; null provider (or a null answer) = today's code path and a byte-identical body (PF0-2).
     */
    private val playPart: (suspend (walkIdBySession: Map<String, String>, walksInThisUpload: Set<String>) -> PlayPart?)? = null,
) {
    private val file = JsonFile(queuePath, SyncFile.serializer())
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _rejectedNotice = MutableStateFlow(false)
    /** Show `sync_rejected_notice` once while true; call [dismissRejectedNotice] after showing it. */
    val rejectedNotice: StateFlow<Boolean> = _rejectedNotice.asStateFlow()

    private val runLock = Mutex()
    private var job: Job? = null
    private var again = false
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /** Startup: publish the persisted status without touching the network. */
    suspend fun load() {
        val f = readFile()
        _rejectedNotice.value = f.rejectedNotice
        _state.value = if (currentUserId() == null) SyncState.SignedOut
        else SyncState.Idle(f.lastSuccessAt.takeIf { f.userId == currentUserId() }, f.pending.size)
    }

    fun onSignedIn() = request()

    fun walkFinished() = request()

    fun syncNow() = request()

    /** App came to the foreground: sync when > [FOREGROUND_INTERVAL_MS] since the last attempt or walks are queued. */
    fun onForeground() {
        if (currentUserId() == null) return
        scope.launch {
            val f = readFile()
            val due = f.lastAttemptAt == null || now() - f.lastAttemptAt > FOREGROUND_INTERVAL_MS
            if (due || f.pending.isNotEmpty() || f.userId != currentUserId()) request()
        }
    }

    /** Signed out: stop syncing. [wipe] (account deleted + "clear this phone") also forgets walk ids. */
    suspend fun onSignedOut(wipe: Boolean) {
        retryJob?.cancel()
        job?.cancel() // a running pass holds runLock; never wait on it from its own call stack
        runLock.withLock {
            withContext(io) {
                if (wipe) file.delete() else file.write(SyncFile(walkIds = readFileNow().walkIds))
            }
        }
        _rejectedNotice.value = false
        _state.value = SyncState.SignedOut
    }

    fun dismissRejectedNotice() {
        _rejectedNotice.value = false
        scope.launch { runLock.withLock { update { it.copy(rejectedNotice = false) } } }
    }

    /** Coalescing trigger: one run at a time; a trigger during a run causes one more run after it. */
    private fun request() {
        if (currentUserId() == null) return
        if (job?.isActive == true) {
            again = true
            return
        }
        job = scope.launch {
            do {
                again = false
                syncOnce()
            } while (again)
        }
    }

    /** One full sync pass (tests call it directly). */
    suspend fun syncOnce(): SyncState = runLock.withLock {
        val userId = currentUserId() ?: return@withLock SyncState.SignedOut.also { _state.value = it }
        val snapshot = coverage.snapshot()
        var f = prepare(userId, snapshot.walks)
        val backfill = f.lastSuccessAt == null
        _state.value = SyncState.Syncing(backfill)
        f = f.copy(lastAttemptAt = now())
        write(f)
        val result = try {
            run(f, snapshot.state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "sync failed: ${e.message}")
            SyncState.Error(ApiError.Http(0, "client", e.message ?: "sync failed"))
        }
        if (result is SyncState.Pending) scheduleRetry(result.error) else if (result !is SyncState.SignedOut) resetRetry()
        _state.value = result
        result
    }

    /** Account switch → backfill; new local walks → queue. */
    private suspend fun prepare(userId: String, walks: List<LocalWalk>): SyncFile {
        var f = readFile()
        val known = f.walkIds.toMutableMap()
        val added = mutableListOf<WalkDto>()
        for (w in walks) {
            if (w.sessionId in known) continue
            val dto = WalkDto(newWalkId(), w.durationS.coerceAtLeast(0))
            known[w.sessionId] = dto
            added += dto
        }
        f = f.copy(walkIds = known)
        f = if (f.userId != userId) {
            // Backfill (TL-2): every local walk and every local bit, for this account.
            AppLog.i(TAG, "backfill for new account: ${known.size} walks")
            SyncFile(userId = userId, walkIds = known, pending = known.values.toList(), rejectedNotice = f.rejectedNotice)
        } else {
            f.copy(pending = f.pending + added)
        }
        write(f)
        return f
    }

    private suspend fun run(start: SyncFile, state: CoverageState): SyncState {
        var f = start
        val version = datasetVersion()
        val keep = uploadable()
        val local = withContext(io) { Bitsets.fromState(state).let { all -> if (keep == null) all else all.filterKeys(keep) } }
        val delta = Bitsets.minus(local, f.baseline)
        if (f.pending.isEmpty() && delta.isEmpty()) {
            // Nothing to upload: pull (a new device gets its history; another device's walks arrive).
            return when (val r = api.getCoverage()) {
                is ApiResult.Ok -> {
                    f = merge(f, r.value, sent = emptyMap())
                    write(f.copy(lastSuccessAt = now()))
                    SyncState.Idle(now(), 0)
                }
                is ApiResult.Failure -> failure(f, r.error, emptyMap(), emptyList())
            }
        }
        var cap = if (f.acceptedOnce) MAX_WALKS_AFTER_FIRST else MAX_WALKS_FIRST
        var withPlay = true
        while (true) {
            // One upload only (see class doc): over the cap, keep the longest walks (largest allowance).
            val walks = if (f.pending.size <= cap) f.pending else f.pending.sortedByDescending { it.durationS }.take(cap)
            val play = if (withPlay) playPartFor(f, walks) else null
            val sendPlay = play?.takeIf { it.elements.isNotEmpty() }
            val upload = CoverageUpload(version, TICK_M, delta, walks, playConfig = sendPlay?.playConfig, play = sendPlay?.elements)
            when (val r = api.putCoverage(upload)) {
                is ApiResult.Ok -> {
                    val dropped = f.pending.size - walks.size
                    if (dropped > 0) AppLog.w(TAG, "$dropped walks over the per-upload cap were not credited")
                    f = merge(f, r.value, sent = delta).copy(pending = emptyList(), acceptedOnce = true, lastSuccessAt = now())
                    write(f)
                    AppLog.i(TAG, "uploaded ${walks.size} walks, ${delta.size} segments; +${r.value.newlyCoveredM ?: 0.0} m")
                    play?.let { settlePlay(it, PlayOutcome.Answered(r.value.play, stillPending = emptySet())) }
                    return SyncState.Idle(now(), 0)
                }
                is ApiResult.Failure -> {
                    if (r.error is ApiError.TooManyWalks && cap > MAX_WALKS_AFTER_FIRST) {
                        cap = MAX_WALKS_AFTER_FIRST
                        continue
                    }
                    val e = r.error
                    if (sendPlay != null && e is ApiError.Http && e.code in PLAY_ONLY_ERRORS) {
                        // Play is optional (PF0-3): its own 400 never costs the walks; resend the pure upload.
                        AppLog.w(TAG, "play part refused (${e.code}); resending without it")
                        settlePlay(play, PlayOutcome.Refused(e.code, stillPending = f.pending.mapTo(HashSet()) { it.id }))
                        withPlay = false
                        continue
                    }
                    val result = failure(f, e, delta, walks)
                    if (play != null && walks.isNotEmpty()) {
                        // The walks left the queue uncredited (422, walk limit, another 4xx): so do their claims.
                        val left = readFile().pending.mapTo(HashSet()) { it.id }
                        if (walks.none { it.id in left }) settlePlay(play, PlayOutcome.Refused(e.code, stillPending = left))
                    }
                    return result
                }
            }
        }
    }

    private suspend fun playPartFor(f: SyncFile, walks: List<WalkDto>): PlayPart? {
        val provider = playPart ?: return null
        return try {
            provider(f.walkIds.mapValues { it.value.id }, walks.mapTo(HashSet()) { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "play part failed: ${e.message}") // claims stay queued; the pure upload goes
            null
        }
    }

    /** Play is optional: a failure while recording its outcome never changes the sync result (PF0-3). */
    private suspend fun settlePlay(play: PlayPart, outcome: PlayOutcome) {
        try {
            play.settle(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "play settle failed: ${e.message}")
        }
    }

    private suspend fun failure(f: SyncFile, error: ApiError, sent: Map<String, String>, walks: List<WalkDto>): SyncState = when {
        error is ApiError.Unauthorized -> SyncState.SignedOut // the client already cleared the session
        error.isRetryable -> SyncState.Pending(f.pending.size, error)
        error is ApiError.Implausible && walks.isEmpty() -> {
            // Bits without a walk: keep them out of the baseline so they go again with the next walk (P1-2).
            AppLog.w(TAG, "upload without walks rejected: $error")
            SyncState.Error(error)
        }
        error is ApiError.Implausible || error is ApiError.WalkLimit -> {
            // Don't retry the same payload (api.md): give up on these bits and walks for this account; keep them locally.
            AppLog.w(TAG, "upload rejected: $error")
            settle(f, sent, walks, notice = true)
            _rejectedNotice.value = true
            SyncState.Rejected(error)
        }
        error is ApiError.Http && error.status in 400..499 -> {
            AppLog.e(TAG, "upload refused: $error")
            settle(f, sent, walks, notice = false)
            SyncState.Error(error)
        }
        else -> SyncState.Error(error)
    }

    private suspend fun settle(f: SyncFile, sent: Map<String, String>, walks: List<WalkDto>, notice: Boolean) {
        val ids = walks.mapTo(HashSet()) { it.id }
        write(
            f.copy(
                baseline = Bitsets.union(f.baseline, sent),
                pending = f.pending.filterNot { it.id in ids },
                rejectedNotice = f.rejectedNotice || notice,
            ),
        )
    }

    /** Unions the server's state into local storage; returns [f] with the baseline advanced. */
    private suspend fun merge(f: SyncFile, r: CoverageResponse, sent: Map<String, String>): SyncFile {
        val server = Bitsets.toState(r.segments)
        val changed = coverage.mergeRemote(server, r.completedTrailIds.toSet())
        if (changed) onCoverageChanged()
        return f.copy(baseline = Bitsets.union(Bitsets.union(f.baseline, sent), r.segments))
    }

    private fun scheduleRetry(error: ApiError) {
        val base = backoffMs.getOrNull(retryAttempt) ?: return // exhausted: wait for the next trigger
        val serverHint = when (error) {
            is ApiError.RateLimited -> error.retryAfterSeconds
            is ApiError.Unavailable -> error.retryAfterSeconds
            else -> null
        }?.times(1000) ?: 0
        retryAttempt++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(maxOf(base, serverHint))
            request()
        }
    }

    private fun resetRetry() {
        retryAttempt = 0
        retryJob?.cancel()
    }

    private val ApiError.code: String
        get() = when (this) {
            is ApiError.Implausible -> "implausible"
            is ApiError.WalkLimit -> "walk_limit"
            is ApiError.TooManyWalks -> "too_many_walks"
            is ApiError.Http -> code
            else -> "error"
        }

    private suspend fun readFile(): SyncFile = withContext(io) { readFileNow() }

    private fun readFileNow(): SyncFile = file.read() ?: SyncFile()

    private suspend fun write(f: SyncFile) = withContext(io) { file.write(f) }

    private suspend fun update(change: (SyncFile) -> SyncFile) = write(change(readFile()))

    companion object {
        private const val TAG = "sync"
        const val TICK_M = 5
        const val FOREGROUND_INTERVAL_MS = 15 * 60_000L

        /** api.md: walks per upload before the account's first accepted upload / after it. */
        const val MAX_WALKS_FIRST = 2000
        const val MAX_WALKS_AFTER_FIRST = 200

        /** 400s about the Play fields alone (`backend/src/play/claims.ts`): the coverage part was fine. */
        private val PLAY_ONLY_ERRORS = setOf("invalid_play", "too_many_claims")
    }
}
