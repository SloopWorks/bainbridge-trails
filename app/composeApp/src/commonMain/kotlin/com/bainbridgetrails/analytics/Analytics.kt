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

/**
 * Play's analytics events (docs/play/product.md § 7.4) behind the Play gate: [track] drops every event unless Play is
 * on at that moment ([playOn], read per call), so a never-enabled or turned-off install emits no `play_*` event
 * (PF0-4, PP-2). The offer events are not emitted: the offer only ever shows while Play is off, and PF0-4 wins over
 * § 7.4's `play_offer_*` rows (an accepted offer is `play_enabled` with `source=offer`).
 */
class PlayAnalytics(private val sink: () -> Analytics, private val playOn: () -> Boolean) {
    fun track(event: String, props: Map<String, String> = emptyMap()) {
        require(event.startsWith(PREFIX)) { "not a Play event: $event" }
        if (playOn()) sink().track(event, props)
    }

    companion object {
        const val PREFIX = "play_"
        const val ENABLED = "play_enabled"
        const val DISABLED = "play_disabled"
        const val START_BALANCE = "play_start_balance"
        const val LOGBOOK_OPENED = "play_logbook_opened"
        const val QUIET_DAY = "play_quiet_day"
        const val CONTENT_OPENED = "play_content_opened"
    }
}
