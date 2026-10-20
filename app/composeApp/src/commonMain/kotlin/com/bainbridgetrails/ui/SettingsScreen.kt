package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.settings_about
import com.bainbridgetrails.resources.settings_attribution_dataset
import com.bainbridgetrails.resources.settings_attribution_fonts
import com.bainbridgetrails.resources.settings_map
import com.bainbridgetrails.resources.settings_match_dark
import com.bainbridgetrails.resources.settings_show_intro
import com.bainbridgetrails.resources.settings_style
import com.bainbridgetrails.resources.settings_style_dark
import com.bainbridgetrails.resources.settings_style_light
import com.bainbridgetrails.resources.settings_style_topo
import com.bainbridgetrails.resources.settings_title
import com.bainbridgetrails.resources.settings_units
import com.bainbridgetrails.resources.settings_units_km
import com.bainbridgetrails.resources.settings_units_mi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings (F10). Map style + match-dark, units, show intro, dataset version. Account/attribution come next. */
@Composable
fun SettingsScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onShowIntro: () -> Unit) {
    val dataset by AppGraph.datasets.state.collectAsStateWithLifecycle()
    PushedScreen(stringResource(Res.string.settings_title), onBack) {
        Section(Res.string.settings_map)
        Text(stringResource(Res.string.settings_style), style = MaterialTheme.typography.labelLarge)
        for ((style, label) in listOf(MapStyle.TOPO to Res.string.settings_style_topo, MapStyle.MINIMAL_LIGHT to Res.string.settings_style_light, MapStyle.DARK to Res.string.settings_style_dark)) {
            Choice(stringResource(label), settings.mapStyle == style) { graph.settings.update { it.copy(mapStyle = style) } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.settings_match_dark), Modifier.weight(1f))
            Switch(settings.matchDark, onCheckedChange = { on -> graph.settings.update { it.copy(matchDark = on) } })
        }
        Section(Res.string.settings_units)
        Choice(stringResource(Res.string.settings_units_mi), settings.units == Units.MILES) { graph.settings.update { it.copy(units = Units.MILES) } }
        Choice(stringResource(Res.string.settings_units_km), settings.units == Units.KILOMETRES) { graph.settings.update { it.copy(units = Units.KILOMETRES) } }
        Section(Res.string.settings_about)
        (dataset as? Loadable.Ready)?.let { Text(stringResource(Res.string.settings_attribution_dataset, it.value.dataset.version)) }
        Text(stringResource(Res.string.settings_attribution_fonts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onShowIntro) { Text(stringResource(Res.string.settings_show_intro)) }
    }
}

@Composable
private fun Section(title: StringResource) {
    Spacer(Modifier.height(16.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label)
    }
}
