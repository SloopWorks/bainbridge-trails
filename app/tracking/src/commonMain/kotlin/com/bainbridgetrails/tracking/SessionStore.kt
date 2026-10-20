package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readLine
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

data class RecordedSession(
    val id: String,
    val fixes: List<Fix>,
    val activities: List<ActivitySample>,
    val steps: List<StepSample>,
    val activityAvailable: Boolean,
    val stepsAvailable: Boolean,
    val finished: Boolean,
    /** A "precise" SourceState reported reduced accuracy (iOS approximate location). */
    val approximateLocation: Boolean = false,
    /** Wall-clock time of the Start event (null if missing). */
    val startMs: Long? = null,
    /** Wall-clock time of the Stop event (null while unfinished). */
    val stopMs: Long? = null,
)

/**
 * Append-only JSONL store, one file per session under `<dir>/sessions/<id>.jsonl`.
 * Each event is written and flushed on its own so a crash loses at most the last line.
 * Never logs event contents (raw tracks are private).
 */
class SessionStore(dir: String) {
    private val sessionsDir = Path(dir, "sessions")
    private var currentId: String? = null

    init {
        SystemFileSystem.createDirectories(sessionsDir)
    }

    private fun pathOf(id: String): Path {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "invalid session id" }
        return Path(sessionsDir, "$id.jsonl")
    }

    fun begin(sessionId: String, timeMs: Long) {
        val path = pathOf(sessionId)
        check(!SystemFileSystem.exists(path)) { "session already exists" }
        currentId = sessionId
        append(SessionEvent.Start(sessionId, timeMs))
    }

    /**
     * Reopens an unfinished session for appending. Requires the file to exist and have no Stop.
     * Drops any truncated tail (everything after the last '\n') so later appends start on a clean line.
     */
    fun resume(sessionId: String) {
        val path = pathOf(sessionId)
        check(SystemFileSystem.exists(path)) { "session not found" }
        check(readEvents(sessionId).none { it is SessionEvent.Stop }) { "session already finished" }
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        val keep = text.substring(0, text.lastIndexOf('\n') + 1)
        if (keep.length != text.length) {
            val tmp = Path(sessionsDir, "$sessionId.jsonl.tmp")
            SystemFileSystem.sink(tmp).buffered().use {
                it.writeString(keep)
                it.flush()
            }
            SystemFileSystem.atomicMove(tmp, path)
        }
        currentId = sessionId
    }

    fun append(event: SessionEvent) {
        val id = checkNotNull(currentId) { "no active session" }
        val line = json.encodeToString(SessionEvent.serializer(), event) + "\n"
        SystemFileSystem.sink(pathOf(id), append = true).buffered().use {
            it.writeString(line)
            it.flush()
        }
    }

    fun end(timeMs: Long) {
        append(SessionEvent.Stop(timeMs))
        currentId = null
    }

    /**
     * Sessions with a Start but no Stop. Cheap enough for app start: per file it parses only the first line and
     * the last line (Stop is always the last event written), reading at most [TAIL_BYTES] of the tail.
     */
    fun unfinished(): List<String> = ids().filter { tailState(it) == Tail.OPEN }

    /**
     * Sessions with a Start whose last event is Stop (same cheap head/tail check as [unfinished]). A Stop that lost
     * its trailing '\n' in a crash still counts; the scan repairs the newline so [resume] and [load] agree.
     * Call only when no recorder is writing (app launch): a finished session never has a writer.
     */
    fun finished(): List<String> = ids().filter { tailState(it) == Tail.STOPPED }

    private fun ids(): List<String> {
        if (!SystemFileSystem.exists(sessionsDir)) return emptyList()
        return SystemFileSystem.list(sessionsDir)
            .map { it.name }
            .filter { it.endsWith(SUFFIX) }
            .map { it.removeSuffix(SUFFIX) }
            .sorted()
    }

    private fun parse(line: String?): SessionEvent? =
        if (line.isNullOrBlank()) null else try {
            json.decodeFromString(SessionEvent.serializer(), line)
        } catch (_: Exception) {
            null
        }

    private enum class Tail { NOT_A_SESSION, OPEN, STOPPED }

    private fun tailState(id: String): Tail {
        val path = pathOf(id)
        val size = SystemFileSystem.metadataOrNull(path)?.size ?: return Tail.NOT_A_SESSION
        val first = SystemFileSystem.source(path).buffered().use { it.readLine() }
        if (parse(first) !is SessionEvent.Start) return Tail.NOT_A_SESSION
        val tail = SystemFileSystem.source(path).buffered().use {
            val skip = (size - TAIL_BYTES).coerceAtLeast(0)
            it.skip(skip)
            it.readString()
        }
        // A Stop written without its '\n' (crash between the two) is still a Stop: repair the newline.
        val unterminated = tail.substring(tail.lastIndexOf('\n') + 1)
        if (unterminated.isNotEmpty() && parse(unterminated) is SessionEvent.Stop) {
            SystemFileSystem.sink(path, append = true).buffered().use {
                it.writeString("\n")
                it.flush()
            }
            return Tail.STOPPED
        }
        // Otherwise the last complete line decides; anything after the final '\n' is a truncated tail.
        val complete = tail.substring(0, tail.lastIndexOf('\n').coerceAtLeast(0))
        val lastLine = complete.substring(complete.lastIndexOf('\n') + 1)
        return if (parse(lastLine) is SessionEvent.Stop) Tail.STOPPED else Tail.OPEN
    }

    fun load(sessionId: String): RecordedSession = toSession(sessionId, readEvents(sessionId))

    fun delete(sessionId: String) {
        val path = pathOf(sessionId)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        if (currentId == sessionId) currentId = null
    }

    /** Lines that fail to parse (e.g. a truncated tail after a crash) are dropped. */
    private fun readEvents(id: String): List<SessionEvent> {
        val path = pathOf(id)
        if (!SystemFileSystem.exists(path)) return emptyList()
        return parseEvents(SystemFileSystem.source(path).buffered().use { it.readString() })
    }

    companion object {
        private const val SUFFIX = ".jsonl"
        private const val TAIL_BYTES = 4_096L

        private val json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
        }

        /** Builds a [RecordedSession] from session JSONL [text] (e.g. a pulled file, for offline re-gating). */
        fun parse(sessionId: String, text: String): RecordedSession = toSession(sessionId, parseEvents(text))

        private fun parseEvents(text: String): List<SessionEvent> = text.split('\n').mapNotNull { line ->
            if (line.isBlank()) null
            else try {
                json.decodeFromString(SessionEvent.serializer(), line)
            } catch (_: Exception) {
                null
            }
        }

        private fun toSession(sessionId: String, events: List<SessionEvent>): RecordedSession {
            val fixes = mutableListOf<Fix>()
            val acts = mutableListOf<ActivitySample>()
            val steps = mutableListOf<StepSample>()
            val lastAvailable = mutableMapOf<String, Boolean>()
            var finished = false
            var startMs: Long? = null
            var stopMs: Long? = null
            for (e in events) {
                when (e) {
                    is SessionEvent.Start -> if (startMs == null) startMs = e.timeMs
                    is SessionEvent.FixEvent -> fixes += Fix(e.timeMs, e.lon, e.lat, e.accuracyM, e.isMock)
                    is SessionEvent.Act -> acts += e.sample
                    is SessionEvent.Steps -> steps += e.sample
                    is SessionEvent.SourceState -> lastAvailable[e.source] = e.available
                    is SessionEvent.Diag -> Unit
                    is SessionEvent.Stop -> {
                        finished = true
                        stopMs = e.timeMs
                    }
                }
            }
            return RecordedSession(
                id = sessionId,
                fixes = fixes,
                activities = acts,
                steps = steps,
                activityAvailable = lastAvailable["activity"] == true,
                stepsAvailable = lastAvailable["steps"] == true,
                finished = finished,
                approximateLocation = lastAvailable["precise"] == false,
                startMs = startMs,
                stopMs = stopMs,
            )
        }
    }
}
