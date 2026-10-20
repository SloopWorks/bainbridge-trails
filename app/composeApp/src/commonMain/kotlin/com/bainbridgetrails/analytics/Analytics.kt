package com.bainbridgetrails.analytics

import com.bainbridgetrails.log.AppLog

/**
 * Product analytics facade. The spec says "analytics: none by default", so release uses [NoOpAnalytics];
 * debug builds install [LogAnalytics], which only writes to the (debug drawer) log.
 *
 * TODO(swip): SWIP instrumentation is deferred — it needs a bainbridge product registry + generated event ids.
 *  When that exists, add a SwipAnalytics implementation behind this interface (no call-site changes).
 */
interface Analytics {
    fun track(event: String, props: Map<String, String> = emptyMap())
}

object NoOpAnalytics : Analytics {
    override fun track(event: String, props: Map<String, String>) = Unit
}

/** Debug only: every event becomes a log line (tag "analytics"). */
class LogAnalytics(private val log: (String) -> Unit = { AppLog.d("analytics", it) }) : Analytics {
    override fun track(event: String, props: Map<String, String>) {
        log(if (props.isEmpty()) event else "$event ${props.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    }
}

object AnalyticsEvents {
    const val SCREEN_VIEW = "screen_view"
    const val WALK_STARTED = "walk_started"
    const val WALK_FINISHED = "walk_finished"
    const val TRAIL_COMPLETED = "trail_completed"
}
