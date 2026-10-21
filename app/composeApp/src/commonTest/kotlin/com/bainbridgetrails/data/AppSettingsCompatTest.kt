package com.bainbridgetrails.data

import com.bainbridgetrails.map.MapStyle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Play's settings block (docs/play/plan.md L2-2): settings.json written before Play decodes unchanged, Play off. */
class AppSettingsCompatTest {
    private fun writeFile(path: String, text: String) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray()) }
    }

    @Test
    fun oldSettingsJsonWithoutPlayDecodesPlayEnabledFalse() = runTest {
        val path = DataFixtures.tmpDir("settings-compat") + "/settings.json"
        // Exactly what a pre-Play build wrote (encodeDefaults = true).
        writeFile(path, """{"mapStyle":"DARK","matchDark":false,"units":"KILOMETRES","onboardingDone":true}""")
        val s = SettingsRepository(path, backgroundScope, io = StandardTestDispatcher(testScheduler)).await()
        assertEquals(MapStyle.DARK, s.mapStyle)
        assertFalse(s.matchDark)
        assertEquals(Units.KILOMETRES, s.units)
        assertTrue(s.onboardingDone)
        assertFalse(s.play.enabled)
        assertEquals(PlaySettings(), s.play)
    }

    @Test
    fun playDefaultsAreOffWithAmbientOn() {
        val p = AppSettings().play
        assertFalse(p.enabled)
        assertFalse(p.everToggled)
        assertFalse(p.offerShown)
        assertFalse(p.sounds)
        assertTrue(p.ambient)
    }

    @Test
    fun playBlockRoundTripsAndPartialBlockKeepsDefaults() = runTest {
        val path = DataFixtures.tmpDir("settings-compat") + "/settings.json"
        val d = StandardTestDispatcher(testScheduler)
        val first = SettingsRepository(path, backgroundScope, io = d)
        first.updateNow { it.copy(play = it.play.copy(enabled = true, everToggled = true, ambient = false)) }
        val again = SettingsRepository(path, backgroundScope, io = d).await()
        assertEquals(PlaySettings(enabled = true, everToggled = true, ambient = false), again.play)

        writeFile(path, """{"onboardingDone":true,"play":{"enabled":true}}""")
        val partial = SettingsRepository(path, backgroundScope, io = d).await()
        assertEquals(PlaySettings(enabled = true), partial.play)
        assertTrue(partial.play.ambient)
    }
}
