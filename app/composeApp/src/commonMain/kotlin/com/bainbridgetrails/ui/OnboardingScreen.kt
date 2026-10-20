package com.bainbridgetrails.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.resources.roads_onboarding_2_milestones
import com.bainbridgetrails.ui.components.milestoneDistance
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.nav.DebugLaunch
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.onboarding_1_body
import com.bainbridgetrails.resources.onboarding_1_title
import com.bainbridgetrails.resources.onboarding_2_body
import com.bainbridgetrails.resources.onboarding_2_title
import com.bainbridgetrails.resources.onboarding_3_signin_hint
import com.bainbridgetrails.resources.onboarding_3_title
import com.bainbridgetrails.resources.onboarding_done
import com.bainbridgetrails.resources.onboarding_next
import com.bainbridgetrails.resources.onboarding_page_a11y
import com.bainbridgetrails.resources.onboarding_privacy_body
import com.bainbridgetrails.resources.onboarding_skip
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.IslandChart
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.StatusChip
import com.bainbridgetrails.ui.components.WaterLines
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val PAGES = 3

/** First run (F1, ux.md §3.1): three pages, Skip / Next / Start exploring. No permission prompts here. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(initialPage = DebugLaunch.page.coerceIn(0, PAGES - 1)) { PAGES }
    val scope = rememberCoroutineScope()
    val completePct = (CoverageParams().completionThreshold * 100).roundToInt()
    val last = pager.currentPage == PAGES - 1
    val goal = LocalGoal.current
    // RD-4/RP-20: the "over N miles" number comes from the dataset (blank for the moment before it has loaded).
    val datasetState by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val islandTotalM = (datasetState as? Loadable.Ready)?.value?.dataset?.islandTotalM
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = BtTheme.spacing.contentMaxWidth)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = BtTheme.spacing.xs), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone, modifier = Modifier.alpha(if (last) 0f else 1f), enabled = !last) { Text(stringResource(Res.string.onboarding_skip)) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = BtTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        when (page) {
                            0 -> IslandIllustration(Modifier.fillMaxSize(), roads = goal.isRoads)
                            1 -> if (goal.isRoads) RoadGridFillIllustration(pager.currentPage == 1, Modifier.fillMaxWidth())
                            else TrailFillIllustration(pager.currentPage == 1, Modifier.fillMaxWidth())
                            else -> PrivacyIllustration(Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(BtTheme.spacing.xl))
                    val (title, body) = when (page) {
                        0 -> goal.string(Res.string.onboarding_1_title) to (islandTotalM?.let { goal.string(Res.string.onboarding_1_body, goal.islandTotalOver(it)) } ?: "")
                        1 -> stringResource(Res.string.onboarding_2_title) to goal.string(Res.string.onboarding_2_body, completePct)
                        else -> stringResource(Res.string.onboarding_3_title) to goal.string(Res.string.onboarding_privacy_body)
                    }
                    Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    Text(body, Modifier.fillMaxWidth().padding(top = BtTheme.spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (page == 1 && goal.isRoads) {
                        // product.md §3.2: the milestone promise under the fill-in page (default unit: miles).
                        Text(
                            stringResource(Res.string.roads_onboarding_2_milestones, milestoneDistance(5, Units.MILES)),
                            Modifier.fillMaxWidth().padding(top = BtTheme.spacing.md),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (page == 2) {
                        Text(
                            stringResource(Res.string.onboarding_3_signin_hint),
                            Modifier.fillMaxWidth().padding(top = BtTheme.spacing.md),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            val pageA11y = stringResource(Res.string.onboarding_page_a11y, pager.currentPage + 1, PAGES)
            Row(
                Modifier.fillMaxWidth().padding(vertical = BtTheme.spacing.lg).clearAndSetSemantics { contentDescription = pageA11y },
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGES) { i ->
                    val on = pager.currentPage == i
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(width = if (on) 20.dp else 8.dp, height = 8.dp)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, PillShape),
                    )
                }
            }
            PrimaryButton(
                stringResource(if (last) Res.string.onboarding_done else Res.string.onboarding_next),
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH).padding(bottom = BtTheme.spacing.lg),
            )
        }
    }
}

/** Page 1: the real island network, trails shown in all four statuses (decorative). Water lines until data is ready. */
@Composable
private fun IslandIllustration(modifier: Modifier, roads: Boolean) {
    val state by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val data = (state as? Loadable.Ready)?.value
    val built by produceState<Pair<IslandGeometry, Map<String, TrailStatus>>?>(null, data) {
        if (data == null) return@produceState
        val g = IslandGeometry.forDataset(data.dataset)
        value = withContext(Dispatchers.Default) {
            // Roads (docs/roads/ux.md §7 P1): the whole counted plat not started, "a little colored in" around
            // Winslow (segments within 1.2 km of the ferry terminal), so it reads as a real island of streets.
            if (roads) return@withContext g to OnboardingArt.winslowDemo(data.dataset)
            // Every named trail gets a status in turn, so the drawing shows the whole legend.
            val cycle = listOf(TrailStatus.COMPLETE, TrailStatus.PARTWAY, TrailStatus.NOT_STARTED, TrailStatus.NEARLY_DONE, TrailStatus.NOT_STARTED)
            val status = HashMap<String, TrailStatus>()
            data.dataset.trails.forEachIndexed { i, t -> t.segmentIds.forEach { s -> status.getOrPut(s) { cycle[i % cycle.size] } } }
            g to status.filterValues { it != TrailStatus.NOT_STARTED }
        }
    }
    val water = MaterialTheme.colorScheme.background
    val b = built
    if (b == null) WaterLines(modifier.size(200.dp)) else IslandChart(b.first, b.second, water, "", modifier.clearAndSetSemantics {}, land = MaterialTheme.colorScheme.surfaceContainerHigh)
}

