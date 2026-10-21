package com.bainbridgetrails.play.fx

import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.map.CASING_FX_SLOT
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.map.statusLayerPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure mode stays untouched (engine PF-9, plan L2-2): with Play off the map call sites get no FX host inputs (so no
 * FxScene, clock, sources or layers are created), and `StatusLayers(casingFx = null)` composes today's layer list.
 */
class PlayGateTest {
    @Test
    fun fxHostInputsNullWhenPlayOff() {
        assertNull(fxHostInputs(AppSettings().play, recording = false))
        assertNull(fxHostInputs(PlaySettings(enabled = false, everToggled = true, ambient = true), recording = true))
    }

    @Test
    fun fxHostInputsPresentWhenPlayOnAndAmbientFollowsSettingAndRecording() {
        val on = assertNotNull(fxHostInputs(PlaySettings(enabled = true), recording = false))
        assertFalse(on.ambientOff)
        assertTrue(assertNotNull(fxHostInputs(PlaySettings(enabled = true), recording = true)).ambientOff, "ambient rests while recording")
        assertTrue(assertNotNull(fxHostInputs(PlaySettings(enabled = true, ambient = false), recording = false)).ambientOff)
    }

    /** Every status present, a highlight and a selection: the fullest trail composition (StatusLayers before L2-2). */
    private val allBuckets = mapOf(
        TrailStatus.PARTWAY to listOf("a"),
        TrailStatus.NEARLY_DONE to listOf("b"),
        TrailStatus.COMPLETE to listOf("c"),
    )

    @Test
    fun statusLayersCasingFxNullComposesTodaysLayers() {
        val plan = statusLayerPlan(allBuckets, StatusLayerSpec.TRAILS, MapPalette.Dark, highlight = true, selection = true, roadEnds = false, casingFx = false)
        assertEquals(
            listOf(
                "app-nc-hairline", "app-nc-closed", "app-nc-easement",
                "app-trail-halo-not_started", "app-trail-halo-partway", "app-trail-halo-nearly_done", "app-trail-halo-complete",
                "app-highlight-outline", "app-highlight-gap",
                "app-trail-casing-partway", "app-trail-casing-nearly_done",
                "app-trail-glow",
                "app-trail-not_started", "app-trail-partway", "app-trail-nearly_done", "app-trail-complete",
                "app-trail-nc-not_started", "app-trail-nc-partway", "app-trail-nc-nearly_done", "app-trail-nc-complete",
                "app-selected-outline", "app-selected-gap", "app-selected-line",
            ),
            plan.layerIds(),
        )
        // Coverage-free Topo: only the not-started bucket draws, no glow.
        assertEquals(
            listOf(
                "app-nc-hairline", "app-nc-closed", "app-nc-easement",
                "app-trail-halo-not_started", "app-trail-not_started", "app-trail-nc-not_started",
            ),
            statusLayerPlan(emptyMap(), StatusLayerSpec.TRAILS, MapPalette.Topo, highlight = false, selection = false, roadEnds = false, casingFx = false).layerIds(),
        )
        // Roads: no hairlines, no not-started halo, no named-not-counted, road-end pins on top.
        assertEquals(
            listOf("app-trail-halo-complete", "app-trail-not_started", "app-trail-complete", "app-road-ends"),
            statusLayerPlan(mapOf(TrailStatus.COMPLETE to listOf("c")), StatusLayerSpec.ROADS, MapPalette.Topo, highlight = false, selection = false, roadEnds = true, casingFx = false).layerIds(),
        )
    }

    @Test
    fun casingFxSlotSitsAfterHalosBeforeStatusLines() {
        val off = statusLayerPlan(allBuckets, StatusLayerSpec.TRAILS, MapPalette.Topo, highlight = true, selection = true, roadEnds = false, casingFx = false).layerIds()
        val on = statusLayerPlan(allBuckets, StatusLayerSpec.TRAILS, MapPalette.Topo, highlight = true, selection = true, roadEnds = false, casingFx = true).layerIds()
        assertEquals(off, on - CASING_FX_SLOT, "the slot only adds itself")
        val slot = on.indexOf(CASING_FX_SLOT)
        assertTrue(on.subList(0, slot).containsAll(on.filter { it.startsWith("app-trail-halo-") }), "after every halo")
        assertTrue(on.subList(slot, on.size).containsAll(listOf("app-trail-casing-partway", "app-trail-not_started", "app-trail-complete")), "before casings and status lines")
    }
}
