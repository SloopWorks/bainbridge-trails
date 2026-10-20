package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

@Serializable
data class WalkTrail(val trailId: String, val before: Double, val after: Double)

/**
 * One finalised walk for the history list (docs/mvp/product.md §6). Deliberately holds no coordinates
 * (raw tracks stay in session files) and is never synced (times are private).
 */
@Serializable
data class WalkRecord(
    val sessionId: String,
    /** Start event wall clock (epoch ms). */
    val startedAt: Long,
    /** Stop event wall clock; the last fix (or start) when the walk was merged before its Stop. */
    val endedAt: Long,
    /** Gated, filtered GPS path length; GPS gaps over [Gating.MAX_GAP_MS] add nothing. */
    val distanceM: Double,
    /** This walk's own matched length: distinct ticks on any mapped segment × tick length (feeds trail miles). */
    val matchedM: Double,
    val verified: Boolean,
    val approximateLocation: Boolean = false,
    val islandBefore: Double = 0.0,
    val islandAfter: Double = 0.0,
    /** Trails whose coverage rose on this walk, highest resulting fraction first. */
    val trails: List<WalkTrail> = emptyList(),
    val newlyCompleted: List<String> = emptyList(),
    /** The results sheet for this walk was shown; it is never shown again (F0-3 / F6-5). */
    val summaryShown: Boolean = false,
    /** Wall clock of the last finalize (epoch ms; 0 in records written before it existed). Raw-track retention. */
    val finalizedAt: Long = 0,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

@Serializable
private data class WalksFile(val v: Int = 1, val walks: List<WalkRecord> = emptyList())

/**
 * Walk history index at `<dir>/walks.json`, written by [Finalizer] after each merge. Re-finalising a session
 * (stop-timeout path, then again after its Stop) replaces its entry. Atomic tmp + move like [CoverageStore];
 * every read-modify-write holds the process-wide [writeLock] (one shared tmp path per directory).
 */
class WalkIndex(dir: String) {
    private val path = Path(dir, "walks.json")
    private val tmp = Path(dir, "walks.json.tmp")

    init {
        SystemFileSystem.createDirectories(Path(dir))
    }

    /** Newest first. Empty if missing or unreadable (the index is derived data; losing it loses no coverage). */
    fun all(): List<WalkRecord> {
        if (!SystemFileSystem.exists(path)) return emptyList()
        return runCatching {
            json.decodeFromString(WalksFile.serializer(), SystemFileSystem.source(path).buffered().use { it.readString() }).walks
        }.getOrElse { emptyList() }
    }

    /** Ids of walks whose results sheet was already shown. */
    fun shownIds(): Set<String> = all().filter { it.summaryShown }.mapTo(HashSet()) { it.sessionId }

    /** Replaces [record]'s entry; a previously shown walk stays shown. */
    suspend fun upsert(record: WalkRecord) = writeLock.withLock {
        val cur = all()
        val wasShown = cur.any { it.sessionId == record.sessionId && it.summaryShown }
        val merged = if (wasShown) record.copy(summaryShown = true) else record
        write((cur.filterNot { it.sessionId == record.sessionId } + merged).sortedByDescending { it.startedAt })
    }

    /** Marks [sessionId]'s results sheet as shown (no-op if the walk is not indexed). */
    suspend fun markShown(sessionId: String) = writeLock.withLock {
        val cur = all()
        if (cur.none { it.sessionId == sessionId && !it.summaryShown }) return@withLock
        write(cur.map { if (it.sessionId == sessionId) it.copy(summaryShown = true) else it })
    }

    /** Forgets every walk (local wipe). */
    suspend fun clear() = writeLock.withLock {
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    /** Σ [WalkRecord.matchedM] over all walks: "trail miles walked" (repeats count). */
    fun totalMatchedM(): Double = all().sumOf { it.matchedM }

    private fun write(next: List<WalkRecord>) {
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(WalksFile.serializer(), WalksFile(walks = next)))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, path)
    }

    companion object {
        /**
         * Process-wide guard for read-modify-write of `walks.json` (Finalizer, results sheet, wipe). Not reentrant.
         * Lock order: [CoverageStore.writeLock] before this one.
         */
        val writeLock: Mutex = Mutex()

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** Planar path length of [fixes] (time order), skipping steps across gaps longer than [Gating.MAX_GAP_MS]. */
        fun distanceM(fixes: List<Fix>, proj: LocalProjection): Double {
            var total = 0.0
            for (i in 1 until fixes.size) {
                val a = fixes[i - 1]
                val b = fixes[i]
                if (b.timeMs - a.timeMs > Gating.MAX_GAP_MS) continue
                val pa = proj.toXy(a.lon, a.lat)
                val pb = proj.toXy(b.lon, b.lat)
                total += hypot(pb.x - pa.x, pb.y - pa.y)
            }
            return total
        }
    }
}
