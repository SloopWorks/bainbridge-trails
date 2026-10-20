package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.CoverageResponse
import com.bainbridgetrails.net.CoverageUpload
import com.bainbridgetrails.net.JsonFile
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

/** Local coverage as sync sees it (production: tracking's CoverageStore under its write lock). */
interface LocalCoverage {
    suspend fun load(): CoverageState

    /** Unions [state] and sticky [completed] into the store; true when anything changed. */
    suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean
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
 */
@OptIn(ExperimentalUuidApi::class)
class SyncRepository(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val queuePath: String,
    /** Signed-in user id, null when signed out. */
    private val currentUserId: () -> String?,
    private val coverage: LocalCoverage,
    /** Finished local walks (WalkIndex / WalkHistoryRepository). */
    private val localWalks: suspend () -> List<LocalWalk>,
    /** Bundled dataset version (`DatasetRepository.await().dataset.version`). */
    private val datasetVersion: suspend () -> String,
    /** Local coverage changed through a merge (refresh progress/map). */
    private val onCoverageChanged: () -> Unit = {},
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val backoffMs: List<Long> = listOf(30_000L, 120_000L, 600_000L),
    private val newWalkId: () -> String = { Uuid.random().toString() },
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
        var f = prepare(userId)
        val backfill = f.lastSuccessAt == null
        _state.value = SyncState.Syncing(backfill)
        f = f.copy(lastAttemptAt = now())
        write(f)
        val result = try {
            run(f)
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
    private suspend fun prepare(userId: String): SyncFile {
        var f = readFile()
        val walks = localWalks()
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

    private suspend fun run(start: SyncFile): SyncState {
        var f = start
        val version = datasetVersion()
        val local = withContext(io) { Bitsets.fromState(coverage.load()) }
        var delta = Bitsets.minus(local, f.baseline)
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
        while (true) {
            val walks = f.pending.take(cap)
            val upload = CoverageUpload(version, TICK_M, delta, walks)
            when (val r = api.putCoverage(upload)) {
                is ApiResult.Ok -> {
                    val sentIds = walks.mapTo(HashSet()) { it.id }
                    f = merge(f, r.value, sent = delta).copy(
                        pending = f.pending.filterNot { it.id in sentIds },
                        acceptedOnce = true,
                        lastSuccessAt = now(),
                    )
                    write(f)
                    AppLog.i(TAG, "uploaded ${walks.size} walks, ${delta.size} segments; +${r.value.newlyCoveredM ?: 0.0} m")
                    if (f.pending.isEmpty()) return SyncState.Idle(now(), 0)
                    // More walks than one upload allows: send the rest (their bits went with the first batch).
                    delta = emptyMap()
                    cap = MAX_WALKS_AFTER_FIRST
                }
                is ApiResult.Failure -> {
                    if (r.error is ApiError.TooManyWalks && cap > MAX_WALKS_AFTER_FIRST) {
                        cap = MAX_WALKS_AFTER_FIRST
                        continue
                    }
                    return failure(f, r.error, delta, walks)
                }
            }
        }
    }

    private suspend fun failure(f: SyncFile, error: ApiError, sent: Map<String, String>, walks: List<WalkDto>): SyncState = when {
        error is ApiError.Unauthorized -> SyncState.SignedOut // the client already cleared the session
        error.isRetryable -> SyncState.Pending(f.pending.size, error)
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
    }
}
