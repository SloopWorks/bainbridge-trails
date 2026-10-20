package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.search_clear_a11y
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import com.bainbridgetrails.ui.theme.of
import org.jetbrains.compose.resources.stringResource

/** Docked search field (ux.md §3.3): 56dp pill on `surfaceContainerHigh`, search icon, 48dp clear button, IME Search hides the keyboard. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
        decorationBox = { inner ->
            Row(
                Modifier.height(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtIcon(BtIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        BtIcon(BtIcons.Close, stringResource(Res.string.search_clear_a11y), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** Status filter chip: glyph + label; selected uses that status's `container`/`onContainer` (allowed: it is about status). */
@Composable
fun StatusFilterChip(status: TrailStatus, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = BtTheme.colors.of(status)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(status.label)) },
        modifier = modifier,
        shape = PillShape,
        leadingIcon = { StatusGlyph(status, size = 16.dp, color = if (selected) c.onContainer else c.accent) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = c.container,
            selectedLabelColor = c.onContainer,
            selectedLeadingIconColor = c.onContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = c.accent.copy(alpha = 0.5f),
            selectedBorderWidth = 1.dp,
        ),
    )
}

/** Neutral chip opening a menu (Park ▾, Sort: Name ▾). [active] = a non-default value is chosen. */
@Composable
fun DropdownChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        shape = PillShape,
        trailingIcon = { BtIcon(BtIcons.ExpandMore, null, size = 18.dp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = active, borderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Horizontally scrolling row: the four status chips (multi-select) then [trailing] chips (Park, Sort). */
@Composable
fun FilterChipRow(
    selected: Set<TrailStatus>,
    onToggle: (TrailStatus) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in TrailStatus.entries) StatusFilterChip(s, s in selected, { onToggle(s) })
        trailing()
    }
}
