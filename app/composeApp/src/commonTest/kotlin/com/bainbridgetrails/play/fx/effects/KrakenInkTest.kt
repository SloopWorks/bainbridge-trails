package com.bainbridgetrails.play.fx.effects

import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.MapPalette
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxPalette
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.ui.theme.BtExtraDark
import com.bainbridgetrails.ui.theme.BtExtraLight
import com.bainbridgetrails.ui.theme.PlayDark
import com.bainbridgetrails.ui.theme.PlayLight
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KrakenInkTest {
    private val a = KrakenInk.Stretch(
        "road-manitou-beach-drive-northeast",
        listOf(
            listOf(GeoPoint(-122.5052, 47.6590), GeoPoint(-122.5049, 47.6600), GeoPoint(-122.5047, 47.6610)),
            listOf(GeoPoint(-122.5047, 47.6610), GeoPoint(-122.5045, 47.6620)),
        ),
    )
    private val b = KrakenInk.Stretch("road-northeast-gertie-johnson-road", listOf(listOf(GeoPoint(-122.5060, 47.6650), GeoPoint(-122.5055, 47.6660))))
    private fun attack(active: Boolean = true) = KrakenInk.Attack("kraken-roads@2026-10-29", listOf(a, b), water = GeoPoint(-122.5025, 47.6603), active = active)

    @Test
    fun casingOnlyOnActiveStretches() {
        assertTrue(KrakenInk.specs(attack(active = false), repelled = emptySet()).isEmpty(), "no casing outside the attack window")
        val specs = KrakenInk.specs(attack(), repelled = emptySet())
        val casing = specs.filter { it.band == FxBand.CASING }
        assertEquals(3, casing.size, "one casing feature per stretch line")
        assertTrue(casing.all { it.anchor is FxAnchor.Line && KrakenInk.stretchOf(it) in setOf(a.id, b.id) })
        // Pool (UNDER_STATUS) + far marker ride on one water instance.
        val water = specs.single { it.anchor is FxAnchor.Point }
        assertEquals(FxBand.UNDER_STATUS, water.band)
        assertEquals(specs.map { it.id }.toSet().size, specs.size, "ids are unique")
    }

    @Test
    fun repelledStretchRemovedPerUser() {
        val specs = KrakenInk.specs(attack(), repelled = setOf(a.id))
        val casing = specs.filter { it.band == FxBand.CASING }
        assertTrue(casing.none { KrakenInk.stretchOf(it) == a.id }, "the user's repelled stretch has no casing")
        assertEquals(1, casing.size)
        assertNotNull(KrakenInk.introSpec(attack(), repelled = setOf(a.id)), "the attack still stands on b")
        // Every stretch repelled: nothing left for this user, not even the pool or the intro.
        assertTrue(KrakenInk.specs(attack(), repelled = setOf(a.id, b.id)).isEmpty())
        assertNull(KrakenInk.introSpec(attack(), repelled = setOf(a.id, b.id)))
    }

    @Test
    fun introRevealsAndGripsThenHandsOff() {
        val intro = KrakenInk.introSpec(attack(), emptySet())!!
        assertEquals(FxBand.OVERLAY, intro.band)
        assertTrue(intro.timeline.totalMs <= 3_000 && intro.timeline.runLoopMs == null)
        assertEquals(0f, KrakenInk.revealAt(0)); assertEquals(1f, KrakenInk.revealAt(intro.timeline.reducedAtMs))
        assertEquals(0f, KrakenInk.gripAt(0)); assertEquals(0f, KrakenInk.gripAt(intro.timeline.reducedAtMs))
        assertTrue(KrakenInk.gripAt(1_500) > 0.9f)
    }

    /** PD-20: casing = play.ink blended 35 % toward the Sound teal, opaque, ΔE ≥ 20 from not-started over land. */
    @Test
    fun inkCasingPinnedAndFarFromNotStarted() {
        val light = FxPalette.of(PlayLight).inkCasing
        val dark = FxPalette.of(PlayDark).inkCasing
        assertEquals(Color(0xFF2B4751), light)
        assertEquals(Color(0xFFC0DAE1), dark)
        val lightNotStarted = listOf(BtExtraLight.notStarted.line, MapPalette.Topo.of(TrailStatus.NOT_STARTED), MapPalette.Light.of(TrailStatus.NOT_STARTED))
        val darkNotStarted = listOf(BtExtraDark.notStarted.line, MapPalette.Dark.of(TrailStatus.NOT_STARTED))
        for (ns in lightNotStarted) assertTrue(deltaE(light, ns) >= 20.0, "light ΔE ${deltaE(light, ns)}")
        for (ns in darkNotStarted) assertTrue(deltaE(dark, ns) >= 20.0, "dark ΔE ${deltaE(dark, ns)}")
    }

    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin(c.red); val g = lin(c.green); val bl = lin(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}
