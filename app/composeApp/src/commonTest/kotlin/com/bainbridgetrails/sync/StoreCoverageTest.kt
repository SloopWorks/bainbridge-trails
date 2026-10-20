package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.WalkIndex
import com.bainbridgetrails.tracking.WalkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StoreCoverageTest {
    @Test
    fun snapshotReadsWalksAndCoverageUnderTheWriteLock() = runTest {
        val dir = DataFixtures.tmpDir("storecov")
        val state = CoverageState.decode("""{"seg-000001":"Aw=="}""")
        CoverageStore(dir).merge("s1", state, verified = true)
        WalkIndex(dir).upsert(WalkRecord("s1", 0, 90_000, 0.0, 0.0, verified = true))

        CoverageStore.writeLock.lock()
        val snap = async(Dispatchers.Default) { StoreCoverage(dir).snapshot() }
        withContext(Dispatchers.Default) { delay(200) }
        assertFalse(snap.isCompleted, "a finalize in progress holds the lock: sync waits for coverage + walk together")
        CoverageStore.writeLock.unlock()

        val s = snap.await()
        assertEquals(state, s.state)
        assertEquals(listOf(LocalWalk("s1", 90)), s.walks)
    }
}
