package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.DrawableResource

/**
 * Neutral Play fact pill (docs/play/ux.md §2.12): 32dp, `surfaceContainerHigh`, `labelMedium` `onSurfaceVariant`
 * (expiry "Washes away Sunday night", a window, "Found", a tag). [outlined] = the outlined variant (remembrance tag:
 * no fill, 1dp `outlineVariant`). [icon] is decorative, 14dp, tinted [iconTint] (e.g. a sea-glass check on "Found").
 * Non-interactive; speaks [text].
 */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    icon: DrawableResource? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = PillShape
    Row(
        modifier
            .heightIn(min = 32.dp)
            .then(
                if (outlined) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                },
            )
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) BtIcon(icon, null, size = 14.dp, tint = iconTint)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
