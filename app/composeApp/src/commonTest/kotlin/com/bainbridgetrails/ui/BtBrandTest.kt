package com.bainbridgetrails.ui

import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.ui.theme.BtBrand
import com.bainbridgetrails.ui.theme.BtDarkScheme
import com.bainbridgetrails.ui.theme.BtLightScheme
import com.bainbridgetrails.ui.theme.withBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/roads/ux.md §1.1: trails is unchanged; roads swaps in survey slate + spruce secondary. */
class BtBrandTest {
    @Test
    fun trailsBrandIsTheV1Scheme() {
        assertEquals(BtLightScheme.toString(), BtLightScheme.withBrand(BtBrand.TRAILS.light).toString())
        assertEquals(BtDarkScheme.toString(), BtDarkScheme.withBrand(BtBrand.TRAILS.dark).toString())
    }

    @Test
    fun roadsBrandIsSurveySlate() {
        val light = BtLightScheme.withBrand(BtBrand.ROADS.light)
        assertEquals(Color(0xFF2F5068), light.primary)
        assertEquals(Color(0xFF2F5068), light.surfaceTint)
        assertEquals(Color(0xFF2E5E4E), light.secondary)
        assertEquals(BtLightScheme.background, light.background)
        assertEquals(Color(0xFFA8C8E0), BtDarkScheme.withBrand(BtBrand.ROADS.dark).primary)
        assertEquals(BtBrand.ROADS, BtBrand.of(DatasetProfile.ROADS))
    }
}
