package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.error_dataset_body
import com.bainbridgetrails.resources.error_dataset_title
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.components.ErrorState
import com.bainbridgetrails.ui.components.LoadingState
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** Shows loading / error+retry until the dataset is parsed (F0 step 2), then [content]. */
@Composable
fun DatasetGate(modifier: Modifier = Modifier, content: @Composable (TrailData) -> Unit) {
    val state by AppGraph.datasets.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is Loadable.Ready -> content(s.value)
        Loadable.Loading -> LoadingState(modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        is Loadable.Failed -> Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            ErrorState(
                title = stringResource(Res.string.error_dataset_title),
                body = stringResource(Res.string.error_dataset_body),
                retryLabel = stringResource(Res.string.common_retry),
                onRetry = { AppGraph.datasets.load() },
            )
        }
    }
}

/** Pushed-screen chrome: [BtTopBar] with back + title, safe-area padding, screen margins. */
@Composable
fun PushedScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        BtTopBar(title, onBack = onBack)
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = BtTheme.spacing.screenH),
            content = content,
        )
    }
}
