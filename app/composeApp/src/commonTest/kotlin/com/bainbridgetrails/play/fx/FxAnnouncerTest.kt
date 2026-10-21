package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The announcer's live-region text over time (review 2026-10-20 P1 a11y): the node is always there, says "" when
 * nothing is current, clears [FxAnnouncer.CLEAR_AFTER_MS] after a line is set and when the host goes inactive, and a
 * repeat of the same line (after the dedupe window) is a new change ("" → text), so TalkBack speaks it again.
 */
class FxAnnouncerTest {
    private var now = 0L
    private val announcer = FxAnnouncer { now }.also { it.active = true }

    private fun text() = liveRegionText(announcer.current)

    /** Advance to [ms], pumping as the live-region loop would (at every requested wake-up up to [ms]). */
    private fun advanceTo(ms: Long) {
        while (true) {
            val wait = announcer.pump() ?: break
            if (now + wait > ms) break
            now += wait.coerceAtLeast(1)
        }
        now = ms
        announcer.pump()
    }

    @Test
    fun `empty node before anything is said`() {
        assertEquals("", text())
        assertNull(announcer.pump())
    }

    @Test
    fun `line clears after 5 s`() {
        announcer.say("Treasure collected")
        assertEquals("Treasure collected", text())
        advanceTo(FxAnnouncer.CLEAR_AFTER_MS - 1)
        assertEquals("Treasure collected", text())
        advanceTo(FxAnnouncer.CLEAR_AFTER_MS)
        assertEquals("", text())
        assertNull(announcer.pump())
    }

    @Test
    fun `polite lines queue on the 4 s gap, assertive speaks at once`() {
        announcer.say("one")
        announcer.say("two")
        assertEquals("one", text())
        advanceTo(3_999)
        assertEquals("one", text())
        advanceTo(4_000)
        assertEquals("two", text())
        announcer.say("now", FxPoliteness.ASSERTIVE)
        assertEquals("now", text())
        assertEquals(FxPoliteness.ASSERTIVE, announcer.current?.politeness)
        advanceTo(4_000 + FxAnnouncer.CLEAR_AFTER_MS)
        assertEquals("", text())
    }

    @Test
    fun `inactive clears the node and drops the queue`() {
        announcer.say("one")
        announcer.say("two")
        announcer.active = false
        assertEquals("", text())
        assertNull(announcer.pump())
        announcer.say("three")
        assertEquals("", text()) // nothing is announced from the background
        announcer.active = true
        advanceTo(10_000)
        assertEquals("", text())
    }

    @Test
    fun `a repeat after the dedupe window is a new change`() {
        val seen = mutableListOf(text())
        fun record() { if (text() != seen.last()) seen += text() }
        announcer.say("Kraken stirs")
        record()
        advanceTo(10_000)
        record()
        announcer.say("Kraken stirs") // within 30 s: dropped
        record()
        advanceTo(31_000)
        announcer.say("Kraken stirs")
        record()
        assertEquals(listOf("", "Kraken stirs", "", "Kraken stirs"), seen)
        assertEquals(2L, announcer.current?.seq)
    }
}
