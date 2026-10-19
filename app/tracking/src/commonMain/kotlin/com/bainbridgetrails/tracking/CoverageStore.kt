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
)

/**
 * Persistent merged coverage at `<dir>/coverage.json`:
 * `{"v":1,"tickM":5.0,"state":<CoverageState.encode()>,"verified":{"<sessionId>":true}}`.
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

    /** EMPTY if missing. */
    fun load(): CoverageState = read().state

    fun merge(sessionId: String, state: CoverageState, verified: Boolean) {
        val cur = read()
        val next = cur.copy(state = cur.state.union(state), verified = cur.verified + (sessionId to verified))
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(CoverageFile.serializer(), next))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, path)
    }
}
