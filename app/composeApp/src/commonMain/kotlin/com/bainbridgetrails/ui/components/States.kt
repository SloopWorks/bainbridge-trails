package com.bainbridgetrails.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_loading_trails
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.EmptyMotif
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.stringResource

enum class EmptyKind { Empty, Error, Offline }

/**
 * Centered empty / error / offline message (ux.md §4). Empty shows the water-lines motif; Offline `cloud_off`;
 * Error the error icon. [action] = (label, onClick): a filled button for Error (Retry), a text button otherwise.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    kind: EmptyKind = EmptyKind.Empty,
    action: Pair<String, () -> Unit>? = null,
    compact: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.xl, vertical = if (compact) BtTheme.spacing.lg else BtTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) {
                when (kind) {
                    EmptyKind.Empty -> if (BtTheme.brand.emptyMotif == EmptyMotif.RoadGrid) RoadGridGlyph(Modifier.size(96.dp)) else WaterLines(Modifier.size(96.dp))
                    EmptyKind.Offline -> IconBadge { BtIcon(BtIcons.CloudOff, null, size = 32.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    EmptyKind.Error -> IconBadge(MaterialTheme.colorScheme.errorContainer) {
                        BtIcon(BtIcons.Error, null, size = 32.dp, tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(BtTheme.spacing.lg))
            }
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(BtTheme.spacing.xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(BtTheme.spacing.lg))
                val (label, onClick) = action
                if (kind == EmptyKind.Error) SecondaryButton(label, onClick) else TextButton(onClick = onClick) { Text(label) }
            }
        }
    }
}

/** Error variant of [EmptyState] with a Retry action (never raw exception text). */
@Composable
fun ErrorState(title: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier, body: String? = null) =
    EmptyState(title, modifier, body, EmptyKind.Error, retryLabel to onRetry)

@Composable
private fun IconBadge(color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable () -> Unit) {
    Box(Modifier.size(72.dp).background(color, PillShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { content() }
}

/**
 * Full-screen first-launch loader (DatasetLoading, ux.md §3): water-lines motif, [text], indeterminate bar.
 * Reduced motion: no moving indicator.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String = stringResource(Res.string.common_loading_trails)) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg)) {
            WaterLines(Modifier.size(120.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (BtTheme.motion.reduced) {
                Spacer(Modifier.height(4.dp))
            } else {
                LinearProgressIndicator(Modifier.widthIn(max = 160.dp).fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

/** Placeholder block for skeleton rows (`surfaceContainerHigh`); shimmers unless reduced motion. */
@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
    val reduced = BtTheme.motion.reduced
    val t = if (reduced) 0f else {
        val tr = rememberInfiniteTransition()
        val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart))
        v
    }
    Box(
        modifier.size(width, height).background(base, shape).clearAndSetSemantics {}.drawWithContent {
            drawContent()
            if (!reduced) {
                val w = size.width
                val x = -w + 2 * w * t
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = x, endX = x + w))
            }
        },
    )
}

/** Skeleton TrailRow (ring + two lines). */
@Composable
fun SkeletonTrailRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(BtTheme.spacing.rowHeight).padding(horizontal = BtTheme.spacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
    ) {
        SkeletonBlock(40.dp, 40.dp, shape = PillShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(180.dp, 14.dp)
            SkeletonBlock(120.dp, 12.dp)
        }
    }
}

/** Inline offline line: cloud_off + text on a `surfaceContainerHigh` pill. */
@Composable
fun OfflineNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BtIcon(BtIcons.CloudOff, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card asking to sign in (secondaryContainer), with an optional dismiss. */
@Composable
fun SignInPrompt(text: String, signInLabel: String, onSignIn: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null, dismissLabel: String? = null) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium).padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Text(text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onDismiss != null && dismissLabel != null) TextButton(onClick = onDismiss) { Text(dismissLabel) }
            TextButton(onClick = onSignIn) { Text(signInLabel) }
        }
    }
}

/**
 * Signature motif (ux.md §0): a small island with three concentric "water lines", like the Topo coastline.
 * Decorative; use only in empty states, Me hero and Celebration.
 */
@Composable
fun WaterLines(modifier: Modifier = Modifier, color: Color = BtTheme.colors.waterLine, land: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val d = size.minDimension
        val island = islandPath(d)
        val c = Offset(size.width / 2, size.height / 2)
        val rings = listOf(1.0f to 1f, 1.3f to 0.75f, 1.6f to 0.5f, 1.9f to 0.28f)
        for ((i, pair) in rings.withIndex()) {
            val (s, a) = pair
            scale(s * 0.52f, pivot = c) {
                translate(c.x - d / 2, c.y - d / 2) {
                    if (i == 0) drawPath(island, land)
                    drawPath(island, color.copy(alpha = a), style = Stroke(1.5.dp.toPx() / (s * 0.52f)))
                }
            }
        }
    }
}

/** A Bainbridge-ish blob (long north–south, notched west side), in a d×d box. */
private fun islandPath(d: Float): Path {
    val pts = listOf(
        0.50f to 0.06f, 0.62f to 0.14f, 0.66f to 0.30f, 0.74f to 0.42f, 0.70f to 0.58f,
        0.76f to 0.74f, 0.64f to 0.92f, 0.48f to 0.94f, 0.38f to 0.82f, 0.30f to 0.70f,
        0.40f to 0.58f, 0.28f to 0.46f, 0.34f to 0.30f, 0.40f to 0.16f,
    ).map { (x, y) -> Offset(((x - 0.52f) * 1.45f + 0.5f) * d, y * d) }
    return Path().apply {
        val n = pts.size
        val mid = { a: Offset, b: Offset -> Offset((a.x + b.x) / 2, (a.y + b.y) / 2) }
        moveTo(mid(pts[n - 1], pts[0]).x, mid(pts[n - 1], pts[0]).y)
        for (i in 0 until n) {
            val p = pts[i]
            val m = mid(p, pts[(i + 1) % n])
            quadraticTo(p.x, p.y, m.x, m.y)
        }
        close()
    }
}
