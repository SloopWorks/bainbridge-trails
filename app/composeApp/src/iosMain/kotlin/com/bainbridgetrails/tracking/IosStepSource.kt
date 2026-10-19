package com.bainbridgetrails.tracking

import platform.CoreMotion.CMPedometer
import platform.Foundation.NSDate

/**
 * CMPedometer updates from the moment [start] is called; `numberOfSteps` is cumulative since that date, so it is
 * already session-relative and monotonic (no baseline bookkeeping, unlike Android's step counter).
 */
class IosStepSource : StepSource {
    private var pedometer: CMPedometer? = null

    override val available: Boolean
        get() = CMPedometer.isStepCountingAvailable() && !IosActivitySource.motionDenied(CMPedometer.authorizationStatus())

    override fun start(onSample: (StepSample) -> Unit) {
        if (pedometer != null || !available) return
        val p = CMPedometer().also { pedometer = it }
        p.startPedometerUpdatesFromDate(NSDate()) { data, error ->
            if (error == null && data != null) {
                onSample(StepSample(IosMappers.epochMs(data.endDate), data.numberOfSteps.longValue))
            }
        }
    }

    override fun stop() {
        val p = pedometer ?: return
        pedometer = null
        p.stopPedometerUpdates()
    }
}
