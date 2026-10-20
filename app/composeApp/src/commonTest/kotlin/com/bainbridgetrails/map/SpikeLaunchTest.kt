package com.bainbridgetrails.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpikeLaunchTest {
    @Test
    fun defaultsWhenEmpty() {
        val l = SpikeLaunch.parse(emptyMap())
        assertEquals(MapStyle.Default, l.style)
        assertEquals(IslandCamera, l.camera)
        assertNull(l.switchTo)
        assertEquals(SpikeLaunch.Screen.RECORD, l.screen)
    }

    @Test
    fun picksScreen() {
        assertEquals(SpikeLaunch.Screen.STYLE, SpikeLaunch.parse(mapOf("screen" to "style")).screen)
        assertEquals(SpikeLaunch.Screen.STYLE, SpikeLaunch.parse(mapOf("style" to "dark")).screen)
        assertEquals(SpikeLaunch.Screen.RECORD, SpikeLaunch.parse(mapOf("screen" to "record", "style" to "dark")).screen)
        assertEquals(SpikeLaunch.Screen.RECORD, SpikeLaunch.parse(mapOf("lat" to "47.6", "lon" to "-122.5")).screen)
    }

    @Test
    fun parsesStyleCameraAndSwitch() {
        val l = SpikeLaunch.parse(
            mapOf("style" to "dark", "lat" to "47.655", "lon" to "-122.548", "zoom" to "15", "switchTo" to "MINIMAL_LIGHT", "switchAfterMs" to "500"),
        )
        assertEquals(MapStyle.DARK, l.style)
        assertEquals(47.655, l.camera.center.latitude)
        assertEquals(-122.548, l.camera.center.longitude)
        assertEquals(15.0, l.camera.zoom)
        assertEquals(MapStyle.MINIMAL_LIGHT, l.switchTo)
        assertEquals(500, l.switchAfterMs)
    }

    @Test
    fun ignoresUnknownStyle() {
        assertEquals(MapStyle.Default, SpikeLaunch.parse(mapOf("style" to "neon")).style)
    }
}
