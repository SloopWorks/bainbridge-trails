package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.nav.DebugLaunch
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.length_ft
import com.bainbridgetrails.resources.length_m
import com.bainbridgetrails.resources.time_days_ago
import com.bainbridgetrails.resources.time_hours_ago
import com.bainbridgetrails.resources.time_just_now
import com.bainbridgetrails.resources.time_minutes_ago
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** Trail length in the user's units (feet/metres when short, decisions.md P2). */
@Composable
fun trailLengthText(miles: Double, units: Units): String = when (val l = ListFormat.trailLength(miles, units)) {
    is ListFormat.ShortLength.Feet -> stringResource(Res.string.length_ft, l.n)
    is ListFormat.ShortLength.Metres -> stringResource(Res.string.length_m, l.n)
    is ListFormat.ShortLength.Regular -> l.text
}.replace(' ', '\u00A0') // keep "0.6 mi" together when the subtitle wraps

/** "5 min ago" for [thenMs], refreshed every 30 s while shown. */
@Composable
fun ageText(thenMs: Long): String {
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(thenMs) {
        while (true) {
            now = nowMs()
            delay(30_000)
        }
    }
    return when (val a = ListFormat.age(thenMs, now)) {
        ListFormat.Age.JustNow -> stringResource(Res.string.time_just_now)
        is ListFormat.Age.Minutes -> stringResource(Res.string.time_minutes_ago, a.n)
        is ListFormat.Age.Hours -> stringResource(Res.string.time_hours_ago, a.n)
        is ListFormat.Age.Days -> pluralStringResource(Res.plurals.time_days_ago, a.n, a.n)
    }
}

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Pushed list screen: top bar with back + [actions], a [LazyColumn] (max width 600dp, centered) padded for the
 * bottom/side insets, a snackbar host, and an optional [bottomBar] pinned under the list (e.g. the Leaderboard's
 * YouRow). [content] items handle their own horizontal margins (rows run edge to edge).
 */
@Composable
fun ListScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    insetBottom: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LaunchedEffect(Unit) { if (DebugLaunch.scroll > 0) listState.scrollToItem(DebugLaunch.scroll) }
    val bottomInset = if (insetBottom) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding() else 0.dp
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(title, onBack = onBack, actions = actions)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.fillMaxSize().widthIn(max = BtTheme.spacing.contentMaxWidth)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    state = listState,
                    contentPadding = PaddingValues(bottom = if (bottomBar == null) bottomInset + 24.dp else 16.dp),
                    content = content,
                )
            }
            if (bottomBar != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = bottomInset), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = BtTheme.spacing.contentMaxWidth)) { bottomBar() }
                }
            }
        }
        if (snackbar != null) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp, start = 8.dp, end = 8.dp))
        }
    }
}

/** Inset hairline between list groups. */
@Composable
fun ListDivider(start: Dp = BtTheme.spacing.screenH) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}
