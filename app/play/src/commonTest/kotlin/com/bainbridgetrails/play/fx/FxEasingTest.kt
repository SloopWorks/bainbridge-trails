package com.bainbridgetrails.play.fx

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FxEasingTest {
    /**
     * Compose `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)` (BtEasing.emphasizedDecelerate) at f = i/100, computed from
     * the cubic-bezier definition by bisection on x(t) (200 iterations, double), then y(t) — what Compose's solver
     * converges to.
     */
    private val composeEmphasizedDecelerate = doubleArrayOf(
        0.000000, 0.131658, 0.240027, 0.325265, 0.392832, 0.447700, 0.493366, 0.532187, 0.565768, 0.595231, 0.621384,
        0.644825, 0.666005, 0.685274, 0.702909, 0.719129, 0.734114, 0.748012, 0.760946, 0.773019, 0.784319, 0.794921,
        0.804891, 0.814284, 0.823149, 0.831530, 0.839464, 0.846987, 0.854127, 0.860913, 0.867367, 0.873513, 0.879370,
        0.884956, 0.890287, 0.895379, 0.900244, 0.904897, 0.909348, 0.913608, 0.917687, 0.921595, 0.925338, 0.928926,
        0.932366, 0.935665, 0.938829, 0.941863, 0.944774, 0.947568, 0.950247, 0.952819, 0.955286, 0.957653, 0.959924,
        0.962103, 0.964192, 0.966196, 0.968117, 0.969958, 0.971723, 0.973413, 0.975032, 0.976581, 0.978063, 0.979481,
        0.980836, 0.982130, 0.983366, 0.984544, 0.985667, 0.986737, 0.987755, 0.988722, 0.989640, 0.990511, 0.991335,
        0.992115, 0.992851, 0.993544, 0.994196, 0.994808, 0.995380, 0.995915, 0.996412, 0.996873, 0.997299, 0.997690,
        0.998048, 0.998373, 0.998666, 0.998928, 0.999160, 0.999362, 0.999535, 0.999679, 0.999796, 0.999886, 0.999950,
        0.999988, 1.000000,
    )

    @Test
    fun `emphasizedDecelerate matches Compose at 101 samples within 1e-3`() {
        assertEquals(101, composeEmphasizedDecelerate.size)
        for (i in 0..100) {
            val got = FxEasing.EmphasizedDecelerate.transform(i / 100f)
            val want = composeEmphasizedDecelerate[i]
            assertTrue(abs(got - want) <= 1e-3, "f=${i / 100.0}: got $got want $want")
        }
    }

    @Test
    fun endpointsAndClamping() {
        for (e in listOf(FxEasing.Linear, FxEasing.Emphasized, FxEasing.EmphasizedDecelerate, FxEasing.EmphasizedAccelerate)) {
            assertEquals(0f, e.transform(0f))
            assertEquals(1f, e.transform(1f))
            assertEquals(0f, e.transform(-0.5f))
            assertEquals(1f, e.transform(1.5f))
        }
        assertEquals(0.25f, FxEasing.Linear.transform(0.25f), 1e-4f)
    }
}
