package com.bainbridgetrails.play

import com.bainbridgetrails.analytics.Analytics
import com.bainbridgetrails.analytics.PlayAnalytics
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.map.statusLayerPlan
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.MapContentPart
import com.bainbridgetrails.play.fx.fxHostInputs
import com.bainbridgetrails.play.fx.mapContentOrder
import com.bainbridgetrails.ui.play.sheets.PlayOptInGraph
import com.bainbridgetrails.ui.play.sheets.PlayToggleFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PF0-1, PF0-4 (PP-2): with Play never enabled nothing Play is composed, created or tracked. */
class PureModeTest {
    private class Recording : Analytics {
        val events = mutableListOf<String>()
        override fun track(event: String, props: Map<String, String>) {
            events += event
        }
    }

    @Test
    fun playNeverEnabledNoFxLayerIdNoPlayRepositoryNoPlayAnalyticsEvent() = runTest {
        val play = AppSettings().play
        // 1. No fx- layer id in any composed layer list: no host → no FX bands, and today's status plan.
        val host = fxHostInputs(play, recording = false)
        assertNull(host)
        assertNull(fxHostInputs(play, recording = true))
        for (location in listOf(false, true)) {
            assertTrue(mapContentOrder(play = host != null, location = location).none { it is MapContentPart.Fx }, "no FX band composed")
        }
        val buckets = mapOf(TrailStatus.PARTWAY to listOf("a"), TrailStatus.COMPLETE to listOf("c"))
        for (spec in listOf(StatusLayerSpec.TRAILS, StatusLayerSpec.ROADS)) for (palette in listOf(MapPalette.Topo, MapPalette.Dark)) {
            for (sel in listOf(false, true)) {
                val ids = statusLayerPlan(buckets, spec, palette, highlight = sel, selection = sel, roadEnds = spec == StatusLayerSpec.ROADS, casingFx = host != null).layerIds()
                assertTrue(ids.none { FxLayerIds.isFx(it) }, "fx- layer in $ids")
            }
        }

        // 2. No PlayRepository: the gate never constructs one through a whole session (launch, walk, settings writes).
        val settings = MutableStateFlow<PlaySettings?>(null)
        var created = 0
        val gate = PlayGate(backgroundScope, settings) { created++; error("PlayRepository must not be created while Play is off") }
        gate.start()
        settings.value = play
        runCurrent()
        settings.value = play.copy(sounds = true, ambient = false) // other Play prefs written, still off
        runCurrent()
        assertNull(gate.current)
        assertEquals(0, created)

        // 3. No Play analytics event: every Play event is dropped while off, and dismissing the intro emits nothing.
        val sink = Recording()
        val analytics = PlayAnalytics({ sink }, { settings.value?.enabled == true })
        listOf(PlayAnalytics.ENABLED, PlayAnalytics.DISABLED, PlayAnalytics.START_BALANCE, PlayAnalytics.LOGBOOK_OPENED, PlayAnalytics.QUIET_DAY, PlayAnalytics.CONTENT_OPENED)
            .forEach { analytics.track(it) }
        val toggle = PlayToggleFlow(
            PlayOptInGraph(updatePlay = { c -> settings.value = c(settings.value ?: PlaySettings()) }, repo = { gate.current }, analytics = analytics, goal = "roads"),
            enabled = false,
        )
        toggle.onSwitch(true)
        toggle.onIntroDismissed()
        runCurrent()
        assertEquals(emptyList(), sink.events.filter { it.startsWith("play_") })
        assertNull(gate.current)
        assertEquals(0, created)
    }
}
