package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.auth.ProviderAvailability
import com.bainbridgetrails.auth.SignInProvider
import com.bainbridgetrails.auth.SignInResult
import com.bainbridgetrails.auth.rememberPlatformSignIn
import com.bainbridgetrails.data.NameSaveResult
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.account_danger_zone
import com.bainbridgetrails.resources.account_delete
import com.bainbridgetrails.resources.account_sign_out
import com.bainbridgetrails.resources.account_signed_in_as
import com.bainbridgetrails.resources.account_title
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.delete_body
import com.bainbridgetrails.resources.delete_confirm_button
import com.bainbridgetrails.resources.delete_dialog_body
import com.bainbridgetrails.resources.delete_dialog_confirm
import com.bainbridgetrails.resources.delete_dialog_title
import com.bainbridgetrails.resources.delete_done
import com.bainbridgetrails.resources.delete_local_checkbox
import com.bainbridgetrails.resources.delete_title
import com.bainbridgetrails.resources.error_delete_signin_again
import com.bainbridgetrails.resources.error_offline_delete
import com.bainbridgetrails.resources.error_offline_signin
import com.bainbridgetrails.resources.error_server
import com.bainbridgetrails.resources.error_signin
import com.bainbridgetrails.resources.name_body
import com.bainbridgetrails.resources.name_choice_anon
import com.bainbridgetrails.resources.name_choice_own
import com.bainbridgetrails.resources.name_err_blocked
import com.bainbridgetrails.resources.name_err_generic
import com.bainbridgetrails.resources.name_err_rate
import com.bainbridgetrails.resources.name_reset_notice
import com.bainbridgetrails.resources.name_saved
import com.bainbridgetrails.resources.name_saved_offline
import com.bainbridgetrails.resources.name_shuffle
import com.bainbridgetrails.resources.name_title
import com.bainbridgetrails.resources.name_use_this
import com.bainbridgetrails.resources.profile_privacy_note
import com.bainbridgetrails.resources.profile_signed_out_body
import com.bainbridgetrails.resources.profile_signed_out_title
import com.bainbridgetrails.resources.profile_sync
import com.bainbridgetrails.resources.profile_hide_body
import com.bainbridgetrails.resources.profile_hide_failed
import com.bainbridgetrails.resources.profile_hide_moderation
import com.bainbridgetrails.resources.profile_hide_off
import com.bainbridgetrails.resources.profile_hide_on
import com.bainbridgetrails.resources.profile_hide_updated
import com.bainbridgetrails.data.HideToggleMessage
import com.bainbridgetrails.data.hideToggleMessage
import com.bainbridgetrails.resources.profile_hide_title
import com.bainbridgetrails.resources.error_offline_generic
import com.bainbridgetrails.resources.provider_apple
import com.bainbridgetrails.resources.provider_dev
import com.bainbridgetrails.resources.provider_google
import com.bainbridgetrails.resources.signin_apple
import com.bainbridgetrails.resources.signin_dev
import com.bainbridgetrails.resources.signin_google
import com.bainbridgetrails.resources.signin_not_configured
import com.bainbridgetrails.resources.signout_body
import com.bainbridgetrails.resources.signout_confirm
import com.bainbridgetrails.resources.signout_title
import com.bainbridgetrails.ui.components.AppleSignInButton
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.GoogleSignInButton
import com.bainbridgetrails.ui.components.NameEditor
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.SecondaryButton
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SettingSwitchRow
import com.bainbridgetrails.ui.components.errorRes
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Account (F9, ux.md §3.9): sign-in when signed out; name choice, sync, sign out and deletion when signed in. */
@Composable
fun ProfileScreen(graph: AppGraph.Installed, onBack: () -> Unit) {
    val auth by graph.auth.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ListScreen(stringResource(Res.string.account_title), onBack = onBack, snackbar = snackbar, modifier = Modifier.imePadding()) {
        when (val a = auth) {
            is AuthState.SignedIn -> item(key = "in") { SignedInContent(graph, a, snackbar) }
            else -> item(key = "out") { SignedOutContent(graph, snackbar) }
        }
    }
}

