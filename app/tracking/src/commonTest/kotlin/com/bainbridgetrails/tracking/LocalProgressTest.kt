package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalProgressTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()
    private val t0 = 1_700_000_000_000L
    private val day = 24 * 60 * 60_000L

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }
    private val segs = listOf(SegmentGeom("a", 200.0, true, listOf("A"), listOf(lonLat(Xy(0.0, 0.0)), lonLat(Xy(200.0, 0.0)))))
    private val ds = Dataset(listOf(TrailMeta("A", "Trail A", listOf("a"), 0.1, null)), 200.0, segs)
    private val index = SegmentIndex(segs, params, proj)

    private fun tmpDir(): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-lp-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }

    private fun record(dir: String, id: String, startMs: Long, stop: Boolean = true) = SessionStore(dir).apply {
        begin(id, startMs)
        var i = 0
        var x = 0.0
        while (x <= 200.0) {
            val (lon, lat) = proj.toLonLat(Xy(x, 0.0))
            append(SessionEvent.FixEvent(startMs + i * 2_000L, lon, lat, 5.0, false))
            i++
            x += 3.0
        }
        if (stop) end(startMs + i * 2_000L)
    }

    private fun finalizer(dir: String, now: Long = t0) =
        Finalizer(ds, index, SessionStore(dir), CoverageStore(dir), walks = WalkIndex(dir), clock = { now })

    private object NoRecorder : Recorder {
        override fun start(sessionId: String, resume: Boolean) = Unit
        override fun stop() = Unit
    }

    @Test
    fun wipeThenRelaunchHasNoProgressAndFinalizesNothing() = runTest {
        val dir = tmpDir()
        record(dir, "s1700000000000-a", t0)
        record(dir, "s1700000100000-b", t0 + 100_000)
        record(dir, "s1700000200000-c", t0 + 200_000) // stopped, never finalized
        record(dir, "s1700000300000-live", t0 + 300_000, stop = false) // the walk being recorded right now
        val f = finalizer(dir)
        f.finalize("s1700000000000-a")
        f.finalize("s1700000100000-b")
        assertTrue(CoverageStore(dir).completedIds().isNotEmpty())

        LocalProgress.wipe(dir, keepSessions = setOf("s1700000300000-live"))

        assertEquals(CoverageState.EMPTY, CoverageStore(dir).load())
        assertTrue(CoverageStore(dir).completedIds().isEmpty())
        assertTrue(CoverageStore(dir).finalizedIds().isEmpty())
        assertTrue(WalkIndex(dir).all().isEmpty())
        assertTrue(SessionStore(dir).finished().isEmpty(), "raw tracks of old walks are gone")
        assertEquals(listOf("s1700000300000-live"), SessionStore(dir).unfinished(), "the live recording is kept")

        // Relaunch: nothing is re-finalized, stats stay zero.
        val store = SessionStore(dir)
        val c = RecordingController(store, finalizer(dir), { t0 }, NoRecorder, backgroundScope)
        c.onLaunch()
        assertIs<RecordingState.Recover>(c.state.value)
        assertEquals(CoverageState.EMPTY, CoverageStore(dir).load())
        assertTrue(WalkIndex(dir).all().isEmpty())
        assertEquals(0.0, WalkIndex(dir).totalMatchedM())
    }

    @Test
    fun rawTracksArePrunedThirtyDaysAfterFinalizeAndNeverRefinalized() = runTest {
        val dir = tmpDir()
        record(dir, "s1700000000000-old", t0)
        record(dir, "s1700000100000-new", t0 + 100_000)
        record(dir, "s1700000200000-pending", t0 + 200_000) // stopped, not finalized yet
        record(dir, "s1700000300000-open", t0 + 300_000, stop = false)
        finalizer(dir, now = t0).finalize("s1700000000000-old")
        finalizer(dir, now = t0 + 20 * day).finalize("s1700000100000-new")
        val coverage = CoverageStore(dir).load()

        val pruned = LocalProgress.pruneRawTracks(dir, nowMs = t0 + 31 * day)

        assertEquals(listOf("s1700000000000-old"), pruned)
        assertEquals(listOf("s1700000100000-new", "s1700000200000-pending"), SessionStore(dir).finished())
        assertEquals(listOf("s1700000300000-open"), SessionStore(dir).unfinished())
        assertTrue("s1700000000000-old" in CoverageStore(dir).finalizedIds(), "tombstone stays")
        assertEquals(2, WalkIndex(dir).all().size, "history keeps the pruned walk")
        assertEquals(coverage, CoverageStore(dir).load())
    }

    @Test
    fun finalizedSessionWithoutWalkRecordIsKept() = runTest {
        val dir = tmpDir()
        record(dir, "s1700000000000-a", t0)
        Finalizer(ds, index, SessionStore(dir), CoverageStore(dir)).finalize("s1700000000000-a") // no walk index
        assertTrue(LocalProgress.pruneRawTracks(dir, nowMs = t0 + 365 * day).isEmpty())
        assertEquals(listOf("s1700000000000-a"), SessionStore(dir).finished())
    }
}
