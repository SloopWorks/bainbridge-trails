package com.bainbridgetrails.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.DrawableResource

class BarItem(
    val label: String,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
    val selected: Boolean,
    val onClick: () -> Unit,
    /** Overrides [label] for screen readers. */
    val contentDescription: String? = null,
)

/**
 * App bottom bar (ux.md §2.2): `surfaceContainer`, 80dp + navigation-bar inset, slots weighted 1·1·1.2·1:
 * [start] tabs, the raised [RecordDisc] (rises [BtSpacing.recordFabRise] above the bar), [end] tabs.
 * The composable's own height includes the rise (transparent, touches pass through) so the disc stays tappable;
 * pad content by [bottomBarContentPadding], not by this composable's height.
 */
@Composable
fun BtBottomBar(
    start: List<BarItem>,
    end: List<BarItem>,
    recordLabel: String,
    recording: Boolean,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = BtTheme.spacing
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(sp.recordFabRise))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
            Row(
                Modifier.fillMaxWidth().height(sp.bottomBarHeight)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            ) {
                start.forEach { BarSlot(it, Modifier.weight(1f)) }
                Box(Modifier.weight(1.2f).fillMaxHeight()) {
                    Column(
                        Modifier.align(Alignment.TopCenter).offset(y = -sp.recordFabRise),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RecordDisc(recording, onRecord)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            recordLabel,
                            Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.labelMedium,
                            color = if (recording) BtTheme.colors.recording else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                end.forEach { BarSlot(it, Modifier.weight(1f)) }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun BarSlot(item: BarItem, modifier: Modifier) {
    val spec = BtTheme.motion.tweenOrSnap<Color>(BtDuration.short4)
    val indicator by animateColorAsState(if (item.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, spec)
    val fg = if (item.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = item.selected,
                onClick = item.onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 40.dp),
            )
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(64.dp, 32.dp).background(indicator, PillShape), contentAlignment = Alignment.Center) {
            BtIcon(if (item.selected) item.selectedIcon else item.icon, item.contentDescription, tint = fg)
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
