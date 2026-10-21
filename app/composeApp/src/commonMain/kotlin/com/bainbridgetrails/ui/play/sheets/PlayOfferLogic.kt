package com.bainbridgetrails.ui.play.sheets

import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.tracking.RecordingState

/** A finished walk whose results sheet just closed: what the offer rules read of it. */
data class ClosedResults(val sessionId: String, val durationMs: Long)

/**
 * The one-time offer (product § 5.2, PF2; ux.md § 3.3). It may show after a finished walk's **results sheet closes**
 * (Done → Idle; a celebration sits over the results, so its results close after it, PF2-2), [DELAY_MS] later, once per
 * install ever: never after Play was toggled in Settings (PF2-4) or the offer was shown (dismiss = never again, PF2-3),
 * never for a discarded walk (it never reaches Done) or one shorter than [MIN_WALK_MS] (PF2-5), never while recording,
 * over a celebration or over onboarding. Pure: the shell feeds it recording states and asks [shouldShow].
 */
class PlayOfferWatcher {
    private var done: ClosedResults? = null

    /**
     * Feeds one recording state; returns the walk whose results just closed (Done → Idle), else null. Any other path
     * out of Done (a new recording) forgets it.
     */
    fun onState(state: RecordingState): ClosedResults? {
        when (state) {
            is RecordingState.Done -> {
                val s = state.summary
                done = ClosedResults(s.sessionId, (s.endedAt - s.startedAt).coerceAtLeast(0))
            }
            RecordingState.Idle -> {
                val closed = done
                done = null
                return closed
            }
            else -> done = null
        }
        return null
    }

    companion object {
        /** ux.md § 3.3: medium4 after the previous tab shows. */
        const val DELAY_MS = 400L

        /** PF2-5: walks shorter than this never trigger it. */
        const val MIN_WALK_MS = 60_000L

        /** Settings allow the offer at all (Play off, never toggled, never offered). */
        fun eligible(play: PlaySettings): Boolean = !play.enabled && !play.everToggled && !play.offerShown

        /** Whether to show the offer for [walk] now. [overBlocking]: a celebration, onboarding or the Record flow is up. */
        fun shouldShow(play: PlaySettings, walk: ClosedResults, recording: Boolean, overBlocking: Boolean): Boolean =
            eligible(play) && walk.durationMs >= MIN_WALK_MS && !recording && !overBlocking
    }
}
