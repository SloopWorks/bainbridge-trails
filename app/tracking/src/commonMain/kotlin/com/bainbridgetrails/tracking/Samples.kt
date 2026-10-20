package com.bainbridgetrails.tracking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ActivityKind { ON_FOOT, RUNNING, BICYCLE, VEHICLE, STILL, UNKNOWN }

enum class Confidence { LOW, MEDIUM, HIGH }

@Serializable
data class ActivitySample(val timeMs: Long, val kind: ActivityKind, val confidence: Confidence)

/** [cumulativeSteps] is monotonic within a session. */
@Serializable
data class StepSample(val timeMs: Long, val cumulativeSteps: Long)

/** One JSON object per line; class discriminator "t". */
@Serializable
sealed interface SessionEvent {
    @Serializable @SerialName("start")
    data class Start(val sessionId: String, val timeMs: Long) : SessionEvent

    @Serializable @SerialName("fix")
    data class FixEvent(
        val timeMs: Long,
        val lon: Double,
        val lat: Double,
        val accuracyM: Double?,
        val isMock: Boolean,
    ) : SessionEvent

    @Serializable @SerialName("act")
    data class Act(val sample: ActivitySample) : SessionEvent

    @Serializable @SerialName("steps")
    data class Steps(val sample: StepSample) : SessionEvent

    /**
     * [source] is "activity" | "steps" | "location" | "precise" ("precise" false = the OS granted only reduced
     * accuracy, iOS; the walk is recorded but flagged).
     */
    @Serializable @SerialName("source")
    data class SourceState(val timeMs: Long, val source: String, val available: Boolean) : SessionEvent

    /** Periodic battery/fix diagnostics (every 5 min). Ignored when building a [RecordedSession]. */
    @Serializable @SerialName("diag")
    data class Diag(val timeMs: Long, val batteryPct: Int?, val fixCount: Int, val meanAccuracyM: Double?) : SessionEvent

    @Serializable @SerialName("stop")
    data class Stop(val timeMs: Long) : SessionEvent
}
