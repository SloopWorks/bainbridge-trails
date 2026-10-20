package com.bainbridgetrails.record

import kotlin.test.Test
import kotlin.test.assertTrue

/** Play prominent-disclosure content: background use, its only purpose, optional, and how to turn it off. */
class GeofenceDisclosureTest {
    private val text = disclosureBody("Allow all the time").joinToString(" ")

    @Test
    fun disclosureStatesPurposeOptionalAndOff() {
        assertTrue("in the background" in text)
        assertTrue("only to notify you on arrival at trailheads" in text)
        assertTrue("This is optional" in text)
        assertTrue("turn it off" in text && "Settings" in text)
        assertTrue("\"Allow all the time\"" in text)
    }
}
