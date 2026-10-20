package com.bainbridgetrails.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.celebrate_a11y_announcement
import com.bainbridgetrails.resources.celebrate_a11y_announcement_multi
import com.bainbridgetrails.resources.celebrate_community
import com.bainbridgetrails.resources.celebrate_continue
import com.bainbridgetrails.resources.celebrate_eyebrow
import com.bainbridgetrails.resources.celebrate_eyebrow_multi
import com.bainbridgetrails.resources.celebrate_island_body
import com.bainbridgetrails.resources.celebrate_island_a11y
import com.bainbridgetrails.resources.celebrate_island_eyebrow
import com.bainbridgetrails.map.IslandCredit
import com.bainbridgetrails.map.islandCredit
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.resources.celebrate_island_label
import com.bainbridgetrails.resources.celebrate_island_title
import com.bainbridgetrails.resources.celebrate_more
import com.bainbridgetrails.resources.celebrate_trails_count
import com.bainbridgetrails.resources.celebrate_view_trail
import com.bainbridgetrails.resources.celebrate_walked
import com.bainbridgetrails.record.FlowBackHandler
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Community counts below this are hidden (decisions.md TL-4). */
private const val COMMUNITY_MIN = 5

/** Full timeline length (ux.md §3.6). */
private const val TIMELINE_MS = 2400

/**
 * Celebration (F6 step 3, ux.md §3.6): one full-screen page for every trail this walk completed (TL-4: one screen,
 * plural variant). The trail's own geometry draws itself, water lines ripple out once, the island % counts up;
 * reduced motion shows the final frame. [sessionId] is the walk (or a debug key) whose results show underneath.
 */
@Composable
fun CelebrationScreen(
    graph: AppGraph.Installed,
    sessionId: String,
    trailIds: List<String>,
    onContinue: () -> Unit,
    onViewTrail: (String) -> Unit,
    island: Boolean = false,
    preview: Boolean = false,
) {
    FlowBackHandler { onContinue() }
    DatasetGate { data ->
        // Fresh numbers: the walk's coverage was written just before this opened.
        val snapshot by produceState(graph.progress.snapshot.value, graph) {
            value = runCatching { graph.progress.refreshNow() }.getOrNull() ?: value
        }
        val islandBefore by produceState<Double?>(null, sessionId) {
            val done = runCatching { graph.controller().await() }.getOrNull()?.state?.value as? RecordingState.Done
            value = done?.summary?.takeIf { it.sessionId == sessionId }?.islandBefore
        }
        val stats by graph.trailStats.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { runCatching { graph.trailStats.get() } }
        val s = snapshot ?: return@DatasetGate
        val trails = remember(trailIds, data) {
            trailIds.mapNotNull { id -> data.dataset.trails.firstOrNull { it.id == id } }.sortedByDescending { it.computedLengthMi }
        }
        if (trails.isEmpty() && !island) return@DatasetGate
        val countedIds = remember(data) { data.dataset.trails.filter { data.dataset.islandCredit(it.id) != IslandCredit.NONE }.mapTo(HashSet()) { it.id } }
        val whole = remember(s, island, trailIds) { IslandCelebration.show(island, s, trailIds, countedIds) }
        val geometry by produceState<IslandGeometry?>(null, data, whole) { if (whole) value = IslandGeometry.forDataset(data.dataset) }
        if (whole && geometry == null) return@DatasetGate // a frame or two while the coastline loads
        // Debug preview: final numbers whatever this phone's coverage is.
        val shown = if (preview) s.copy(islandFraction = 1.0, trailsCompleted = s.trailCount) else s
        val community = trails.singleOrNull()?.let { stats.forTrail(it.id)?.completedCount }?.takeIf { it >= COMMUNITY_MIN }
        CelebrationContent(
            data, shown, trails.map { it.id to it.name }, if (preview) null else islandBefore, community, onContinue, onViewTrail,
            islandArt = if (whole) geometry else null,
        )
    }
}

