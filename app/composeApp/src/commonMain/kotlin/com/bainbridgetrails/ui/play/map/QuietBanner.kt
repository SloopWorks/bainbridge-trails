package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_quiet_banner
import com.bainbridgetrails.resources.play_quiet_banner_action
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** The quiet banner is announced (politely) once per island [day] per process (ux.md § 5.4). Pure. */
internal object QuietBannerAnnounce {
    private var lastDay: String? = null

    fun first(day: String): Boolean {
        if (day == lastDay) return false
        lastDay = day
        return true
    }
}

/**
 * Quiet day (Mar 30) banner (ux.md §3.5.6): replaces the Play row; quiet glyph, no sea glass, no motion. Its line is
 * said politely through [announce] (Play's one announcer) once per [day].
 */
@Composable
fun QuietBanner(onRead: () -> Unit, modifier: Modifier = Modifier, day: String = "", announce: ((String) -> Unit)? = null) {
    val line = stringResource(Res.string.play_quiet_banner)
    LaunchedEffect(day, announce != null) {
        if (announce != null && QuietBannerAnnounce.first(day)) announce(line)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(3.dp, MaterialTheme.shapes.large)
            .background(BtTheme.colors.mapChrome, MaterialTheme.shapes.large).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FxGlyphImage(FxGlyph.QuietRing, size = 20.dp, ink = BtTheme.colors.onMapChrome)
        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = BtTheme.colors.onMapChrome)
        TextButton(onClick = onRead) { Text(stringResource(Res.string.play_quiet_banner_action)) }
    }
}
