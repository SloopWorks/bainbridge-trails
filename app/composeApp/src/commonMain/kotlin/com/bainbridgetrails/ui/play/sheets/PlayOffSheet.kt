package com.bainbridgetrails.ui.play.sheets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.play_off_body
import com.bainbridgetrails.resources.play_off_confirm
import com.bainbridgetrails.resources.play_off_title
import org.jetbrains.compose.resources.stringResource

/** PlayOffSheet (PF14; product § 5.5): confirm turns Play off, nothing deleted; cancel / dismiss keeps it on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOffSheet(onConfirm: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        OptInCard(
            title = stringResource(Res.string.play_off_title),
            body = stringResource(Res.string.play_off_body),
            primary = stringResource(Res.string.play_off_confirm),
            onPrimary = onConfirm,
            secondary = stringResource(Res.string.common_cancel),
            onSecondary = onCancel,
            art = OptInArt.Fog,
        )
    }
}
