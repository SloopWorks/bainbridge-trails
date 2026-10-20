package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalkIndexTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()
    private val t0 = 1_700_000_000_000L

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }
    private fun seg(id: String, y: Double, trails: List<String>) =
        SegmentGeom(id, 200.0, true, trails, listOf(lonLat(Xy(0.0, y)), lonLat(Xy(200.0, y))))

    private val segs = listOf(seg("a", 0.0, listOf("A")), seg("b", 1000.0, listOf("B")))
    private val ds = Dataset(listOf(TrailMeta("A", "Trail A", listOf("a"), 0.1, null), TrailMeta("B", "Trail B", listOf("b"), 0.1, null)), 400.0, segs)
    private val index = SegmentIndex(segs, params, proj)

    private fun tmpDir(): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-walks-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }

    private fun record(dir: String, id: String, startMs: Long, y: Double, x1: Double, stop: Boolean = true) = SessionStore(dir).apply {
        begin(id, startMs)
        var i = 0
        var x = 0.0
        while (x <= x1) {
            val (lon, lat) = proj.toLonLat(Xy(x, y))
            append(SessionEvent.FixEvent(startMs + i * 2_000L, lon, lat, 5.0, false))
            i++
            x += 3.0
        }
        if (stop) end(startMs + i * 2_000L)
    }

    @Test
    fun emptyWhenMissing() {
        assertTrue(WalkIndex(tmpDir()).all().isEmpty())
    }

    @Test
    fun finalizeRecordsWalkWithoutCoordinates() = runTest {
        val dir = tmpDir()
        val store = record(dir, "s1", t0, 0.0, 150.0)
        val walks = WalkIndex(dir)
        Finalizer(ds, index, store, CoverageStore(dir), walks = walks).finalize("s1")

        val w = walks.all().single()
        assertEquals("s1", w.sessionId)
        assertEquals(t0, w.startedAt)
        assertEquals(t0 + 51 * 2_000L, w.endedAt) // Stop written 2 s after the 51st fix
        assertEquals(150.0, w.distanceM, 1.0)
        assertTrue(w.matchedM in 130.0..160.0, "matched ${w.matchedM}")
        assertEquals(listOf("A"), w.trails.map { it.trailId })
        assertTrue(w.islandAfter > w.islandBefore)
        assertFalse(w.summaryShown)
        val text = SystemFileSystem.source(Path(dir, "walks.json")).buffered().use { it.readString() }
        assertFalse("lat" in text || "lon" in text, "no coordinates in the walk index: $text")
    }

    @Test
    fun refinalizingUpdatesInPlaceNewestFirst() = runTest {
        val dir = tmpDir()
        val walks = WalkIndex(dir)
        val f = { s: SessionStore -> Finalizer(ds, index, s, CoverageStore(dir), walks = walks) }
        f(record(dir, "s1", t0, 0.0, 60.0)).finalize("s1")
        f(record(dir, "s2", t0 + 1_000_000, 1000.0, 90.0)).finalize("s2")
        f(SessionStore(dir)).finalize("s1")
        assertEquals(listOf("s2", "s1"), walks.all().map { it.sessionId })
        assertEquals(listOf("B"), walks.all().first().trails.map { it.trailId })
    }

    @Test
    fun summaryShownSurvivesRefinalize() = runTest {
        val dir = tmpDir()
        val walks = WalkIndex(dir)
        val store = record(dir, "s1", t0, 0.0, 60.0)
        val f = Finalizer(ds, index, store, CoverageStore(dir), walks = walks)
        f.finalize("s1")
        f.markShown("s1")
        f.finalize("s1")
        assertEquals(setOf("s1"), f.shownIds())
    }

    @Test
    fun summaryCarriesTimesAndDistances() = runTest {
        val dir = tmpDir()
        val store = record(dir, "s1", t0, 0.0, 150.0)
        val s = Finalizer(ds, index, store, CoverageStore(dir), walks = WalkIndex(dir)).finalize("s1")
        assertEquals(t0, s.startedAt)
        assertEquals(t0 + 51 * 2_000L, s.endedAt)
        assertEquals(150.0, s.distanceM, 1.0)
        assertEquals(WalkIndex(dir).totalMatchedM(), s.matchedM, 1e-9)
    }

    @Test
    fun gapsDoNotAddDistance() {
        val fixes = listOf(
            com.bainbridgetrails.coverage.Fix(0, proj.toLonLat(Xy(0.0, 0.0)).first, proj.toLonLat(Xy(0.0, 0.0)).second, 5.0, false),
            com.bainbridgetrails.coverage.Fix(2_000, proj.toLonLat(Xy(3.0, 0.0)).first, proj.toLonLat(Xy(3.0, 0.0)).second, 5.0, false),
            // 10 minutes later, 500 m away: a GPS gap, not walked distance.
            com.bainbridgetrails.coverage.Fix(602_000, proj.toLonLat(Xy(503.0, 0.0)).first, proj.toLonLat(Xy(503.0, 0.0)).second, 5.0, false),
        )
        assertEquals(3.0, WalkIndex.distanceM(fixes, proj), 0.1)
    }
}
