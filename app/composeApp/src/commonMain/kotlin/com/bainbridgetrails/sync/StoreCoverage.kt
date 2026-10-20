package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.WalkIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LocalCoverage] over tracking's `coverage.json` + `walks.json`, serialized with the Finalizer through
 * [CoverageStore.writeLock]: the Finalizer writes a walk's record and merges its bits under that lock, so a snapshot
 * never holds a walk's bits without the walk (which the server would refuse as implausible).
 */
class StoreCoverage(private val trackingDir: String) : LocalCoverage {
    override suspend fun snapshot(): LocalSnapshot = withContext(Dispatchers.IO) {
        CoverageStore.writeLock.withLock {
            LocalSnapshot(
                state = CoverageStore(trackingDir).load(),
                walks = WalkIndex(trackingDir).all().map { LocalWalk(it.sessionId, it.durationMs / 1000) },
            )
        }
    }

    override suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        CoverageStore.writeLock.withLock { CoverageStore(trackingDir).mergeRemote(state, completed) }
    }
}