@Composable
private fun CelebrationContent(
    data: TrailData,
    s: ProgressSnapshot,
    trails: List<Pair<String, String>>,
    islandBefore: Double?,
    community: Int?,
    onContinue: () -> Unit,
    onViewTrail: (String) -> Unit,
    islandArt: IslandGeometry? = null,
) {
    val reduced = BtTheme.motion.reduced
    val haptics = LocalHapticFeedback.current
    val clock = remember { Animatable(if (reduced) TIMELINE_MS.toFloat() else 0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        launch { fade.animateTo(1f, tween(150)) }
        if (reduced) {
            clock.snapTo(TIMELINE_MS.toFloat())
            haptics.performHapticFeedback(HapticFeedbackType.LongPress) // haptics stay under reduced motion
        } else {
            launch { delay(1300); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            clock.animateTo(TIMELINE_MS.toFloat(), tween(TIMELINE_MS, easing = LinearEasing))
        }
    }
    val t = clock.value

    val single = trails.size == 1
    val island = s.islandFraction
    val wholeIsland = islandArt != null
    val names = trails.map { it.second }
    val shownNames = names.take(3).joinToString("\n") + if (names.size > 3) "\n" + stringResource(Res.string.celebrate_more, names.size - 3) else ""
    val eyebrow = when {
        wholeIsland -> stringResource(Res.string.celebrate_island_eyebrow)
        single -> stringResource(Res.string.celebrate_eyebrow)
        else -> pluralStringResource(Res.plurals.celebrate_eyebrow_multi, trails.size, trails.size)
    }
    val walked = if (single) stringResource(Res.string.celebrate_walked, Format.percentWhole(s.trail(trails[0].first)?.fraction ?: 1.0)) else null
    val trailsLine = stringResource(Res.string.celebrate_trails_count, s.trailsCompleted, s.trailCount)
    val communityLine = community?.let { pluralStringResource(Res.plurals.celebrate_community, it, it) }
    val announcement = if (wholeIsland) {
        stringResource(Res.string.celebrate_island_a11y, Format.percent(island), s.trailsCompleted, s.trailCount)
    } else buildString {
        append(
            if (single) stringResource(Res.string.celebrate_a11y_announcement, names[0], Format.percent(island), s.trailsCompleted, s.trailCount)
            else stringResource(Res.string.celebrate_a11y_announcement_multi, trails.size, names.joinToString(", "), Format.percent(island)),
        )
        communityLine?.let { append(' ').append(it) }
    }

    val complete = BtTheme.colors.complete
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).alpha(fade.value),
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(horizontal = BtTheme.spacing.screenH, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.heightIn(min = 8.dp))
            if (islandArt != null) IslandCelebrationArt(islandArt, t)
            else TrailGlyph(data, trails.map { it.first }, t, reduced, complete.accent, complete.container)
            Spacer(Modifier.heightIn(min = 8.dp))
            // Heading node: the whole announcement, read once on appear (assertive live region, ux.md §3.6).
            val rise = phase(t, 600, 900)
            Column(
                Modifier.fillMaxWidth().widthIn(max = 480.dp)
                    .graphicsLayer { alpha = rise; translationY = (1 - rise) * 8.dp.toPx() }
                    .clearAndSetSemantics { contentDescription = announcement; heading(); liveRegion = LiveRegionMode.Assertive },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
                    color = complete.accent,
                    textAlign = TextAlign.Center,
                )
                if (wholeIsland) {
                    // P1-9: Fraunces headline (upright), the body line under it.
                    Text(stringResource(Res.string.celebrate_island_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    Text(
                        stringResource(Res.string.celebrate_island_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else Text(
                    shownNames,
                    style = (if (single) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium).copy(fontStyle = FontStyle.Italic),
                    textAlign = TextAlign.Center,
                    maxLines = if (single) 3 else 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!wholeIsland) walked?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
            Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                // Counter old → new (900–1700 ms); only when the walk's "before" value is known.
                val p = BtEasing.emphasizedDecelerate.transform(phase(t, 900, 1700))
                val value = if (islandBefore != null && !reduced) islandBefore + (island - islandBefore) * p else island
                IslandCounter(Format.percent(value), widest = listOfNotNull(islandBefore, island).map { Format.percent(it) }.maxBy { it.length })
                Text(stringResource(Res.string.celebrate_island_label), style = MaterialTheme.typography.bodyMedium)
            }
            val tail = phase(t, 1700, 2000)
            Column(
                Modifier.alpha(tail).clearAndSetSemantics {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(trailsLine, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                communityLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
            // Enabled from t = 0: the animation never blocks (only its look fades in).
            Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).graphicsLayer { alpha = max(tail, 0.0001f) }) {
                PrimaryButton(stringResource(Res.string.celebrate_continue), onClick = onContinue, modifier = Modifier.fillMaxWidth())
                if (single && !wholeIsland) {
                    TextButton(onClick = { onViewTrail(trails[0].first) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.celebrate_view_trail))
                    }
                }
            }
        }
    }
}

/** 0..1 progress of [t] through [from, to] ms. */
private fun phase(t: Float, from: Int, to: Int): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/**
 * statHero in a fixed-width box sized to the widest value it shows and end-aligned, so proportional figures cannot
 * reflow the layout while counting (ux.md §3.6); capped at 83sp (ux.md §5.3).
 */
@Composable
private fun IslandCounter(text: String, widest: String) {
    val base = BtTheme.type.statHero
    val scale = LocalDensity.current.fontScale
    val style = if (base.fontSize.value * scale > 83f) base.copy(fontSize = (83f / scale).sp, lineHeight = (88f / scale).sp) else base
    Box(contentAlignment = Alignment.CenterEnd) {
        Text(widest.replace(Regex("[0-9]"), "0"), style = style, modifier = Modifier.alpha(0f))
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The trails' segments as polylines in a unit-free local projection, chained so the draw runs along the trail. */
private fun glyphLines(data: TrailData, trailIds: List<String>): List<List<Offset>> {
    val byId = data.dataset.segments.associateBy { it.id }
    val raw = trailIds.flatMap { id -> data.dataset.trails.firstOrNull { it.id == id }?.segmentIds.orEmpty() }
        .distinct().mapNotNull { byId[it]?.coords?.takeIf { c -> c.size >= 2 } }
    if (raw.isEmpty()) return emptyList()
    val (lon0, lat0) = raw.first().first().let { it[0] to it[1] }
    val k = cos(lat0 * PI / 180)
    // Relative to the first point in doubles first: absolute degrees in Float would lose ~1 m.
    val lines = raw.map { c -> c.map { Offset(((it[0] - lon0) * k).toFloat(), (lat0 - it[1]).toFloat()) } }.toMutableList()
    // Greedy chain: the nearest unused endpoint next, reversed as needed (like scripts/emu-route.sh).
    val out = mutableListOf(lines.removeAt(0))
    while (lines.isNotEmpty()) {
        val end = out.last().last()
        var best = 0
        var bestD = Float.MAX_VALUE
        var flip = false
        lines.forEachIndexed { i, l ->
            val a = (l.first() - end).getDistanceSquared()
            val b = (l.last() - end).getDistanceSquared()
            if (a < bestD) { bestD = a; best = i; flip = false }
            if (b < bestD) { bestD = b; best = i; flip = true }
        }
        val l = lines.removeAt(best)
        out += if (flip) l.reversed() else l
    }
    return out
}

/**
 * Decorative glyph (ux.md §3.6): radial `complete.container` wash, a dotted ghost of the trail, the trail drawing
 * itself in `complete.accent` (6dp, pulse to 8dp), then three water lines (offset outlines) rippling out once.
 */
@Composable
private fun TrailGlyph(data: TrailData, trailIds: List<String>, t: Float, reduced: Boolean, accent: Color, container: Color) {
    // One glyph per trail (up to 3, side by side): trails far apart would shrink to dots in one shared frame.
    val groups = remember(trailIds, data) { trailIds.take(3).map { glyphLines(data, listOf(it)) }.filter { it.isNotEmpty() } }
    val ghost = BtTheme.colors.notStarted.accent
    val water = BtTheme.colors.waterLine
    Canvas(
        Modifier.size(280.dp, 220.dp).clearAndSetSemantics {}
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(container.copy(alpha = 0.6f), container.copy(alpha = 0f)), center, size.maxDimension * 0.7f),
                    radius = size.maxDimension * 0.7f,
                )
            },
    ) {
        if (groups.isEmpty()) return@Canvas
        val n = groups.size
        val gap = 24.dp.toPx()
        val cellW2 = if (n == 1) 200.dp.toPx() else (260.dp.toPx() - gap * (n - 1)) / n
        val cellH = if (n == 1) 160.dp.toPx() else 120.dp.toPx()
        val paths = groups.flatMapIndexed { i, g ->
            val cx = center.x + (i - (n - 1) / 2f) * (cellW2 + gap)
            fit(g, cellW2, cellH, Offset(cx, center.y))
        }
        val lengths = paths.map { PathMeasure().apply { setPath(it, false) }.length }
        val total = lengths.sum()
        val sw = 6.dp.toPx()

        // Water lines (behind the glyph).
        if (reduced) {
            waterLine(paths, sw, 20.dp.toPx(), water.copy(alpha = 0.8f))
        } else {
            for (i in 0 until 3) {
                val p = phase(t, 1200 + 150 * i, TIMELINE_MS) // staggered starts, all gone by the end
                if (p <= 0f || p >= 1f) continue
                waterLine(paths, sw, (10 * (i + 1)).dp.toPx() * p, water.copy(alpha = 0.8f * (1 - p)))
            }
        }

        // Ghost: the not-started dotted pattern, which the solid line then covers ("dots become solid").
        val dots = Stroke(sw * 0.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, sw * 1.4f)))
        if (!reduced && t < 1300) paths.forEach { drawPath(it, ghost.copy(alpha = 0.7f), style = dots) }

        val drawn = total * BtEasing.emphasizedDecelerate.transform(phase(t, 100, 1300))
        val pulse = if (reduced) 0f else sin(PI * phase(t, 1100, 1400)).toFloat()
        val stroke = Stroke(sw + 2.dp.toPx() * pulse, cap = StrokeCap.Round, join = StrokeJoin.Round)
        var start = 0f
        paths.forEachIndexed { i, path ->
            val len = lengths[i]
            when {
                drawn >= start + len -> drawPath(path, accent, style = stroke)
                drawn > start -> {
                    val part = Path()
                    PathMeasure().apply { setPath(path, false) }.getSegment(0f, drawn - start, part, true)
                    drawPath(part, accent, style = stroke)
                }
            }
            start += len
        }
    }
}

/** An outline [offset] outside a [sw]-wide stroke of [paths]: a wide stroke with its middle cleared, in its own layer. */
private fun DrawScope.waterLine(paths: List<Path>, sw: Float, offset: Float, color: Color) {
    val line = 1.5.dp.toPx()
    val outer = sw + 2 * offset + line
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        paths.forEach { drawPath(it, color, style = Stroke(outer, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        paths.forEach { drawPath(it, Color.Black, style = Stroke(outer - 2 * line, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear) }
        canvas.restore()
    }
}

/** Scales [lines] to fit a [w]×[h] box centred on [at] (aspect kept). */
private fun fit(lines: List<List<Offset>>, w: Float, h: Float, at: Offset): List<Path> {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (l in lines) for (p in l) { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
    val bw = max(maxX - minX, 1e-9f)
    val bh = max(maxY - minY, 1e-9f)
    val scale = min(w / bw, h / bh)
    val ox = at.x - bw * scale / 2
    val oy = at.y - bh * scale / 2
    return lines.map { l ->
        Path().apply {
            l.forEachIndexed { i, p ->
                val x = ox + (p.x - minX) * scale
                val y = oy + (p.y - minY) * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
}
