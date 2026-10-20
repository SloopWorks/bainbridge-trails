package com.bainbridgetrails.record

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationAskTest {
    @Test
    fun preciseIsReady() {
        assertEquals(LocationAsk.READY, locationAsk(LocationAccess.PRECISE, permanentlyDenied = false, askedPrecise = false))
        assertEquals(LocationAsk.READY, locationAsk(LocationAccess.PRECISE, permanentlyDenied = true, askedPrecise = true))
    }

    @Test
    fun approximateAsksForPreciseOnceThenSettings() {
        assertEquals(LocationAsk.ASK_PRECISE, locationAsk(LocationAccess.APPROXIMATE, permanentlyDenied = false, askedPrecise = false))
        // The user kept approximate in the system dialog: only Settings can turn precise on now.
        assertEquals(LocationAsk.OPEN_SETTINGS_PRECISE, locationAsk(LocationAccess.APPROXIMATE, permanentlyDenied = false, askedPrecise = true))
        assertEquals(LocationAsk.OPEN_SETTINGS_PRECISE, locationAsk(LocationAccess.APPROXIMATE, permanentlyDenied = true, askedPrecise = false))
    }

    @Test
    fun noneAsksThenSettingsWhenPermanentlyDenied() {
        assertEquals(LocationAsk.ASK, locationAsk(LocationAccess.NONE, permanentlyDenied = false, askedPrecise = false))
        assertEquals(LocationAsk.OPEN_SETTINGS, locationAsk(LocationAccess.NONE, permanentlyDenied = true, askedPrecise = false))
    }
}
