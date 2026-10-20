package com.bainbridgetrails.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.bainbridgetrails.data.DisplayNameRules
import com.bainbridgetrails.data.NameCheck
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.name_counter
import com.bainbridgetrails.resources.name_err_chars
import com.bainbridgetrails.resources.name_err_letters
import com.bainbridgetrails.resources.name_err_link
import com.bainbridgetrails.resources.name_err_long
import com.bainbridgetrails.resources.name_err_short
import com.bainbridgetrails.resources.name_field_label
import com.bainbridgetrails.resources.name_save
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The `name_err_*` copy for a failed client check; null when the name passes. */
val NameCheck.errorRes: StringResource?
    get() = when (this) {
        is NameCheck.Ok -> null
        NameCheck.TooShort -> Res.string.name_err_short
        NameCheck.TooLong -> Res.string.name_err_long
        NameCheck.InvalidCharacters -> Res.string.name_err_chars
        NameCheck.TooFewLetters -> Res.string.name_err_letters
        NameCheck.LooksLikeLink -> Res.string.name_err_link
    }

/**
 * Custom display-name field (product F9b, §5): live [DisplayNameRules] validation (shown once the user has typed),
 * `n/24` counter, [serverError] from the last save (blocked / rate limited), Save (disabled while invalid or
 * unchanged). IME Done saves.
 */
@Composable
fun NameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    savedName: String? = null,
    serverError: String? = null,
    saving: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val check = DisplayNameRules.check(value)
    val localError = if (value.isEmpty()) null else check.errorRes?.let { stringResource(it) }
    val error = localError ?: serverError
    val len = DisplayNameRules.length(value)
    val unchanged = check is NameCheck.Ok && check.normalized == savedName
    val canSave = check is NameCheck.Ok && !unchanged && !saving
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(DisplayNameRules.MAX + 8)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.name_field_label)) },
            singleLine = true,
            isError = error != null,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) { focus.clearFocus(); onSave() } }),
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        error.orEmpty(),
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        stringResource(Res.string.name_counter, len),
                        style = BtTheme.type.numericLabel,
                        color = if (len > DisplayNameRules.MAX) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        PrimaryButton(
            stringResource(Res.string.name_save),
            onClick = { focus.clearFocus(); onSave() },
            enabled = canSave,
            loading = saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
