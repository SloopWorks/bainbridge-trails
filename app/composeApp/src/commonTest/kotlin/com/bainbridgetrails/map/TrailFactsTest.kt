package com.bainbridgetrails.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailFactsTest {
    private val trails = """
        {"version":"v","island_total_shippable_m":1,"trails":[
          {"id":"t1","name":"One","park":"Gazzam Lake Preserve","manager":"BIMPRD","gain_m":38.2,
           "official_map_url":"https://www.biparks.org/x","official_length_mi":1.4,"computed_length_mi":1.31,
           "segment_ids":["s1"],"trailheads":[{"type":"Point","coordinates":[-122.5,47.6]}]},
          {"id":"t2","name":"Two","park":null,"manager":null,"gain_m":null,"official_map_url":null,
           "official_length_mi":null,"computed_length_mi":0.3,"segment_ids":["s2"],
           "trailheads":[{"type":"Point","coordinates":[-122.51,47.61]}]},
          {"id":"t3","name":"Three","computed_length_mi":0.2,"segment_ids":["s3"],"trailheads":[]}
        ]}
    """.trimIndent()

    private val heads = """
        {"version":"v","trailheads":[
          {"id":"th-a","lat":47.6,"lon":-122.5,"name":"Marshall Rd","source":"agency","trail_ids":["t1"]},
          {"id":"th-b","lat":47.61,"lon":-122.49,"name":"Marshall Rd","source":"road_access","trail_ids":["t1"]}
        ]}
    """.trimIndent()

    @Test
    fun namedTrailheadsWinAndDuplicatesAreNumbered() {
        val f = parseTrailFacts(trails, heads)
        assertEquals(listOf("Marshall Rd (1)", "Marshall Rd (2)"), f.getValue("t1").trailheads.map { it.name })
        // No named entry: falls back to the unnamed trails.json point (lon/lat order swapped correctly).
        assertEquals(listOf(Trailhead(null, 47.61, -122.51)), f.getValue("t2").trailheads)
        assertTrue(f.getValue("t3").trailheads.isEmpty())
    }

    @Test
    fun officialLengthPreferred() {
        val f = parseTrailFacts(trails, null)
        assertEquals(1.4, f.getValue("t1").lengthMi)
        assertTrue(f.getValue("t1").isOfficialLength)
        assertEquals(0.3, f.getValue("t2").lengthMi)
        assertEquals("BIMPRD", f.getValue("t1").manager)
        assertEquals(null, f.getValue("t2").park)
    }

    @Test
    fun directionsAndHost() {
        assertEquals("Marshall%20Rd%20%26%20Co", encodeQuery("Marshall Rd & Co"))
        assertEquals("biparks.org", com.bainbridgetrails.ui.hostOf("https://www.biparks.org/home/x"))
    }
}