/** Page 2: one trail filling in — dotted → dashed → solid with casing → thick violet — over the four status chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrailFillIllustration(visible: Boolean, modifier: Modifier) {
    val colors = BtTheme.colors
    val reduced = BtTheme.motion.reduced
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(visible) { if (visible && !reduced) { progress.snapTo(0f); progress.animateTo(1f, tween(1400)) } }
    Column(modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xl)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width; val h = size.height
            val path = Path().apply {
                moveTo(w * 0.04f, h * 0.78f)
                cubicTo(w * 0.22f, h * 0.10f, w * 0.40f, h * 0.95f, w * 0.56f, h * 0.45f)
                cubicTo(w * 0.70f, h * 0.05f, w * 0.84f, h * 0.70f, w * 0.96f, h * 0.22f)
            }
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            fun part(from: Float, to: Float): Path = Path().also { pm.getSegment(len * from, len * to, it, true) }
            val p = progress.value
            // Quarters in map order; each one appears as the animation reaches it.
            val q = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            fun shown(i: Int) = ((p * 4) - i).coerceIn(0f, 1f)
            drawPath(path, colors.notStarted.line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 9.dp.toPx()))))
            if (shown(1) > 0) drawPath(part(q[1], q[1] + 0.25f * shown(1)), colors.partway.line, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx()))))
            if (shown(2) > 0) {
                val s = part(q[2], q[2] + 0.25f * shown(2))
                drawPath(s, colors.nearlyDoneCasing, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                drawPath(s, colors.nearlyDone.line, style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
            }
            if (shown(3) > 0) drawPath(part(q[3], q[3] + 0.25f * shown(3)), colors.complete.line, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            TrailStatus.entries.forEach { StatusChip(it) }
        }
    }
}

/** Page 3: a phone with a lock over a small island (decorative). */
@Composable
private fun PrivacyIllustration(modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val screen = MaterialTheme.colorScheme.surfaceContainerLow
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        WaterLines(Modifier.size(260.dp))
        Canvas(Modifier.size(width = 120.dp, height = 210.dp)) {
            val r = CornerRadius(22.dp.toPx())
            drawRoundRect(screen, cornerRadius = r)
            drawRoundRect(outline, cornerRadius = r, style = Stroke(3.dp.toPx()))
            drawRoundRect(outline, Offset(size.width * 0.36f, 9.dp.toPx()), Size(size.width * 0.28f, 5.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        Box(
            Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, PillShape),
            contentAlignment = Alignment.Center,
        ) { BtIcon(BtIcons.Lock, null, size = 32.dp, tint = MaterialTheme.colorScheme.onPrimary) }
    }
}
