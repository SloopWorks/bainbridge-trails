package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

/** One finished walk for the history list. Deliberately holds no coordinates (raw tracks stay in session files). */
@Serializable
data class WalkRecord(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    /** Gated walking distance; GPS gaps over [Gating.MAX_GAP_MS] add nothing. */
    val distanceM: Double,
    /** Trails whose coverage rose on this walk, highest resulting fraction first. */
    val trailIds: List<String>,
    val verified: Boolean,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

@Serializable
private data class WalksFile(val v: Int = 1, val walks: List<WalkRecord> = emptyList())

/**
 * Walk history index at `<dir>/walks.json`, written by [Finalizer] after each merge. Re-finalising a session
 * (stop-timeout path, then again after its Stop) replaces its entry. Atomic tmp + move like [CoverageStore].
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

    fun upsert(record: WalkRecord) {
        val next = (all().filterNot { it.sessionId == record.sessionId } + record).sortedByDescending { it.startMs }
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(WalksFile.serializer(), WalksFile(walks = next)))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, path)
    }

    companion object {
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
