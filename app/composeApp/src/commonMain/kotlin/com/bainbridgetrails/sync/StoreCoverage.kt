package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.tracking.CoverageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [LocalCoverage] over tracking's `coverage.json`, serialized with the Finalizer through [CoverageStore.writeLock]. */
class StoreCoverage(private val trackingDir: String) : LocalCoverage {
    override suspend fun load(): CoverageState = withContext(Dispatchers.IO) { CoverageStore(trackingDir).load() }

    override suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        CoverageStore.writeLock.withLock { CoverageStore(trackingDir).mergeRemote(state, completed) }
    }
}
