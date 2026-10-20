package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_loading_trails
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.error_dataset_body
import com.bainbridgetrails.resources.error_dataset_title
import com.bainbridgetrails.resources.status_chip
import com.bainbridgetrails.resources.status_chip_a11y
import com.bainbridgetrails.resources.status_complete
import com.bainbridgetrails.resources.status_nearly_done
import com.bainbridgetrails.resources.status_not_started
import com.bainbridgetrails.resources.status_partway
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Shows loading / error+retry until the dataset is parsed (F0 step 2), then [content]. */
@Composable
fun DatasetGate(modifier: Modifier = Modifier, content: @Composable (TrailData) -> Unit) {
    val state by AppGraph.datasets.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is Loadable.Ready -> content(s.value)
        Loadable.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.common_loading_trails))
            }
        }
        is Loadable.Failed -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.error_dataset_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.error_dataset_body))
                Button(onClick = { AppGraph.datasets.load() }) { Text(stringResource(Res.string.common_retry)) }
            }
        }
    }
}

/** Pushed-screen chrome: back + title, safe-area padding. Final top bar comes with the UX spec. */
@Composable
fun PushedScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), content = content)
    }
}

val TrailStatus.label: StringResource
    get() = when (this) {
        TrailStatus.NOT_STARTED -> Res.string.status_not_started
        TrailStatus.PARTWAY -> Res.string.status_partway
        TrailStatus.NEARLY_DONE -> Res.string.status_nearly_done
        TrailStatus.COMPLETE -> Res.string.status_complete
    }

/** "Partway · 42.0%" with the status color; read as "Partway, 42.0% walked". */
@Composable
fun StatusChip(status: TrailStatus, fraction: Double, modifier: Modifier = Modifier) {
    val label = stringResource(status.label)
    val pct = Format.percent(fraction)
    val a11y = stringResource(Res.string.status_chip_a11y, label, pct)
    Text(
        stringResource(Res.string.status_chip, label, pct),
        modifier
            .semantics { contentDescription = a11y }
            .background(status.color(isSystemInDarkTheme()).copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = status.color(isSystemInDarkTheme()),
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

