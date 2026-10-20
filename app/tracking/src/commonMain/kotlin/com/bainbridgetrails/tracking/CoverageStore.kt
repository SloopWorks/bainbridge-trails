package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageState
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
    /** Sessions merged early (before their Stop) whose summary the user already saw; cleared once finalized. */
    val shown: Set<String> = emptySet(),
)

/**
 * Persistent merged coverage at `<dir>/coverage.json`:
 * `{"v":1,"tickM":5.0,"state":<CoverageState.encode()>,"verified":{"<sessionId>":true},"shown":["<sessionId>"]}`.
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

    /**
     * Sessions merged before their Stop (stop-timeout path) whose summary was already shown. [RecordingController.onLaunch]
     * re-merges them silently instead of showing a second "Walk saved" sheet.
     */
    fun shownIds(): Set<String> = read().shown

    /** EMPTY if missing. */
    fun load(): CoverageState = read().state

    /**
     * Unions [state] into the stored coverage (idempotent). [finalized] = the session had its Stop: only then is the
     * id recorded, so a session merged early (stop timeout) is merged again, complete, on the next launch.
     * [summaryShown] (early merges only) records that its summary was shown, so that re-merge stays silent.
     */
    fun merge(sessionId: String, state: CoverageState, verified: Boolean, finalized: Boolean = true, summaryShown: Boolean = false) {
        val cur = read()
        val ids = if (finalized) cur.verified + (sessionId to verified) else cur.verified
        val shown = when {
            finalized -> cur.shown - sessionId
            summaryShown -> cur.shown + sessionId
            else -> cur.shown
        }
        write(cur.copy(state = cur.state.union(state), verified = ids, shown = shown))
    }

    /** Replaces the stored coverage with EMPTY and forgets finalized ids (debug tools only). */
    fun reset() = write(CoverageFile())

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
