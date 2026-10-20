package com.bainbridgetrails.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.board_signed_out_body
import com.bainbridgetrails.resources.board_signed_out_title
import com.bainbridgetrails.resources.board_title
import com.bainbridgetrails.resources.me_edit_name
import org.jetbrains.compose.resources.stringResource

/** Leaderboard (F8). Placeholder until sign-in + backend client exist. */
@Composable
fun LeaderboardScreen(onBack: () -> Unit) {
    PushedScreen(stringResource(Res.string.board_title), onBack) {
        Text(stringResource(Res.string.board_signed_out_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(Res.string.board_signed_out_body), modifier = Modifier.padding(top = 8.dp))
    }
}

/** Account and profile (F9). Placeholder. */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    PushedScreen(stringResource(Res.string.me_edit_name), onBack) {}
}
