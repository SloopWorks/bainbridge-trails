package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStoreTest {
    private fun tempDir(): String {
        val p = Path(SystemTemporaryDirectory, "tracking-test-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(p)
        return p.toString()
    }

    private fun fix(t: Long) = FixEvent(t, 1.5, 2.5, 3.0, false)

    private fun FixEvent(t: Long, lon: Double, lat: Double, acc: Double?, mock: Boolean) =
        SessionEvent.FixEvent(t, lon, lat, acc, mock)

    @Test
    fun roundTripsAllEvents() {
        val s = SessionStore(tempDir())
        s.begin("a", 1)
        s.append(SessionEvent.SourceState(2, "location", true))
        s.append(SessionEvent.FixEvent(3, 10.25, 20.5, null, true))
        s.append(fix(4))
        s.append(SessionEvent.Act(ActivitySample(5, ActivityKind.ON_FOOT, Confidence.HIGH)))
        s.append(SessionEvent.Steps(StepSample(6, 42)))
        s.append(SessionEvent.SourceState(7, "activity", true))
        s.append(SessionEvent.SourceState(8, "steps", true))
        s.end(9)
        val r = s.load("a")
        assertEquals("a", r.id)
        assertEquals(listOf(Fix(3, 10.25, 20.5, null, true), Fix(4, 1.5, 2.5, 3.0, false)), r.fixes)
        assertEquals(listOf(ActivitySample(5, ActivityKind.ON_FOOT, Confidence.HIGH)), r.activities)
        assertEquals(listOf(StepSample(6, 42)), r.steps)
        assertTrue(r.activityAvailable)
        assertTrue(r.stepsAvailable)
        assertTrue(r.finished)
        assertTrue(s.unfinished().isEmpty())
    }

    @Test
    fun diagEventRoundTrip() {
        val dir = tempDir()
        val s = SessionStore(dir)
        s.begin("d", 1)
        s.append(SessionEvent.Diag(300_000, 87, 140, 6.5))
        s.append(SessionEvent.Diag(600_000, null, 0, null))
        s.append(fix(700_000))
        s.end(800_000)
        val r = s.load("d")
        assertEquals(1, r.fixes.size) // Diag does not affect the recorded session
        assertTrue(r.finished)
        val text = kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(dir, "sessions", "d.jsonl")).buffered().use { it.readString() }
        assertTrue(text.contains("\"t\":\"diag\""))
    }

    @Test
    fun recoversUnfinishedSession() {
        val dir = tempDir()
        val s = SessionStore(dir)
        s.begin("u", 1)
        s.append(fix(2))
        s.append(fix(3))
        val s2 = SessionStore(dir)
        assertEquals(listOf("u"), s2.unfinished())
        val r = s2.load("u")
        assertFalse(r.finished)
        assertEquals(2, r.fixes.size)
    }

    @Test
    fun truncatedLastLineIgnored() {
        val dir = tempDir()
        val s = SessionStore(dir)
        s.begin("t", 1)
        s.append(fix(2))
        val path = Path(dir, "sessions", "t.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("{\"t\":\"fix\",\"timeMs\":3,\"lo") }
        val r = s.load("t")
        assertEquals(1, r.fixes.size)
        assertFalse(r.finished)
    }

    @Test
    fun sourceAvailabilityFromLastState() {
        val s = SessionStore(tempDir())
        s.begin("s", 1)
        assertFalse(s.load("s").activityAvailable)
        assertFalse(s.load("s").stepsAvailable)
        s.append(SessionEvent.SourceState(2, "activity", true))
        s.append(SessionEvent.SourceState(3, "steps", true))
        s.append(SessionEvent.SourceState(4, "activity", false))
        val r = s.load("s")
        assertFalse(r.activityAvailable)
        assertTrue(r.stepsAvailable)
    }

    @Test
    fun deleteRemovesFile() {
        val dir = tempDir()
        val s = SessionStore(dir)
        s.begin("d", 1)
        s.end(2)
        s.delete("d")
        assertFalse(SystemFileSystem.exists(Path(dir, "sessions", "d.jsonl")))
        assertTrue(s.unfinished().isEmpty())
    }

    @Test
    fun resumeRepairsTruncatedTailThenAppends() {
        val dir = tempDir()
        val s = SessionStore(dir)
        s.begin("r", 1)
        s.append(fix(2))
        s.append(SessionEvent.Steps(StepSample(3, 7)))
        val path = Path(dir, "sessions", "r.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("{\"t\":\"fix\",\"timeMs\":4,\"lo") }
        val s2 = SessionStore(dir)
        s2.resume("r")
        s2.append(fix(5))
        val r = s2.load("r")
        assertEquals(listOf(2L, 5L), r.fixes.map { it.timeMs })
        assertEquals(listOf(StepSample(3, 7)), r.steps)
        assertFalse(r.finished)
        s2.end(6)
        assertTrue(s2.load("r").finished)
    }

    @Test
    fun resumeRejectsFinished() {
        val s = SessionStore(tempDir())
        s.begin("f", 1)
        s.end(2)
        assertFailsWith<IllegalStateException> { s.resume("f") }
        assertFailsWith<IllegalStateException> { s.resume("missing") }
        assertFailsWith<IllegalStateException> { s.begin("f", 3) }
    }

    @Test
    fun unfinishedReadsOnlyFirstAndLastCompleteLine() {
        val dir = tempDir()
        val s = SessionStore(dir)
        // Long finished session with a corrupt line in the middle: only the head and the tail decide.
        s.begin("long", 1)
        repeat(2_000) { s.append(fix(2L + it)) }
        SystemFileSystem.sink(Path(dir, "sessions", "long.jsonl"), append = true).buffered().use { it.writeString("garbage\n") }
        s.append(fix(5_000))
        s.end(5_001)
        // Unfinished: last complete line is a fix, followed by a truncated tail.
        s.begin("open", 1)
        repeat(2_000) { s.append(fix(2L + it)) }
        SystemFileSystem.sink(Path(dir, "sessions", "open.jsonl"), append = true).buffered().use { it.writeString("{\"t\":\"stop\",\"ti") }
        // Truncated right after Start: still unfinished (recoverable).
        SessionStore(dir).begin("tiny", 1)
        // No Start: not a session.
        SystemFileSystem.sink(Path(dir, "sessions", "junk.jsonl")).buffered().use { it.writeString("x\n") }
        assertEquals(listOf("open", "tiny"), SessionStore(dir).unfinished())
    }
}
