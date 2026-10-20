package com.bainbridgetrails.tracking

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.CoreMotion.CMMotionActivity
import platform.CoreMotion.CMMotionActivityManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.coroutines.resume

/**
 * CMMotionActivityManager live updates, delivered on the main queue (the recorder keeps its bookkeeping there).
 * iOS stops delivering them while the app is suspended; [history] (queried at Stop) fills those gaps.
 */
class IosActivitySource : ActivitySource {
    private val manager = CMMotionActivityManager()
    private var running = false

    override val available: Boolean
        get() = CMMotionActivityManager.isActivityAvailable() && !motionDenied(CMMotionActivityManager.authorizationStatus())

    override fun start(onSample: (ActivitySample) -> Unit) {
        if (running || !available) return
        running = true
        manager.startActivityUpdatesToQueue(NSOperationQueue.mainQueue) { activity ->
            if (running && activity != null) onSample(activity.toSample())
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        manager.stopActivityUpdates()
    }

    /** Activity transitions recorded by the motion coprocessor in [fromMs, toMs]; empty when unavailable or on error. */
    override suspend fun history(fromMs: Long, toMs: Long): List<ActivitySample> {
        if (!available || toMs <= fromMs) return emptyList()
        return suspendCancellableCoroutine { cont ->
            manager.queryActivityStartingFromDate(
                NSDate.dateWithTimeIntervalSince1970(fromMs / 1000.0),
                toDate = NSDate.dateWithTimeIntervalSince1970(toMs / 1000.0),
                toQueue = NSOperationQueue.mainQueue,
            ) { activities, error ->
                val samples = if (error != null) emptyList() else activities.orEmpty().filterIsInstance<CMMotionActivity>().map { it.toSample() }
                if (cont.isActive) cont.resume(samples)
            }
        }
    }

    companion object {
        fun motionDenied(status: Long): Boolean = status == CMAuthorizationStatusDenied || status == CMAuthorizationStatusRestricted

        fun CMMotionActivity.toSample() = ActivitySample(
            timeMs = IosMappers.epochMs(startDate),
            kind = IosMappers.activityKind(automotive, cycling, running, walking, stationary),
            confidence = IosMappers.confidence(confidence),
        )
    }
}
