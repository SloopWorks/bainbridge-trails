package com.bainbridgetrails.play.fx

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.ui.theme.BtExtraColors
import com.bainbridgetrails.ui.theme.BtExtraDark
import com.bainbridgetrails.ui.theme.BtExtraLight
import com.bainbridgetrails.ui.theme.PlayDark
import com.bainbridgetrails.ui.theme.PlayLight
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Status hues are reserved (engine PF-9, effects §0 rule 1): no FX color, as drawn over the map land, is within CIE76
 * ΔE 20 of a coverage status color (theme roles and every map palette of that brightness), recording or userTrack.
 */
class FxPaletteGuardTest {
    @Test
    fun noFxColorWithinDeltaE20OfAStatusColorLight() =
        assertFar(FxPalette.of(PlayLight), BtExtraLight, listOf(MapPalette.Topo, MapPalette.Light), land = MAP_LAND_LIGHT, theme = "light")

    @Test
    fun noFxColorWithinDeltaE20OfAStatusColorDark() =
        assertFar(FxPalette.of(PlayDark), BtExtraDark, listOf(MapPalette.Dark), land = MAP_LAND_DARK, theme = "dark")

    @Test
    fun paletteIsDerivedFromThePlayRolesOnly() {
        val p = FxPalette.of(PlayLight)
        assertEquals(PlayLight.ink, p.ink)
        assertEquals(PlayLight.seaGlass, p.seaGlass)
        assertEquals(PlayLight.lamp, p.lamp)
        assertEquals(PlayLight.fog, p.fog)
        assertTrue(p.inkPool.alpha <= 0.35f, "ink pool α ≤ .35 (effects §1.6)")
        assertTrue(p.colors().map { it.first }.toSet().size == p.colors().size, "role names are unique")
    }

    private fun assertFar(fx: FxPalette, x: BtExtraColors, maps: List<MapPalette>, land: Color, theme: String) {
        val reserved = buildMap {
            listOf("notStarted" to x.notStarted, "partway" to x.partway, "nearlyDone" to x.nearlyDone, "complete" to x.complete)
                .forEach { (n, s) -> put("$n.line", s.line); put("$n.accent", s.accent) }
            maps.forEachIndexed { i, m -> TrailStatus.entries.forEach { s -> put("map$i.${s.name}", m.of(s)) } }
            put("recording", x.recording)
            put("userTrack", x.userTrack)
        }
        val problems = mutableListOf<String>()
        fx.colors().forEach { (name, c) ->
            val seen = c.compositeOver(land)
            reserved.forEach { (rn, rc) ->
                val d = deltaE76(seen, rc.compositeOver(land))
                if (d < 20.0) problems += "$theme fx.$name vs $rn: ΔE ${((d * 10).toInt() / 10.0)} < 20"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        /** Map land, docs/spikes/map-render/styles.md (Topo / Dark). */
        val MAP_LAND_LIGHT = Color(0xFFECEFE6)
        val MAP_LAND_DARK = Color(0xFF1D2925)

        fun deltaE76(a: Color, b: Color): Double {
            val (l1, a1, b1) = lab(a)
            val (l2, a2, b2) = lab(b)
            return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
        }

        private fun lab(c: Color): Triple<Double, Double, Double> {
            fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            val r = lin(c.red)
            val g = lin(c.green)
            val bl = lin(c.blue)
            val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
            val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883
            fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
            return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
        }
    }
}
