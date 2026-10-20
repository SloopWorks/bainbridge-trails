package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageState
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageStoreMergeRemoteTest {
    private fun tmpDir(): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-cov-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }

    @Test
    fun unionsBitsAndCompletedKeepingSessionBookkeeping() {
        val store = CoverageStore(tmpDir())
        store.merge("s1", CoverageState.decode("""{"seg-000001":"Aw=="}"""), verified = true, completed = setOf("t-a"))

        val changed = store.mergeRemote(CoverageState.decode("""{"seg-000001":"BA==","seg-000002":"AQ=="}"""), setOf("t-b"))

        assertTrue(changed)
        assertEquals(CoverageState.decode("""{"seg-000001":"Bw==","seg-000002":"AQ=="}"""), store.load())
        assertEquals(setOf("t-a", "t-b"), store.completedIds())
        assertEquals(setOf("s1"), store.finalizedIds())
    }

    @Test
    fun neverRemovesAndReportsNoChange() {
        val store = CoverageStore(tmpDir())
        store.merge("s1", CoverageState.decode("""{"seg-000001":"Bw=="}"""), verified = true, completed = setOf("t-a"))

        assertFalse(store.mergeRemote(CoverageState.decode("""{"seg-000001":"AQ=="}"""), setOf("t-a")))
        assertFalse(store.mergeRemote(CoverageState.EMPTY, emptySet()))
        assertEquals(CoverageState.decode("""{"seg-000001":"Bw=="}"""), store.load())
    }
}
