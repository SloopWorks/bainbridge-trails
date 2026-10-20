package com.bainbridgetrails.data

import com.bainbridgetrails.tracking.WalkRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local walk history (newest first) and the derived "trail metres walked" (Σ matchedM; repeats count). */
data class WalkHistory(val walks: List<WalkRecord>) {
    val trailMetresWalked: Double = walks.sumOf { it.matchedM }

    companion object {
        val EMPTY = WalkHistory(emptyList())
    }
}

/** Reads `walks.json` (written by the Finalizer) off Main; call [refresh] after each finalized walk. */
class WalkHistoryRepository(
    private val scope: CoroutineScope,
    private val read: () -> List<WalkRecord>,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _history = MutableStateFlow<WalkHistory?>(null)
    /** Null until first read. */
    val history: StateFlow<WalkHistory?> = _history.asStateFlow()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow(): WalkHistory {
        val next = WalkHistory(withContext(io) { runCatching(read).getOrDefault(emptyList()) })
        _history.value = next
        return next
    }
}
