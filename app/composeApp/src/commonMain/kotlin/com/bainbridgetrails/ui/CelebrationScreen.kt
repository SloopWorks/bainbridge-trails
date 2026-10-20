package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.celebrate_body
import com.bainbridgetrails.resources.celebrate_continue
import com.bainbridgetrails.resources.celebrate_more
import com.bainbridgetrails.resources.celebrate_title
import com.bainbridgetrails.resources.celebrate_title_multi
import com.bainbridgetrails.resources.celebrate_view_trail
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Celebration (F6 step 3). Placeholder: copy + buttons; confetti/reduced-motion/announcement come with the UX spec. */
@Composable
fun CelebrationScreen(graph: AppGraph.Installed, trailIds: List<String>, onContinue: () -> Unit, onViewTrail: (String) -> Unit) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    DatasetGate { data ->
        val names = trailIds.mapNotNull { id -> data.dataset.trails.firstOrNull { it.id == id }?.name }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = if (names.size == 1) stringResource(Res.string.celebrate_title, names.single())
            else pluralStringResource(Res.plurals.celebrate_title_multi, names.size, names.size)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (names.size > 1) {
                Text(names.take(3).joinToString("\n") + if (names.size > 3) "\n" + stringResource(Res.string.celebrate_more, names.size - 3) else "", textAlign = TextAlign.Center)
            }
            snapshot?.let { s ->
                Text(stringResource(Res.string.celebrate_body, Format.percent(s.islandFraction), s.trailsCompleted, s.trailCount), textAlign = TextAlign.Center)
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(Res.string.celebrate_continue)) }
            if (trailIds.size == 1) OutlinedButton(onClick = { onViewTrail(trailIds.single()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.celebrate_view_trail)) }
        }
    }
}