@Composable
private fun SignedOutContent(graph: AppGraph.Installed, snackbar: SnackbarHostState) {
    val platform = rememberPlatformSignIn()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    fun run(key: String, block: suspend () -> SignInResult) {
        busy = key
        scope.launch {
            val r = block()
            busy = null
            val msg = when (r) {
                is SignInResult.Success, SignInResult.Cancelled -> null
                SignInResult.NotConfigured -> getString(Res.string.signin_not_configured)
                is SignInResult.Failed -> getString(if (r.error is ApiError.Offline) Res.string.error_offline_signin else Res.string.error_signin)
            }
            if (msg != null) snackbar.showSnackbar(msg)
        }
    }
    val apple = platform.availability(SignInProvider.APPLE)
    val google = platform.availability(SignInProvider.GOOGLE)
    Column(Modifier.padding(horizontal = BtTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
        Spacer(Modifier.height(BtTheme.spacing.sm))
        Text(stringResource(Res.string.profile_signed_out_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.profile_signed_out_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(BtTheme.spacing.md))
        if (apple != ProviderAvailability.UNSUPPORTED) {
            AppleSignInButton(
                stringResource(Res.string.signin_apple),
                onClick = { run("apple") { graph.auth.signInWithApple(platform) } },
                enabled = apple == ProviderAvailability.AVAILABLE && busy == null,
                loading = busy == "apple",
            )
        }
        if (google != ProviderAvailability.UNSUPPORTED) {
            GoogleSignInButton(
                stringResource(Res.string.signin_google),
                onClick = { run("google") { graph.auth.signInWithGoogle(platform) } },
                enabled = google == ProviderAvailability.AVAILABLE && busy == null,
                loading = busy == "google",
            )
        }
        if (apple == ProviderAvailability.NOT_CONFIGURED || google == ProviderAvailability.NOT_CONFIGURED) {
            Text(stringResource(Res.string.signin_not_configured), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (graph.auth.devSignInAvailable) {
            SecondaryButton(
                stringResource(Res.string.signin_dev),
                onClick = { run("dev") { graph.auth.signInWithDev() } },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(BtTheme.spacing.sm))
        PrivacyNote()
    }
}

@Composable
private fun PrivacyNote() {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium).padding(BtTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        BtIcon(BtIcons.Lock, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.profile_privacy_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class NameMode { ANON, OWN }

@Composable
private fun SignedInContent(graph: AppGraph.Installed, auth: AuthState.SignedIn, snackbar: SnackbarHostState) {
    val profileState by graph.profile.profile.collectAsStateWithLifecycle()
    val sync by graph.sync.state.collectAsStateWithLifecycle()
    val profile = profileState ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.profile.refresh() }

    val savedCustom = profile.customName
    var mode by remember(savedCustom != null) { mutableStateOf(if (savedCustom != null) NameMode.OWN else NameMode.ANON) }
    var suggestion by remember(profile.anonName) { mutableStateOf(profile.anonName) }
    var draft by remember(savedCustom) { mutableStateOf(savedCustom.orEmpty()) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var signOutDialog by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableStateOf(0) }
    var wipeLocal by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun save(block: suspend () -> NameSaveResult) {
        saving = true
        serverError = null
        scope.launch {
            val r = block()
            saving = false
            when (r) {
                is NameSaveResult.Saved -> snackbar.showSnackbar(getString(Res.string.name_saved))
                NameSaveResult.SavedLocally -> snackbar.showSnackbar(getString(Res.string.name_saved_offline))
                is NameSaveResult.Invalid -> serverError = r.check.errorRes?.let { getString(it) }
                NameSaveResult.Rejected -> serverError = getString(Res.string.name_err_blocked)
                NameSaveResult.RateLimited -> serverError = getString(Res.string.name_err_rate)
                is NameSaveResult.Failed -> snackbar.showSnackbar(getString(Res.string.name_err_generic))
            }
        }
    }

    Column(Modifier.padding(horizontal = BtTheme.spacing.screenH)) {
        if (profile.nameBlocked) {
            Text(
                stringResource(Res.string.name_reset_notice, profile.displayName),
                Modifier.fillMaxWidth().padding(bottom = BtTheme.spacing.md)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.medium).padding(BtTheme.spacing.lg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Text(stringResource(Res.string.name_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(BtTheme.spacing.sm))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == NameMode.ANON,
                onClick = {
                    if (mode != NameMode.ANON) {
                        mode = NameMode.ANON
                        // Switching back keeps the last anon name (F9b.3); saves right away (ux.md §3.9).
                        if (savedCustom != null) save { graph.profile.saveAnonymous() }
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text(stringResource(Res.string.name_choice_anon)) },
            )
            SegmentedButton(
                selected = mode == NameMode.OWN,
                onClick = { mode = NameMode.OWN; serverError = null },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text(stringResource(Res.string.name_choice_own)) },
            )
        }
        Spacer(Modifier.height(BtTheme.spacing.lg))
        when (mode) {
            NameMode.ANON -> AnonNameCard(
                name = suggestion,
                changed = suggestion != profile.anonName || savedCustom != null,
                saving = saving,
                onShuffle = { suggestion = graph.profile.shuffle() },
                onUse = { val n = suggestion; save { graph.profile.saveAnonymous(n) } },
            )
            NameMode.OWN -> NameEditor(
                value = draft,
                onValueChange = { draft = it; serverError = null },
                onSave = { val n = draft; save { graph.profile.saveCustomName(n) } },
                savedName = savedCustom,
                serverError = serverError,
                saving = saving,
            )
        }
        Text(
            stringResource(Res.string.name_body),
            Modifier.padding(top = BtTheme.spacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // P1-8: saves at once (optimistic, reverted + snackbar on failure; ux.md §3.9).
        val hidden = profile.me?.hiddenFromBoard == true
        SettingSwitchRow(
            stringResource(Res.string.profile_hide_title),
            checked = hidden,
            onCheckedChange = { hide ->
                scope.launch {
                    val r = graph.profile.setHiddenFromBoard(hide)
                    val msg = when (r) {
                        is ApiResult.Ok -> {
                            graph.leaderboard.refresh()
                            getString(
                                when (hideToggleMessage(hide, r.value)) {
                                    HideToggleMessage.HIDDEN -> Res.string.profile_hide_on
                                    HideToggleMessage.SHOWN -> Res.string.profile_hide_off
                                    HideToggleMessage.UPDATED -> Res.string.profile_hide_updated
                                },
                            )
                        }
                        is ApiResult.Failure -> getString(if (r.error is ApiError.Offline) Res.string.error_offline_generic else Res.string.profile_hide_failed)
                    }
                    snackbar.showSnackbar(msg)
                }
            },
            modifier = Modifier.padding(top = BtTheme.spacing.md),
            description = stringResource(Res.string.profile_hide_body),
            enabled = profile.me != null,
            horizontalPadding = 0.dp,
        )
        // P2-10: an admin hide outlives the toggle; say so neutrally instead of letting "off" look broken.
        if (profile.me?.hiddenByModeration == true) {
            Text(
                stringResource(Res.string.profile_hide_moderation),
                Modifier.padding(top = BtTheme.spacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(stringResource(Res.string.profile_sync), Modifier.padding(top = BtTheme.spacing.lg))
        SyncStatusLine(sync, onRetry = { graph.sync.syncNow() })
        val provider = when (auth.session.provider) {
            "apple" -> stringResource(Res.string.provider_apple)
            "google" -> stringResource(Res.string.provider_google)
            else -> stringResource(Res.string.provider_dev)
        }
        Text(
            stringResource(Res.string.account_signed_in_as, provider),
            Modifier.padding(vertical = BtTheme.spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(stringResource(Res.string.account_sign_out), onClick = { signOutDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = BtTheme.spacing.sm))

        SectionHeader(stringResource(Res.string.account_danger_zone), Modifier.padding(top = BtTheme.spacing.xl), color = MaterialTheme.colorScheme.error)
        TextButton(
            onClick = { wipeLocal = false; deleteStep = 1 },
            enabled = !deleting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        ) { Text(stringResource(if (deleting) Res.string.delete_dialog_confirm else Res.string.account_delete)) }
        Spacer(Modifier.height(BtTheme.spacing.md))
        PrivacyNote()
    }

    if (signOutDialog) {
        AlertDialog(
            onDismissRequest = { signOutDialog = false },
            title = { Text(stringResource(Res.string.signout_title)) },
            text = { Text(stringResource(Res.string.signout_body)) },
            confirmButton = { TextButton(onClick = { signOutDialog = false; scope.launch { graph.auth.signOut() } }) { Text(stringResource(Res.string.signout_confirm)) } },
            dismissButton = { TextButton(onClick = { signOutDialog = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    if (deleteStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text(stringResource(Res.string.delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                    Text(stringResource(Res.string.delete_body))
                    Row(
                        Modifier.fillMaxWidth().toggleable(wipeLocal, role = Role.Checkbox, onValueChange = { wipeLocal = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(wipeLocal, onCheckedChange = null)
                        Text(stringResource(Res.string.delete_local_checkbox), Modifier.padding(start = BtTheme.spacing.sm), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteStep = 2 }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(Res.string.delete_confirm_button))
                }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    if (deleteStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text(stringResource(Res.string.delete_dialog_title)) },
            text = { Text(stringResource(Res.string.delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteStep = 0
                        deleting = true
                        val wipe = wipeLocal
                        scope.launch {
                            val r = graph.auth.deleteAccount(wipe)
                            deleting = false
                            val msg = when (r) {
                                is ApiResult.Ok -> getString(Res.string.delete_done)
                                is ApiResult.Failure -> getString(
                                    when (r.error) {
                                        is ApiError.Offline -> Res.string.error_offline_delete
                                        // The 401 signed this phone out; nothing was deleted (F9e needs a live session).
                                        is ApiError.Unauthorized -> Res.string.error_delete_signin_again
                                        else -> Res.string.error_server
                                    },
                                )
                            }
                            snackbar.showSnackbar(msg)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.delete_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

@Composable
private fun AnonNameCard(name: String, changed: Boolean, saving: Boolean, onShuffle: () -> Unit, onUse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                .padding(start = BtTheme.spacing.lg, end = BtTheme.spacing.xs, top = BtTheme.spacing.xs, bottom = BtTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f).padding(vertical = BtTheme.spacing.md), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onShuffle, enabled = !saving) {
                BtIcon(BtIcons.Shuffle, null, size = 18.dp)
                Spacer(Modifier.padding(start = 6.dp))
                Text(stringResource(Res.string.name_shuffle))
            }
        }
        if (changed) PrimaryButton(stringResource(Res.string.name_use_this), onClick = onUse, loading = saving, modifier = Modifier.fillMaxWidth())
    }
}
