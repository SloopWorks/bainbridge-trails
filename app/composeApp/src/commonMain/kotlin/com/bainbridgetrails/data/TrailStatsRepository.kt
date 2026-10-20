package com.bainbridgetrails.data

import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.TrailStatDto
import com.bainbridgetrails.net.TrailStatsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [stats] = last good response (memory only), [error] = last failure (community stats just hide when offline). */
data class TrailStatsState(val stats: TrailStatsDto? = null, val fetchedAt: Long? = null, val error: ApiError? = null) {
    fun forTrail(trailId: String): TrailStatDto? = stats?.trails?.get(trailId)
}

/** Public community trail stats (TL-4, TL-11), cached for [ttlMs] (server: `max-age=300`). */
class TrailStatsRepository(
    private val api: ApiClient,
    private val now: () -> Long,
    private val ttlMs: Long = 5 * 60_000L,
) {
    private val _state = MutableStateFlow(TrailStatsState())
    val state: StateFlow<TrailStatsState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Returns the cached stats when younger than the TTL (unless [force]), else fetches. */
    suspend fun get(force: Boolean = false): ApiResult<TrailStatsDto> = mutex.withLock {
        val cur = _state.value
        val fresh = cur.stats != null && cur.fetchedAt != null && now() - cur.fetchedAt < ttlMs
        if (fresh && !force) return@withLock ApiResult.Ok(cur.stats!!)
        val r = api.trailStats()
        _state.value = when (r) {
            is ApiResult.Ok -> TrailStatsState(r.value, now(), null)
            is ApiResult.Failure -> cur.copy(error = r.error)
        }
        r
    }
}
