package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.coverage.Xy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinalizerTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()
    private val t0 = 1_700_000_000_000L

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }

    private fun seg(id: String, y: Double, trails: List<String>) =
        SegmentGeom(id, 200.0, true, trails, listOf(lonLat(Xy(0.0, y)), lonLat(Xy(200.0, y))))

    private val segs = listOf(seg("a", 0.0, listOf("A")), seg("b", 1000.0, listOf("B")))
    private val trails = listOf(TrailMeta("A", "Trail A", listOf("a"), 0.1, null), TrailMeta("B", "Trail B", listOf("b"), 0.1, null))
    private val ds = Dataset(trails, 400.0, segs)
    private val index = SegmentIndex(segs, params, proj)

    private fun tmpDir(): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-fin-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }

    /** Walk east along y from x0 to x1 at 1.5 m/s, a fix per 2 s, no steps/activity. */
    private fun record(store: SessionStore, id: String, y: Double, x0: Double, x1: Double) {
        store.begin(id, t0)
        var s = 0
        var x = x0
        while (x <= x1) {
            val (lon, lat) = proj.toLonLat(Xy(x, y))
            store.append(SessionEvent.FixEvent(t0 + s * 1000L, lon, lat, 5.0, false))
            s += 2
            x += 3.0
        }
        store.end(t0 + s * 1000L)
    }

    private fun fin(dir: String) = Finalizer(ds, index, SessionStore(dir), CoverageStore(dir))

    @Test
    fun finalizeMergesAndReportsRise() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        record(store, "s1", 0.0, 0.0, 200.0)
        val sum = Finalizer(ds, index, store, CoverageStore(dir)).finalize("s1")
        val a = sum.trails.single { it.trailId == "A" }
        assertTrue(a.after > a.before)
        assertEquals(0.0, a.before)
        assertTrue(sum.islandAfter > sum.islandBefore)
        assertTrue(sum.trails.none { it.trailId == "B" })
        assertEquals(sum.islandAfter, CoverageStore(dir).let { c ->
            com.bainbridgetrails.coverage.Progress(ds, index, params).islandFraction(c.load())
        }, 1e-9)
        assertEquals(false, sum.verified)
    }

    @Test
    fun secondSessionAccumulates() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        val f = Finalizer(ds, index, store, CoverageStore(dir))
        record(store, "s1", 0.0, 0.0, 100.0)
        val s1 = f.finalize("s1")
        assertTrue(s1.newlyCompleted.isEmpty())
        assertEquals(TrailStatus.PARTWAY, s1.trails.single().status)
        record(store, "s2", 0.0, 100.0, 200.0)
        val s2 = f.finalize("s2")
        assertEquals(listOf("A"), s2.newlyCompleted)
        assertEquals(TrailStatus.COMPLETE, s2.trails.single().status)
        assertTrue(s2.trails.single().before > 0.3)
    }

    @Test
    fun coverageStoreAtomicAndVersioned() {
        val dir = tmpDir()
        val cs = CoverageStore(dir)
        assertEquals(CoverageState.EMPTY, cs.load())
        val st = index.coverFixes(
            (0..60).map { Fix(t0 + it * 2000L, proj.toLonLat(Xy(it * 3.0, 0.0)).first, proj.toLonLat(Xy(it * 3.0, 0.0)).second, 5.0) },
        )
        cs.merge("s1", st, true)
        val text = SystemFileSystem.source(Path(dir, "coverage.json")).buffered().use { it.readString() }
        assertTrue(text.contains("\"v\":1"), text)
        assertTrue(text.contains("\"tickM\":5.0"), text)
        assertTrue(text.contains("\"s1\":true"), text)
        // simulated crash mid-write: partial temp file lying around
        SystemFileSystem.sink(Path(dir, "coverage.json.tmp")).buffered().use { it.writeString("{\"v\":1,\"sta") }
        assertEquals(st, CoverageStore(dir).load())
        cs.merge("s2", CoverageState.EMPTY, false)
        assertEquals(st, cs.load())
    }

    @Test
    fun finalizeRunsOffMainThread() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        record(store, "s1", 0.0, 0.0, 50.0)
        var seen: Any? = null
        Finalizer(ds, index, store, CoverageStore(dir), onWork = { seen = currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor] }).finalize("s1")
        assertEquals(Dispatchers.Default, seen)
    }
    @Test
    fun onlyFinishedSessionsAreMarkedFinalized() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        record(store, "done", 0.0, 0.0, 50.0)
        store.begin("open", t0) // stop-timeout path: finalised before the recorder wrote Stop
        val (lon, lat) = proj.toLonLat(Xy(60.0, 0.0))
        store.append(SessionEvent.FixEvent(t0 + 1000, lon, lat, 5.0, false))
        val f = fin(dir)
        f.finalize("done")
        f.finalize("open")
        assertEquals(setOf("done"), CoverageStore(dir).finalizedIds(), "an unfinished session is finalised again once it has its Stop")
    }

    @Test
    fun approximateLocationReportedInSummary() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        store.begin("a", t0)
        store.append(SessionEvent.SourceState(t0, "precise", false))
        store.end(t0 + 1)
        assertTrue(fin(dir).finalize("a").approximateLocation)
        record(store, "p", 0.0, 0.0, 10.0)
        assertFalse(fin(dir).finalize("p").approximateLocation)
    }

    @Test
    fun completionIsStickyAndCelebratedOnce() = runTest {
        val dir = tmpDir()
        val store = SessionStore(dir)
        record(store, "s1", 0.0, 0.0, 200.0)
        val first = Finalizer(ds, index, store, CoverageStore(dir)).finalize("s1")
        assertEquals(listOf("A"), first.newlyCompleted)
        assertEquals(setOf("A"), CoverageStore(dir).completedIds())

        // Coverage lost (e.g. reset) but the completion stays: walking A again does not celebrate it again.
        CoverageStore(dir).merge("x", CoverageState.EMPTY, verified = false, finalized = false, completed = setOf("B"))
        assertEquals(setOf("A", "B"), CoverageStore(dir).completedIds(), "union only")
        val dir2 = tmpDir()
        CoverageStore(dir2).merge("old", CoverageState.EMPTY, verified = false, completed = setOf("A"))
        val store2 = SessionStore(dir2)
        record(store2, "s2", 0.0, 0.0, 200.0)
        val again = Finalizer(ds, index, store2, CoverageStore(dir2)).finalize("s2")
        assertTrue(again.newlyCompleted.isEmpty(), "A was already complete (sticky)")
    }
}

