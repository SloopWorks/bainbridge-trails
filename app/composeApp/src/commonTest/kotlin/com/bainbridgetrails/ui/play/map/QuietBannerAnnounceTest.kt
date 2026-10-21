package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.SettingsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ux.md § 5.4: the quiet banner's line is announced once per island day, and a restart on that day stays silent. */
class QuietBannerAnnounceTest {
    @Test
    fun oncePerIslandDay() {
        assertTrue(QuietBannerAnnounce.due("2027-03-30", announcedOn = null))
        assertFalse(QuietBannerAnnounce.due("2027-03-30", announcedOn = "2027-03-30"))
        assertTrue(QuietBannerAnnounce.due("2028-03-30", announcedOn = "2027-03-30"))
        assertFalse(QuietBannerAnnounce.due("", announcedOn = null), "no day, no announcement")
    }

    @Test
    fun restartOnTheSameDayStaysSilent() = runTest {
        val path = DataFixtures.tmpDir("quiet-announce") + "/settings.json"
        val d = StandardTestDispatcher(testScheduler)
        val day = "2027-03-30"
        val first = SettingsRepository(path, backgroundScope, io = d)
        assertTrue(QuietBannerAnnounce.due(day, first.await().play.quietAnnouncedOn))
        first.updateNow { it.copy(play = QuietBannerAnnounce.announced(it.play, day)) }

        // A new process (same file) on the same island day.
        val again = SettingsRepository(path, backgroundScope, io = d).await()
        assertFalse(QuietBannerAnnounce.due(day, again.play.quietAnnouncedOn))
        assertTrue(QuietBannerAnnounce.due("2028-03-30", again.play.quietAnnouncedOn))
    }
}
