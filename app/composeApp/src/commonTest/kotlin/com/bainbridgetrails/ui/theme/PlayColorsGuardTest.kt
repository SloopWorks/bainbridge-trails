package com.bainbridgetrails.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * docs/play/ux.md §1.1 guard: the four Play roles never read as a coverage status, recording, "you" (userTrack) or the
 * island-%-points `positive` hue (CIE76 ΔE ≥ 20, both themes, both brands), and sea glass is legible as `+n` text
 * on the three surfaces where text is allowed (WCAG ≥ 4.5).
 *
 * Exception (ux §1.1): `ink` is not checked against the roads `positive` (= roads `primary`, survey slate, ΔE 15.7):
 * slate is chrome-only and ink is art-only, and `primary` is excluded for ink only.
 */
class PlayColorsGuardTest {
    @Test
    fun everyPlayColorIsAtLeastDeltaE20FromStatusRecordingUserTrackPositiveLight() =
        assertFarFromReserved(BtExtraLight, BtLightScheme.surface, dark = false)

    @Test
    fun everyPlayColorIsAtLeastDeltaE20FromStatusRecordingUserTrackPositiveDark() =
        assertFarFromReserved(BtExtraDark, BtDarkScheme.surface, dark = true)

    @Test
    fun seaGlassTextContrastIsAtLeast4p5OnSurfaceSurfaceContainerLowAndMapChrome() {
        listOf(Triple("light", BtLightScheme, BtExtraLight to MAP_LAND_LIGHT), Triple("dark", BtDarkScheme, BtExtraDark to MAP_LAND_DARK))
            .forEach { (theme, scheme, extra) ->
                val (colors, land) = extra
                val sea = colors.play.seaGlass
                val chrome = colors.mapChrome.compositeOver(land)
                listOf("surface" to scheme.surface, "surfaceContainerLow" to scheme.surfaceContainerLow, "mapChrome" to chrome)
                    .forEach { (name, bg) ->
                        val ratio = contrast(sea, bg)
                        assertTrue(ratio >= 4.5, "$theme seaGlass on $name is ${ratio.fmt()}:1, needs 4.5")
                    }
            }
    }

    @Test
    fun rolesAreTheSpecValuesAndBrandIndependent() {
        assertSame(PlayLight, BtExtraLight.play)
        assertSame(PlayDark, BtExtraDark.play)
        assertEquals(Color(0xFF22323C), PlayLight.ink)
        assertEquals(Color(0xFF037245), PlayLight.seaGlass)
        assertEquals(Color(0xFFFFF1C9), PlayLight.lamp)
        assertEquals(Color(0x8CF4F6F5), PlayLight.fog)
        assertEquals(Color(0xFFCFE0E6), PlayDark.ink)
        assertEquals(Color(0xFF45E0AE), PlayDark.seaGlass)
        assertEquals(Color(0xFFFFF0CF), PlayDark.lamp)
        assertEquals(Color(0x407D9095), PlayDark.fog)
    }

    @Test
    fun seaGlassWashIsTheDerivedValue() {
        assertClose(Color(0xFFD1DED3), PlayLight.seaGlassWash, "light wash")
        assertClose(Color(0xFF1F4438), PlayDark.seaGlassWash, "dark wash")
        // Text on the wash is onSurface only (ux §1.1): it must clear 4.5 there.
        assertTrue(contrast(BtLightScheme.onSurface, PlayLight.seaGlassWash) >= 4.5)
        assertTrue(contrast(BtDarkScheme.onSurface, PlayDark.seaGlassWash) >= 4.5)
    }

    private fun assertFarFromReserved(x: BtExtraColors, surface: Color, dark: Boolean) {
        val brands = BtBrand.entries.associate { it.name to it.roles(dark).positive }
        val reserved = buildMap {
            listOf("notStarted" to x.notStarted, "partway" to x.partway, "nearlyDone" to x.nearlyDone, "complete" to x.complete)
                .forEach { (n, s) -> put("$n.line", s.line); put("$n.accent", s.accent) }
            put("recording", x.recording)
            put("userTrack", x.userTrack)
            brands.forEach { (b, c) -> put("positive.$b", c) }
        }
        val play = x.play
        val problems = mutableListOf<String>()
        listOf("ink" to play.ink, "seaGlass" to play.seaGlass, "lamp" to play.lamp, "fog" to play.fog).forEach { (pn, pc) ->
            val seen = pc.compositeOver(surface) // fog carries its alpha: judge what is drawn
            reserved.forEach { (rn, rc) ->
                if (pn == "ink" && rn == "positive.${BtBrand.ROADS.name}") return@forEach
                val d = deltaE76(seen, rc.compositeOver(surface))
                if (d < 20.0) problems += "${if (dark) "dark" else "light"} play.$pn vs $rn: ΔE ${d.fmt()} < 20"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun assertClose(expected: Color, actual: Color, what: String) {
        val tol = 1.5f / 255f
        assertTrue(
            abs(expected.red - actual.red) <= tol && abs(expected.green - actual.green) <= tol && abs(expected.blue - actual.blue) <= tol && actual.alpha == 1f,
            "$what: expected $expected, was $actual",
        )
    }

    private companion object {
        /** Map land, docs/spikes/map-render/styles.md (Topo / Dark): mapChrome is 95 % over it. */
        val MAP_LAND_LIGHT = Color(0xFFECEFE6)
        val MAP_LAND_DARK = Color(0xFF1D2925)

        fun contrast(a: Color, b: Color): Double {
            val la = a.luminance().toDouble()
            val lb = b.luminance().toDouble()
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** CIE76 ΔE between two opaque sRGB colors (D65 white, as the ux.md §1.1 measurements). */
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

        fun Double.fmt(): String = ((this * 10).toInt() / 10.0).toString()
    }
}
