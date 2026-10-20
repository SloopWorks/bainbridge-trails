package com.bainbridgetrails.data

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.DatasetBinary
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.tracking.WalkRecord
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoriesTest {
    private fun TestScope.datasets(fail: Boolean = false): DatasetRepository {
        val d = StandardTestDispatcher(testScheduler)
        return DatasetRepository(backgroundScope, sources { fail }, compute = d)
    }

    private fun sources(bin: ByteArray? = null, fail: () -> Boolean = { false }) = DatasetSources(
        trailsJson = { if (fail()) error("boom") else DataFixtures.TRAILS.encodeToByteArray() },
        segmentsJson = { jsonReads++; DataFixtures.SEGMENTS },
        datasetBin = { bin },
    )

    private var jsonReads = 0

    private val jsonDataset get() = Dataset.parse(DataFixtures.TRAILS, DataFixtures.SEGMENTS)

    private fun fixtureBin(trailsJson: String = DataFixtures.TRAILS): ByteArray =
        DatasetBinary.encode(jsonDataset, DatasetBinary.hash(trailsJson.encodeToByteArray()))

    @Test
    fun datasetPrefersTheBinary() = runTest {
        val repo = DatasetRepository(backgroundScope, sources(fixtureBin()), compute = StandardTestDispatcher(testScheduler))
        val data = repo.await()
        assertEquals("bin", data.format)
        assertEquals(0, jsonReads, "JSON not read when the binary is usable")
        assertEquals(jsonDataset.trails, data.dataset.trails)
        assertEquals(jsonDataset.segments, data.dataset.segments)
        assertEquals(jsonDataset.version, data.dataset.version)
    }

    @Test
    fun datasetFallsBackToJsonOnStaleOrCorruptBinary() = runTest {
        val stale = fixtureBin(trailsJson = DataFixtures.TRAILS.replace("t1", "t0"))
        for (bad in listOf(stale, fixtureBin().copyOf(60), byteArrayOf(1, 2, 3))) {
            jsonReads = 0
            val repo = DatasetRepository(backgroundScope, sources(bad), compute = StandardTestDispatcher(testScheduler))
            val data = repo.await()
            assertEquals("json", data.format)
            assertEquals(1, jsonReads)
            assertEquals(2, data.dataset.trails.size)
        }
    }

    @Test
    fun datasetParsesOnceAndExposesReady() = runTest {
        val repo = datasets()
        assertIs<Loadable.Loading>(repo.state.value)
        repo.load()
        repo.load()
        val data = repo.await()
        repo.load()
        assertEquals(1, repo.parseCount)
        assertEquals("t1", data.dataset.version)
        assertEquals(2, data.dataset.trails.size)
        assertTrue(data.parseMs >= 0)
        assertIs<Loadable.Ready<TrailData>>(repo.state.value)
    }

    @Test
    fun datasetFailureIsRetryable() = runTest {
        var fail = true
        val repo = DatasetRepository(backgroundScope, sources { fail }, compute = StandardTestDispatcher(testScheduler))
        repo.load()
        runCurrent() // backgroundScope work is not covered by advanceUntilIdle
        assertEquals(Loadable.Failed("boom"), repo.state.value)
        fail = false
        repo.load()
        runCurrent() // backgroundScope work is not covered by advanceUntilIdle
        assertIs<Loadable.Ready<TrailData>>(repo.state.value)
        assertEquals(2, repo.parseCount)
    }

    @Test
    fun progressIsMemoizedPerCoverageState() = runTest {
        val data = datasets()
        val trailData = data.await()
        var coverage = CoverageState.EMPTY
        var sticky = emptySet<String>()
        val d = StandardTestDispatcher(testScheduler)
        val repo = ProgressRepository(backgroundScope, data, { StoredCoverage(coverage, sticky) }, io = d, compute = d)
        assertNull(repo.snapshot.value)

        val empty = repo.refreshNow()
        assertEquals(0.0, empty.islandFraction)
        assertEquals(2, empty.trailCount)
        repeat(10) { repo.refreshNow() }
        assertEquals(1, repo.computeCount, "unchanged coverage: no recompute")

        // Walk trail A end to end: a new state (equal by value to nothing before) recomputes once.
        val fixes = (0..66).map { i -> Fix(i * 2_000L, -122.535 + i * 0.00004, 47.645, 5.0, false) }
        coverage = trailData.index.coverFixes(fixes)
        val after = repo.refreshNow()
        repo.refreshNow()
        assertEquals(2, repo.computeCount)
        assertEquals(TrailStatus.COMPLETE, after.trail("A")?.status)
        assertEquals(1, after.trailsCompleted)
        assertTrue(after.islandFraction in 0.45..0.5, "island ${after.islandFraction}")
        assertEquals(after, repo.snapshot.value)

        // A new sticky completion (no coverage change) also recomputes: B counts as completed.
        sticky = setOf("B")
        assertEquals(2, repo.refreshNow().trailsCompleted)
        assertEquals(3, repo.computeCount)
    }

    @Test
    fun walkHistorySumsMatchedMetres() = runTest {
        val walks = listOf(
            WalkRecord("s2", 2_000, 3_000, 500.0, 120.0, verified = true),
            WalkRecord("s1", 1_000, 1_500, 300.0, 80.0, verified = false),
        )
        val repo = WalkHistoryRepository(backgroundScope, { walks }, io = StandardTestDispatcher(testScheduler))
        assertNull(repo.history.value)
        val h = repo.refreshNow()
        assertEquals(200.0, h.trailMetresWalked)
        assertEquals(listOf("s2", "s1"), repo.history.value?.walks?.map { it.sessionId })
    }

    @Test
    fun settingsPersistAcrossInstances() = runTest {
        val path = DataFixtures.tmpDir("settings") + "/settings.json"
        val d = StandardTestDispatcher(testScheduler)
        val first = SettingsRepository(path, backgroundScope, io = d)
        assertEquals(AppSettings(), first.await())
        first.updateNow { it.copy(onboardingDone = true, units = Units.KILOMETRES, mapStyle = MapStyle.MINIMAL_LIGHT, matchDark = false) }

        val second = SettingsRepository(path, backgroundScope, io = d)
        val s = second.await()
        assertTrue(s.onboardingDone)
        assertEquals(Units.KILOMETRES, s.units)
        assertEquals(MapStyle.MINIMAL_LIGHT, s.effectiveMapStyle(systemDark = true), "match dark off")
        assertEquals(MapStyle.DARK, s.copy(matchDark = true).effectiveMapStyle(systemDark = true))
    }

    @Test
    fun corruptSettingsFallBackToDefaults() = runTest {
        val path = DataFixtures.tmpDir("settings") + "/settings.json"
        kotlinx.io.files.SystemFileSystem.sink(kotlinx.io.files.Path(path)).buffered().use {
            it.write("{not json".encodeToByteArray())
        }
        val repo = SettingsRepository(path, backgroundScope, io = StandardTestDispatcher(testScheduler))
        assertEquals(AppSettings(), repo.await())
    }
}
