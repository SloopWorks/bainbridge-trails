package com.bainbridgetrails.nav

import com.bainbridgetrails.map.SpikeLaunch
import kotlin.test.Test
import kotlin.test.assertEquals

class StartRouteTest {
    @Test
    fun normalLaunchStartsOnMap() = assertEquals<Route>(MapRoute, startRouteFor(null))

    @Test
    fun spikeScriptsKeepTheirScreens() {
        assertEquals<Route>(RecordRoute, startRouteFor(SpikeLaunch.parse(mapOf("screen" to "record"))))
        assertEquals<Route>(RecordRoute, startRouteFor(SpikeLaunch.parse(mapOf("lat" to "47.6", "lon" to "-122.5"))))
        assertEquals<Route>(StyleSpikeRoute, startRouteFor(SpikeLaunch.parse(mapOf("style" to "dark"))))
    }
}
