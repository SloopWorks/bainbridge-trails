package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import kotlin.test.Test
import kotlin.test.assertEquals

class GeofencesTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }

    private fun seg(id: String, y: Double, trails: List<String>) =
        SegmentGeom(id, 100.0, true, trails, listOf(lonLat(Xy(0.0, y)), lonLat(Xy(100.0, y))))

    private val segs = listOf(seg("s1", 0.0, listOf("T1")), seg("s2", 1000.0, listOf("T2")), seg("s3", 2000.0, listOf("T3")))
    private val trails = listOf(
        TrailMeta("T1", "T1", listOf("s1"), 0.1, null),
        TrailMeta("T2", "T2", listOf("s2"), 0.1, null),
        TrailMeta("T3", "T3", listOf("s3"), 0.1, null),
    )
    private val ds = Dataset(trails, 300.0, segs)
    private val index = SegmentIndex(segs, params, proj)
    private val progress = Progress(ds, index, params)

    private fun walk(vararg ys: Double) = index.cover(ys.map { y -> listOf(Xy(0.0, y), Xy(100.0, y)) })

    private fun th(id: String, x: Double, y: Double, trailIds: List<String> = emptyList()): Trailhead {
        val (lon, lat) = proj.toLonLat(Xy(x, y))
        return Trailhead(id, lon, lat, "Head $id", trailIds)
    }

    @Test
    fun nearestPicksClosest() {
        val all = listOf(th("far", 5000.0, 0.0), th("near", 100.0, 0.0), th("mid", 0.0, 1000.0), th("mid2", -2000.0, 0.0))
        val (lon, lat) = proj.toLonLat(Xy(0.0, 0.0))
        assertEquals(listOf("near", "mid", "mid2"), Geofences.nearest(all, lon, lat, 3).map { it.id })
    }

    @Test
    fun maxRespected() {
        val all = (0 until 50).map { th("t$it", it * 100.0, 0.0) }
        val (lon, lat) = proj.toLonLat(Xy(0.0, 0.0))
        assertEquals(Geofences.IOS_MAX, Geofences.nearest(all, lon, lat, Geofences.IOS_MAX).size)
        assertEquals(50, Geofences.nearest(all, lon, lat, Geofences.ANDROID_MAX).size)
        assertEquals(emptyList(), Geofences.nearest(all, lon, lat, 0))
    }

    @Test
    fun arrivalTextCounts() {
        val t = th("a", 0.0, 0.0, listOf("T1", "T2", "T3"))
        assertEquals(
            "You're at Head a — 1 of 3 trails here complete",
            Geofences.arrivalText(t, walk(0.0), progress),
        )
        assertEquals(
            "You're at Head a — 0 of 3 trails here complete",
            Geofences.arrivalText(t, CoverageState.EMPTY, progress),
        )
    }

    @Test
    fun arrivalTextAllComplete() {
        val t = th("a", 0.0, 0.0, listOf("T1", "T2"))
        assertEquals(
            "You're at Head a — 2 of 2 trails here complete",
            Geofences.arrivalText(t, walk(0.0, 1000.0), progress),
        )
    }

    @Test
    fun parseReadsDistSchema() {
        val text = """{"version":"v1","trailheads":[{"id":"th-1","lon":-122.5,"lat":47.6,"name":"X","trail_ids":["t-a"],"source":"road_access"}]}"""
        assertEquals(listOf(Trailhead("th-1", -122.5, 47.6, "X", listOf("t-a"))), Geofences.parse(text))
    }

    @Test
    fun shouldRegisterNeedsAllThree() {
        assertEquals(true, Geofences.shouldRegister(declared = true, optedIn = true, backgroundGranted = true))
        assertEquals(false, Geofences.shouldRegister(declared = true, optedIn = false, backgroundGranted = true))
        assertEquals(false, Geofences.shouldRegister(declared = true, optedIn = true, backgroundGranted = false))
        assertEquals(false, Geofences.shouldRegister(declared = false, optedIn = true, backgroundGranted = true))
    }
}
