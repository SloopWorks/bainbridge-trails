package com.bainbridgetrails.data

import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.LeaderboardDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class BoardCache(val board: LeaderboardDto? = null, val fetchedAt: Long? = null, val hidden: Set<String> = emptySet())

/**
 * [board] = last good response (from the network or the offline cache), with rows the user reported removed
 * (F9-3). [updatedAt] = when it was fetched (`board_updated`). [error] = the last refresh failure (Offline →
 * `board_offline_*`), cleared by the next success. [loading] while a refresh runs.
 */
data class LeaderboardState(
    val board: LeaderboardDto? = null,
    val updatedAt: Long? = null,
    val loading: Boolean = false,
    val error: ApiError? = null,
    /**
     * Everyone this user hid (reported or "Hide this person") on this phone, whether or not they are on the current
     * board (they stay hidden if they come back); footer "Hidden people (N)", whose "Show again" un-hides them all.
     */
    val hiddenCount: Int = 0,
)

sealed interface ReportResult {
    /** Sent (or a duplicate): show `report_thanks`; the row is already hidden. */
    data object Reported : ReportResult
    data object SignInRequired : ReportResult
    data class Failed(val error: ApiError) : ReportResult
}

/** F8 leaderboard, cached in memory and in [cachePath] for offline display (F8-3). */
class LeaderboardRepository(
    private val api: ApiClient,
    cachePath: String,
    private val isSignedIn: () -> Boolean,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val cache = JsonFile(cachePath, BoardCache.serializer())
    private val _state = MutableStateFlow(LeaderboardState())
    val state: StateFlow<LeaderboardState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var hidden: Set<String> = emptySet()
    private var raw: LeaderboardDto? = null
    private var loaded = false

    private suspend fun loadCache() {
        if (loaded) return
        loaded = true
        val c = withContext(io) { cache.read() } ?: return
        hidden = c.hidden
        raw = c.board
        _state.update { it.copy(board = c.board?.withoutHidden(), updatedAt = c.fetchedAt, hiddenCount = hidden.size) }
    }

    /** Fetches the board; on failure keeps showing the cache and sets [LeaderboardState.error]. */
    suspend fun refresh(limit: Int = 100): ApiResult<LeaderboardDto> {
        mutex.withLock { loadCache() }
        _state.update { it.copy(loading = true) }
        val r = api.leaderboard(limit)
        mutex.withLock {
            when (r) {
                is ApiResult.Ok -> {
                    val t = now()
                    raw = r.value
                    _state.value = LeaderboardState(r.value.withoutHidden(), t, loading = false, error = null, hiddenCount = hidden.size)
                    withContext(io) { runCatching { cache.write(BoardCache(r.value, t, hidden)) } }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
        return r
    }

    /** F9c: report [userId]'s name; the row disappears for this user immediately (and stays hidden). */
    suspend fun reportName(userId: String): ReportResult {
        if (!isSignedIn()) return ReportResult.SignInRequired
        val r = api.reportName(userId)
        return when {
            // 404: the user is gone (account deleted since the board was fetched): nothing to report, hide the row.
            r is ApiResult.Ok || (r is ApiResult.Failure && r.error is ApiError.NotFound) -> {
                hide(userId)
                ReportResult.Reported
            }
            else -> ReportResult.Failed((r as ApiResult.Failure).error)
        }
    }

    /** Auth changed: `isYou`/`you` in the cache belong to the old session; drop them until the next refresh. */
    suspend fun onAuthChanged() = mutex.withLock {
        loadCache()
        val b = raw ?: return@withLock
        raw = b.copy(you = null, entries = b.entries.map { it.copy(isYou = false) })
        _state.update { it.copy(board = raw?.withoutHidden()) }
    }

    /**
     * "Hide this person" (App Review 1.2 block): their row disappears from this phone's board right away and stays
     * hidden (local only, nothing is sent). Undo with [showHiddenAgain].
     */
    suspend fun hidePerson(userId: String) = hide(userId)

    /** Undo of one [hidePerson]. */
    suspend fun unhidePerson(userId: String) = mutex.withLock {
        loadCache()
        setHiddenLocked(hidden - userId)
    }

    /** "Show again": every hidden person (reported or hidden) is back on this phone's board. */
    suspend fun showHiddenAgain() = setHidden(emptySet())

    private suspend fun hide(userId: String) = mutex.withLock {
        loadCache()
        setHiddenLocked(hidden + userId)
    }

    private suspend fun setHidden(next: Set<String>) = mutex.withLock {
        loadCache()
        setHiddenLocked(next)
    }

    private suspend fun setHiddenLocked(next: Set<String>) {
        hidden = next
        _state.update { it.copy(board = raw?.withoutHidden(), hiddenCount = hidden.size) }
        // Saved even with no board yet (hidden before the first fetch), so the choice survives a restart.
        val t = if (raw == null) null else _state.value.updatedAt ?: now()
        withContext(io) { runCatching { cache.write(BoardCache(raw, t, hidden)) } }
    }

    private fun LeaderboardDto.withoutHidden(): LeaderboardDto =
        if (hidden.isEmpty()) this else copy(entries = entries.filter { it.userId !in hidden || it.isYou })
}
