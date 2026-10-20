package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import kotlin.test.Test
import kotlin.test.assertEquals

class BitsetsTest {
    @Test
    fun minusKeepsOnlyUnseenBits() {
        val local = mapOf("seg-000001" to "Bw==", "seg-000002" to "AAE=", "seg-000003" to "AQ==")
        val base = mapOf("seg-000001" to "Aw==", "seg-000003" to "AQ==")
        // 0b111 - 0b011 = 0b100; seg 2 unseen entirely; seg 3 fully seen → omitted.
        assertEquals(mapOf("seg-000001" to "BA==", "seg-000002" to "AAE="), Bitsets.minus(local, base))
    }

    @Test
    fun minusDropsTrailingZeroBytes() {
        assertEquals(mapOf("seg-000001" to "AQ=="), Bitsets.minus(mapOf("seg-000001" to "AQE="), mapOf("seg-000001" to "AAH/")))
    }

    @Test
    fun unionIsSortedAndNormalized() {
        val u = Bitsets.union(mapOf("seg-000002" to "AQ=="), mapOf("seg-000001" to "AA==", "seg-000002" to "AAE="))
        assertEquals(listOf("seg-000002"), u.keys.toList())
        assertEquals("AQE=", u["seg-000002"])
    }

    @Test
    fun roundTripsCoverageStateEncoding() {
        val s = CoverageState.decode("""{"seg-000001":"/////w8=","seg-000099":"Hw=="}""")
        assertEquals(mapOf("seg-000001" to "/////w8=", "seg-000099" to "Hw=="), Bitsets.fromState(s))
        assertEquals(s, Bitsets.toState(Bitsets.fromState(s)))
    }
}
