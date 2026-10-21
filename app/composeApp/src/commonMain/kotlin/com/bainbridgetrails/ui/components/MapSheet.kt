package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.map_close
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Drag distance (dp) that commits a swipe on a map sheet. */
private const val SWIPE_COMMIT_DP = 56f

object MapSheetDefaults {
    /** Today's map sheets (trail peek, chooser): no cap, the sheet wraps its content. */
    const val MaxHeightFraction = 1f

    /** Play's EncounterSheet (docs/play/ux.md §2.3): 60 % of the map height, content scrolls inside beyond it. */
    const val EncounterMaxHeightFraction = 0.6f
}

/**
 * The sheet's height cap in px for a parent that offers [availablePx] (null = unbounded), or null for no cap
 * ([maxHeightFraction] 1 = today's behaviour, or nothing to take a fraction of).
 */
internal fun mapSheetMaxHeight(availablePx: Int?, maxHeightFraction: Float): Int? {
    require(maxHeightFraction > 0f && maxHeightFraction <= 1f) { "maxHeightFraction must be in (0, 1], was $maxHeightFraction" }
    if (availablePx == null || maxHeightFraction >= 1f) return null
    return (availablePx * maxHeightFraction).toInt()
}

/**
 * Non-modal sheet over the map (ux.md §3.2): rounded top, drag handle, one detent. Swipe down → [onDismiss];
 * swipe up → [onExpand] (when given). The map behind stays interactive. Below 1, [maxHeightFraction] caps the sheet
 * at that share of the height its parent offers (the map) and the content scrolls under the fixed handle
 * (docs/play/ux.md §2.3: EncounterSheet uses [MapSheetDefaults.EncounterMaxHeightFraction]).
 */
@Composable
fun MapSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    padContent: Boolean = true,
    maxHeightFraction: Float = MapSheetDefaults.MaxHeightFraction,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    val dismissLabel = stringResource(Res.string.map_close)
    val capped = maxHeightFraction < 1f
    Column(
        modifier
            .layout { measurable, constraints ->
                val cap = mapSheetMaxHeight(if (constraints.hasBoundedHeight) constraints.maxHeight else null, maxHeightFraction)
                val c = if (cap == null) constraints else constraints.copy(minHeight = min(constraints.minHeight, cap), maxHeight = cap)
                val p = measurable.measure(c)
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .fillMaxWidth()
            .offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
            .shadow(8.dp, SheetShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, SheetShape)
            // Swallow taps so they never fall through to the map.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .draggable(
                rememberDraggableState { delta -> drag += delta },
                Orientation.Vertical,
                onDragStopped = {
                    val dp = drag / density.density
                    drag = 0f
                    when {
                        dp > SWIPE_COMMIT_DP -> onDismiss()
                        dp < -SWIPE_COMMIT_DP -> onExpand?.invoke()
                    }
                },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true }) }
            .padding(horizontal = if (padContent) BtTheme.spacing.screenH else 0.dp)
            .padding(bottom = if (padContent) BtTheme.spacing.lg else BtTheme.spacing.sm),
    ) {
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
        }
        if (capped) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        } else {
            content()
        }
    }
}
