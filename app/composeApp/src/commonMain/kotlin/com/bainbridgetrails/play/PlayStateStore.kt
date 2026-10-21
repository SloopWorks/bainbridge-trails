package com.bainbridgetrails.play

import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.play.schedule.PlayUserState
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * One Logbook row as stored on this phone (product § 3.8). [kind] / [board] are the product's enum words
 * (`treasure`, `ink`, `start_balance`, …; `pending` | `accepted` | `local_only`) kept as strings so the rules model
 * (`:play` rules, L1-6) owns the vocabulary. [date] is the island date only: no time of day is ever stored.
 */
@Serializable
data class LogbookRecord(
    val id: String,
    val kind: String,
    val what: String,
    val why: String,
    /** Island date `yyyy-MM-dd`. */
    val date: String,
    val marks: Int,
    val walkId: String? = null,
    /** Occurrence key (debug drawer only). */
    val contentId: String? = null,
    /** The Monday of its week. */
    val weekId: String,
    val board: String = "pending",
)

/**
 * Play's per-goal user state (`<appData>/play/<goal>-state.json`): [found] occurrence / placement keys (claimed or
 * restored), [solved] riddle item id → island date of the armchair solve, [hints] riddle item id → hints used,
 * the [logbook] and [startBalanceCredited] (the opt-in starting balance is credited once ever, PF1-2). Kept when Play
 * is turned off (product § 5.5: nothing is deleted).
 */
@Serializable
data class PlayState(
    val schema: Int = 1,
    val found: Set<String> = emptySet(),
    val solved: Map<String, String> = emptyMap(),
    val hints: Map<String, Int> = emptyMap(),
    val logbook: List<LogbookRecord> = emptyList(),
    val startBalanceCredited: Boolean = false,
) {
    /** The scheduler's view (salt is v2). A solve counts from the start of its island day. */
    fun userState(): PlayUserState = PlayUserState(
        salt = null,
        resolved = solved.mapNotNull { (id, d) ->
            runCatching { id to IslandTime.instantOf(LocalDate.parse(d), LocalTime(0, 0)) }.getOrNull()
        }.toMap(),
        foundOcc = found,
    )
}

/**
 * Loads once (off Main) and writes atomically (tmp + move, like SettingsRepository) through [JsonFile]. An unreadable
 * file reads as an empty state (logged), never a crash.
 */
class PlayStateStore(path: String, private val io: CoroutineDispatcher = Dispatchers.Default) {
    private val file = JsonFile(path, PlayState.serializer())
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    suspend fun load(): PlayState = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): PlayState {
        if (!loaded) {
            loaded = true
            withContext(io) { file.read() }?.let { _state.value = it }
        }
        return _state.value
    }

    suspend fun update(change: (PlayState) -> PlayState): PlayState = mutex.withLock {
        val next = change(loadLocked())
        if (next != _state.value) {
            _state.value = next
            withContext(io) { file.write(next) }
        }
        next
    }

    /** Debug "simulate user state": forget finds, solves and hints (the Logbook and start-balance flag stay). */
    suspend fun clearFinds() = update { it.copy(found = emptySet(), solved = emptyMap(), hints = emptyMap()) }
}
