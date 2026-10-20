package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageState
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CoverageFile(
    val v: Int = 1,
    val tickM: Double = 5.0,
    val state: CoverageState = CoverageState.EMPTY,
    val verified: Map<String, Boolean> = emptyMap(),
    /** Sticky completed trail ids (decisions.md TL-7): union-only, never removed by later coverage or data. */
    val completed: Set<String> = emptySet(),
)

/**
 * Persistent merged coverage at `<dir>/coverage.json`:
 * `{"v":1,"tickM":5.0,"state":<CoverageState.encode()>,"verified":{"<sessionId>":true},"completed":["<trailId>"]}`.
 * Writes go to `coverage.json.tmp` then atomically replace the real file, so a crash leaves the old file intact.
 */
class CoverageStore(dir: String) {
    private val dirPath = Path(dir)
    private val path = Path(dir, "coverage.json")
    private val tmp = Path(dir, "coverage.json.tmp")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        SystemFileSystem.createDirectories(dirPath)
    }

    private fun read(): CoverageFile {
        if (!SystemFileSystem.exists(path)) return CoverageFile()
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        return json.decodeFromString(CoverageFile.serializer(), text)
    }

    /** Sessions already merged after their Stop (keys of `verified`); see [merge]. */
    fun finalizedIds(): Set<String> = read().verified.keys

    /** Sticky completed trail ids (TL-7). */
    fun completedIds(): Set<String> = read().completed

    /** EMPTY if missing. */
    fun load(): CoverageState = read().state

    /**
     * Unions [state] into the stored coverage (idempotent). [finalized] = the session had its Stop: only then is the
     * id recorded, so a session merged early (stop timeout) is merged again, complete, on the next launch.
     */
    fun merge(sessionId: String, state: CoverageState, verified: Boolean, finalized: Boolean = true, completed: Set<String> = emptySet()) {
        val cur = read()
        val ids = if (finalized) cur.verified + (sessionId to verified) else cur.verified
        write(cur.copy(state = cur.state.union(state), verified = ids, completed = cur.completed + completed))
    }

    /**
     * Replaces the stored coverage with EMPTY and forgets finalized ids and sticky completions (debug tools;
     * [LocalProgress.wipe], which also deletes the raw tracks so nothing is re-finalized). Hold [writeLock].
     */
    fun reset() = write(CoverageFile())

    /** Adds sticky completions without coverage (debug tools: test the celebration; sync: server's sticky set). */
    fun addCompleted(trailIds: Set<String>) = read().let { write(it.copy(completed = it.completed + trailIds)) }

    /**
     * Sync: unions the server's [state] and sticky [completed] ids in one write (union-only, never removes).
     * Returns true when the stored file changed. Hold [writeLock] around it (the Finalizer writes concurrently).
     */
    fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean {
        val cur = read()
        val next = cur.copy(state = cur.state.union(state), completed = cur.completed + completed)
        if (next == cur) return false
        write(next)
        return true
    }

    companion object {
        /**
         * Process-wide guard for read-modify-write of `coverage.json` across store instances (Finalizer vs sync):
         * without it a sync merge could overwrite a walk merged between its read and its write.
         */
        val writeLock: Mutex = Mutex()
    }

    /** Unions [state] in without touching session bookkeeping (debug tools: inject coverage). */
    fun inject(state: CoverageState) = read().let { write(it.copy(state = it.state.union(state))) }

    private fun write(next: CoverageFile) {
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(CoverageFile.serializer(), next))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, path)
    }
}
