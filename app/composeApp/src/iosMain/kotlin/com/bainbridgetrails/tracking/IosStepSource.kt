package com.bainbridgetrails.tracking

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.coroutines.resume

/**
 * CMPedometer updates from the moment [start] is called; `numberOfSteps` is cumulative since that date, so it is
 * already session-relative and monotonic (no baseline bookkeeping, unlike Android's step counter).
 */
class IosStepSource : StepSource {
    private var pedometer: CMPedometer? = null
    /** Retained for [stepsBetween] (a released CMPedometer may never call its handler); works after [stop] too. */
    private val queryPedometer by lazy { CMPedometer() }

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

    /**
     * Steps between two epoch-ms instants from the pedometer's own history (authoritative across suspension, when
     * live updates are withheld); null on error. Used for the 30 s heartbeat and at Stop.
     */
    suspend fun stepsBetween(fromMs: Long, toMs: Long): Long? = suspendCancellableCoroutine { cont ->
        queryPedometer.queryPedometerDataFromDate(
            NSDate.dateWithTimeIntervalSince1970(fromMs / 1000.0),
            toDate = NSDate.dateWithTimeIntervalSince1970(toMs / 1000.0),
        ) { data, error ->
            if (cont.isActive) cont.resume(if (error == null) data?.numberOfSteps?.longValue else null)
        }
    }

    override fun stop() {
        val p = pedometer ?: return
        pedometer = null
        p.stopPedometerUpdates()
    }
}
