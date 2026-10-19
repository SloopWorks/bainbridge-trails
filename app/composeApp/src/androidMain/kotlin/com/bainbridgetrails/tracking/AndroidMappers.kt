package com.bainbridgetrails.tracking

import android.annotation.SuppressLint
import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.bainbridgetrails.coverage.Fix
import com.google.android.gms.location.DetectedActivity

/** Wall + elapsed-realtime clocks, injectable so time conversion is testable without Robolectric. */
interface DeviceClock {
    fun currentTimeMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

object SystemDeviceClock : DeviceClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** The [Location] fields the mapper needs; [isMock] is only called on API 31+. */
interface LocationFields {
    val timeMs: Long
    val lon: Double
    val lat: Double
    val hasAccuracy: Boolean
    val accuracyM: Float
    fun isMock(): Boolean
    fun isFromMockProvider(): Boolean
}

class LocationFieldsAdapter(private val location: Location) : LocationFields {
    override val timeMs: Long get() = location.time
    override val lon: Double get() = location.longitude
    override val lat: Double get() = location.latitude
    override val hasAccuracy: Boolean get() = location.hasAccuracy()
    override val accuracyM: Float get() = location.accuracy

    @SuppressLint("NewApi") // AndroidMappers.toFix only calls this when SDK_INT >= S.
    override fun isMock(): Boolean = location.isMock

    @Suppress("DEPRECATION")
    override fun isFromMockProvider(): Boolean = location.isFromMockProvider
}

object AndroidMappers {
    /**
     * Ruling R8: a fix taken before the session (re)started is a cached position from before the walk, not
     * part of it, and would add a jump to the track and distance.
     */
    fun isSessionFix(fixTimeMs: Long, sessionStartMs: Long): Boolean = fixTimeMs >= sessionStartMs

    fun activityKind(type: Int): ActivityKind = when (type) {
        DetectedActivity.IN_VEHICLE -> ActivityKind.VEHICLE
        DetectedActivity.ON_BICYCLE -> ActivityKind.BICYCLE
        DetectedActivity.ON_FOOT, DetectedActivity.WALKING -> ActivityKind.ON_FOOT
        DetectedActivity.RUNNING -> ActivityKind.RUNNING
        DetectedActivity.STILL -> ActivityKind.STILL
        else -> ActivityKind.UNKNOWN // TILTING, UNKNOWN, anything new
    }

    fun confidence(percent: Int): Confidence = when {
        percent >= 75 -> Confidence.HIGH
        percent >= 50 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /** Converts an elapsed-realtime event timestamp (ns since boot) to epoch ms. The one place this happens. */
    fun elapsedToEpochMs(eventElapsedNanos: Long, clock: DeviceClock = SystemDeviceClock): Long =
        clock.currentTimeMillis() - (clock.elapsedRealtimeNanos() - eventElapsedNanos) / 1_000_000

    fun toFix(loc: LocationFields, sdkInt: Int = Build.VERSION.SDK_INT): Fix = Fix(
        timeMs = loc.timeMs,
        lon = loc.lon,
        lat = loc.lat,
        accuracyM = if (loc.hasAccuracy) loc.accuracyM.toDouble() else null,
        isMock = if (sdkInt >= Build.VERSION_CODES.S) loc.isMock() else loc.isFromMockProvider(),
    )
}

/**
 * Converts TYPE_STEP_COUNTER (cumulative since boot) to session-relative steps.
 * The first reading is the baseline. If the counter drops (reboot), steps so far are kept and the
 * new value becomes the baseline, so the output stays monotonic.
 */
class StepBaseline {
    private var baseline: Long = -1
    private var last: Long = -1
    private var carried: Long = 0

    fun relative(counter: Float): Long {
        require(counter >= 0f) { "negative step counter" }
        val raw = counter.toLong()
        if (baseline < 0) {
            baseline = raw
        } else if (raw < last) {
            carried += last - baseline
            baseline = raw
        }
        last = raw
        return carried + raw - baseline
    }
}

/** Last location state the recorder wrote. */
enum class LocationState {
    AVAILABLE, NO_FIX, OFF;

    companion object {
        fun after(available: Boolean, systemEnabled: Boolean): LocationState = when {
            !systemEnabled -> OFF
            available -> AVAILABLE
            else -> NO_FIX
        }
    }
}

enum class LocationAvailabilityAction { NONE, MARK_UNAVAILABLE, LOST, MARK_AVAILABLE }

/**
 * Ruling R5. System location off -> LOST (SourceState false + RecordingBus.emitLost). Fused provider reports no
 * fix while location is on -> MARK_UNAVAILABLE only (transient: indoors, under trees; the UI's waiting chip covers
 * it, so never LOST). Recovery -> MARK_AVAILABLE. [current] de-duplicates: no action when the state is unchanged.
 */
fun locationAvailabilityAction(available: Boolean, systemEnabled: Boolean, current: LocationState): LocationAvailabilityAction {
    val next = LocationState.after(available, systemEnabled)
    if (next == current) return LocationAvailabilityAction.NONE
    return when (next) {
        LocationState.OFF -> LocationAvailabilityAction.LOST
        LocationState.NO_FIX -> LocationAvailabilityAction.MARK_UNAVAILABLE
        LocationState.AVAILABLE -> LocationAvailabilityAction.MARK_AVAILABLE
    }
}
