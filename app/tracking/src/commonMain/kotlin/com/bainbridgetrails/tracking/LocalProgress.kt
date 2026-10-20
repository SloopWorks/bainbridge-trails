package com.bainbridgetrails.tracking

import kotlinx.coroutines.sync.withLock

/**
 * Whole-directory operations on local progress (`coverage.json`, `walks.json`, `sessions/`). Both hold
 * [CoverageStore.writeLock] (then [WalkIndex.writeLock]) so they never interleave with a finalize or a sync merge.
 * Blocking file I/O: call off the main thread.
 */
object LocalProgress {
    /** Raw tracks are deleted this long after their walk was finalized (product P1, spike notes). */
    const val RAW_TRACK_RETENTION_MS: Long = 30L * 24 * 60 * 60_000

    /**
     * "Also clear this phone": deletes every session file except [keepSessions] (the walk being recorded), resets
     * coverage, sticky completions and finalized ids, and clears walk history. Old walks can then never be
     * re-finalized, since their raw tracks are gone.
     */
    suspend fun wipe(dir: String, keepSessions: Set<String>) = CoverageStore.writeLock.withLock {
        val sessions = SessionStore(dir)
        for (id in sessions.ids()) if (id !in keepSessions) sessions.delete(id)
        CoverageStore(dir).reset()
        WalkIndex(dir).clear()
    }

    /**
     * Deletes the raw track of every finished session that was finalized at least [retentionMs] before [nowMs] and
     * has a walk record (its history entry and summary no longer need the track). The id stays in
     * `coverage.json`'s `verified` map (the durable tombstone [CoverageStore.finalizedIds]), and with the file gone
     * [SessionStore.finished] no longer lists it, so it is never re-finalized. Returns the deleted ids.
     */
    suspend fun pruneRawTracks(dir: String, nowMs: Long, retentionMs: Long = RAW_TRACK_RETENTION_MS): List<String> =
        CoverageStore.writeLock.withLock {
            val finalized = CoverageStore(dir).finalizedIds()
            val walks = WalkIndex(dir).all().associateBy { it.sessionId }
            val sessions = SessionStore(dir)
            sessions.finished().filter { id ->
                val walk = walks[id] ?: return@filter false
                id in finalized && nowMs - maxOf(walk.finalizedAt, walk.endedAt) >= retentionMs
            }.onEach { sessions.delete(it) }
        }
}
