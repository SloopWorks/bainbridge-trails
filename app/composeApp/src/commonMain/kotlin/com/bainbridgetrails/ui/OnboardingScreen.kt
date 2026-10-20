package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.onboarding_1_body
import com.bainbridgetrails.resources.onboarding_1_title
import com.bainbridgetrails.resources.onboarding_2_body
import com.bainbridgetrails.resources.onboarding_2_title
import com.bainbridgetrails.resources.onboarding_3_body
import com.bainbridgetrails.resources.onboarding_3_signin_hint
import com.bainbridgetrails.resources.onboarding_3_title
import com.bainbridgetrails.resources.onboarding_done
import com.bainbridgetrails.resources.onboarding_next
import com.bainbridgetrails.resources.onboarding_skip
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** First run (F1): three pages, Skip / Next / Done. No permission prompts here. Illustrations come with the UX spec. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val completePct = (CoverageParams().completionThreshold * 100).roundToInt()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(Res.string.onboarding_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                val (title, body) = when (page) {
                    0 -> stringResource(Res.string.onboarding_1_title) to stringResource(Res.string.onboarding_1_body)
                    1 -> stringResource(Res.string.onboarding_2_title) to stringResource(Res.string.onboarding_2_body, completePct)
                    else -> stringResource(Res.string.onboarding_3_title) to stringResource(Res.string.onboarding_3_body)
                }
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                if (page == 2) Text(stringResource(Res.string.onboarding_3_signin_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                Box(
                    Modifier.padding(4.dp).size(8.dp).background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (pager.currentPage == i) 1f else 0.3f),
                        CircleShape,
                    ),
                )
            }
        }
        val last = pager.currentPage == 2
        Button(
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(56.dp).align(Alignment.CenterHorizontally),
        ) { Text(stringResource(if (last) Res.string.onboarding_done else Res.string.onboarding_next)) }
    }
}
