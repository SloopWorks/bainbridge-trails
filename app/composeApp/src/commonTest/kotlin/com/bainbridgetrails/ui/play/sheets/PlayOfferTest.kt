package com.bainbridgetrails.ui.play.sheets

import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.tracking.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PF2 (product § 5.2, ux.md § 3.3): the one-time offer after the first finished walk's results close. */
class PlayOfferTest {
    private fun summary(id: String, minutes: Long, completed: List<String> = emptyList()) = SessionSummary(
        sessionId = id, islandBefore = 0.1, islandAfter = 0.2, trails = emptyList(), verified = true, droppedVehicle = 0,
        droppedSteps = 0, newlyCompleted = completed, startedAt = 1_000L, endedAt = 1_000L + minutes * 60_000L,
    )

    private val fresh = PlaySettings()

    /** Feeds a walk's states; returns what the watcher reports when its results close. */
    private fun PlayOfferWatcher.walk(id: String, minutes: Long, completed: List<String> = emptyList()): ClosedResults? {
        assertNull(onState(RecordingState.Recording(id, 0, 3, null, 100.0, false, true, true)))
        assertNull(onState(RecordingState.Finalizing(id)))
        assertNull(onState(RecordingState.Done(summary(id, minutes, completed))), "nothing while the results show")
        return onState(RecordingState.Idle)
    }

    @Test
    fun showsOnceAfterFirstResultsClose() {
        val w = PlayOfferWatcher()
        val closed = assertNotNull(w.walk("s1", minutes = 12))
        assertEquals("s1", closed.sessionId)
        assertTrue(PlayOfferWatcher.shouldShow(fresh, closed, recording = false, overBlocking = false))
        assertEquals(400L, PlayOfferWatcher.DELAY_MS)
        // Shown: the shell persists offerShown, so the next walk never shows it (once per install).
        val after = fresh.copy(offerShown = true)
        assertFalse(PlayOfferWatcher.shouldShow(after, assertNotNull(w.walk("s2", minutes = 20)), recording = false, overBlocking = false))
    }

    @Test
    fun afterCelebrationsResults() {
        val w = PlayOfferWatcher()
        assertNull(w.onState(RecordingState.Done(summary("s1", 30, completed = listOf("trail-a")))))
        // The celebration sits over the results: still Done, nothing offered; never over a celebration either.
        assertNull(w.onState(RecordingState.Done(summary("s1", 30, completed = listOf("trail-a")))))
        val closed = assertNotNull(w.onState(RecordingState.Idle), "the results sheet that follows the celebration closed")
        assertFalse(PlayOfferWatcher.shouldShow(fresh, closed, recording = false, overBlocking = true), "never over a celebration")
        assertTrue(PlayOfferWatcher.shouldShow(fresh, closed, recording = false, overBlocking = false))
    }

    @Test
    fun neverAgainAfterDismiss() {
        val dismissed = fresh.copy(offerShown = true)
        val w = PlayOfferWatcher()
        repeat(3) { i -> assertFalse(PlayOfferWatcher.shouldShow(dismissed, assertNotNull(w.walk("s$i", 15)), recording = false, overBlocking = false)) }
    }

    @Test
    fun neverIfToggledBefore() {
        val w = PlayOfferWatcher()
        val closed = assertNotNull(w.walk("s1", 15))
        assertFalse(PlayOfferWatcher.shouldShow(fresh.copy(everToggled = true), closed, recording = false, overBlocking = false), "toggled on then off")
        assertFalse(PlayOfferWatcher.shouldShow(fresh.copy(enabled = true, everToggled = true), closed, recording = false, overBlocking = false), "on")
    }

    @Test
    fun notForDiscardedOrUnderOneMinute() {
        val w = PlayOfferWatcher()
        // Discarded recovery: Recover → Finalizing → Idle, never Done.
        assertNull(w.onState(RecordingState.Recover("s0")))
        assertNull(w.onState(RecordingState.Finalizing("s0")))
        assertNull(w.onState(RecordingState.Idle), "a discarded walk never offers")
        val short = assertNotNull(w.walk("s1", minutes = 0))
        assertFalse(PlayOfferWatcher.shouldShow(fresh, short, recording = false, overBlocking = false), "< 1 min")
        // Still eligible for the next real walk; never while recording.
        val real = assertNotNull(w.walk("s2", minutes = 5))
        assertFalse(PlayOfferWatcher.shouldShow(fresh, real, recording = true, overBlocking = false))
        assertTrue(PlayOfferWatcher.shouldShow(fresh, real, recording = false, overBlocking = false))
    }

    @Test
    fun shellStateSurvivesSavedStateRoundTrip() {
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        fun roundTrip(s: PlayShellState): PlayShellState {
            val saved = assertNotNull(with(PlayShellState.Saver) { scope.save(s) })
            return assertNotNull(PlayShellState.Saver.restore(saved))
        }
        val offer = roundTrip(PlayShellState(showOffer = true))
        assertTrue(offer.showOffer, "the once-per-install offer survives rotation / process death")
        assertFalse(offer.wake)
        assertNull(offer.pendingBalance)
        val waking = roundTrip(PlayShellState(wake = true, pendingBalance = 120))
        assertTrue(waking.wake)
        assertEquals(120, waking.pendingBalance)
        assertEquals(0, roundTrip(PlayShellState(wake = true, pendingBalance = 0)).pendingBalance)
    }
}
