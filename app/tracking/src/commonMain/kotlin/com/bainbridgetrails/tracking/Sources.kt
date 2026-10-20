package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix

/** Platform location feed. [onUnavailable] fires instead of fixes when permission or the provider is missing. */
interface LocationSource {
    fun start(onFix: (Fix) -> Unit, onUnavailable: (reason: String) -> Unit)
    fun stop()
}

interface ActivitySource {
    val available: Boolean
    fun start(onSample: (ActivitySample) -> Unit)
    fun stop()
    suspend fun history(fromMs: Long, toMs: Long): List<ActivitySample> = emptyList()
}

/** [StepSample.cumulativeSteps] is session-relative. */
interface StepSource {
    val available: Boolean
    fun start(onSample: (StepSample) -> Unit)
    fun stop()
}
