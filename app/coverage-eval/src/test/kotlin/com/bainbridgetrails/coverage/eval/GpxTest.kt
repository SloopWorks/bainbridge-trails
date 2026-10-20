package com.bainbridgetrails.coverage.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GpxTest {
    private val t0 = 1792317600000L // 2026-10-18T10:00:00Z

    @Test
    fun gpxParsesTrkpts() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>t</name><trkseg>
                <trkpt lat="47.63" lon="-122.53"><ele>10</ele><time>2026-10-18T10:00:00Z</time></trkpt>
                <trkpt lat="47.6301" lon="-122.5301"><time>2026-10-18T10:00:05.500Z</time></trkpt>
              </trkseg><trkseg>
                <trkpt lat="47.6302" lon="-122.5302"><time>2026-10-18T03:00:10-07:00</time></trkpt>
              </trkseg></trk>
            </gpx>"""
        val fixes = readGpx(xml)
        assertEquals(3, fixes.size)
        assertEquals(listOf(t0, t0 + 5_500, t0 + 10_000), fixes.map { it.timeMs })
        assertEquals(-122.5301, fixes[1].lon)
        assertEquals(47.6301, fixes[1].lat)
        assertEquals(null, fixes[0].accuracyM)
        assertEquals(false, fixes[0].isMock)
    }

    @Test
    fun gpxWithoutTimeRejected() {
        val xml = """<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="47.63" lon="-122.53"><time>2026-10-18T10:00:00Z</time></trkpt>
            <trkpt lat="47.63" lon="-122.53"></trkpt>
            </trkseg></trk></gpx>"""
        assertFailsWith<IllegalArgumentException> { readGpx(xml) }
    }

    @Test
    fun gpxDoctypeRejected() {
        // GPX files are untrusted: no DOCTYPE, so no external entities (XXE)
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><trk><trkseg><trkpt lat="47.63" lon="-122.53"><time>&x;</time></trkpt></trkseg></trk></gpx>"""
        assertFailsWith<IllegalArgumentException> { readGpx(xml) }
    }
}
