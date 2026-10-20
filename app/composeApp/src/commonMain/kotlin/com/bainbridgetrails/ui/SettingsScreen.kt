package com.bainbridgetrails.ui

import com.bainbridgetrails.data.LocalGoal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.attribution_fonts_title
import com.bainbridgetrails.resources.attribution_unavailable
import com.bainbridgetrails.resources.privacy_account_body
import com.bainbridgetrails.resources.privacy_account_title
import com.bainbridgetrails.resources.privacy_board_body
import com.bainbridgetrails.resources.privacy_board_title
import com.bainbridgetrails.resources.privacy_delete_body
import com.bainbridgetrails.resources.privacy_delete_title
import com.bainbridgetrails.resources.privacy_lead
import com.bainbridgetrails.resources.privacy_phone_body
import com.bainbridgetrails.resources.privacy_phone_title
import com.bainbridgetrails.resources.privacy_title
import com.bainbridgetrails.resources.settings_about
import com.bainbridgetrails.resources.settings_account
import com.bainbridgetrails.resources.settings_account_signed_out
import com.bainbridgetrails.resources.settings_attribution
import com.bainbridgetrails.resources.settings_attribution_dataset
import com.bainbridgetrails.resources.settings_attribution_fonts
import com.bainbridgetrails.resources.settings_map
import com.bainbridgetrails.resources.settings_privacy
import com.bainbridgetrails.resources.settings_privacy_policy
import com.bainbridgetrails.resources.settings_terms
import com.bainbridgetrails.resources.settings_support
import com.bainbridgetrails.resources.settings_link_failed
import com.bainbridgetrails.config.ApiConfig
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import com.bainbridgetrails.resources.settings_show_intro
import com.bainbridgetrails.resources.settings_style
import com.bainbridgetrails.resources.settings_style_auto
import com.bainbridgetrails.resources.settings_style_auto_desc
import com.bainbridgetrails.resources.settings_style_dark
import com.bainbridgetrails.resources.settings_style_dark_desc
import com.bainbridgetrails.resources.settings_style_light
import com.bainbridgetrails.resources.settings_style_light_desc
import com.bainbridgetrails.resources.settings_style_topo
import com.bainbridgetrails.resources.settings_style_topo_desc
import com.bainbridgetrails.resources.settings_title
import com.bainbridgetrails.resources.settings_units
import com.bainbridgetrails.resources.settings_units_km
import com.bainbridgetrails.resources.settings_units_mi
import com.bainbridgetrails.resources.settings_version
import com.bainbridgetrails.record.rememberPlatformContext
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.MapStyleSwatch
import com.bainbridgetrails.ui.components.NavRow
import com.bainbridgetrails.ui.components.SettingRadioRow
import com.bainbridgetrails.ui.components.SwatchStyle
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Map style choice (decisions.md TL-4): Auto = Topo in light mode, Dark in dark mode. Stored as the existing
 * `mapStyle` + `matchDark` pair, so the map code needs no change: Auto = (Topo, match on); the rest = (style, off).
 */
enum class MapStyleChoice { AUTO, TOPO, LIGHT, DARK }

fun AppSettings.styleChoice(): MapStyleChoice = when {
    matchDark && mapStyle == MapStyle.TOPO -> MapStyleChoice.AUTO
    mapStyle == MapStyle.MINIMAL_LIGHT -> MapStyleChoice.LIGHT
    mapStyle == MapStyle.DARK -> MapStyleChoice.DARK
    else -> MapStyleChoice.TOPO
}

fun AppSettings.withStyleChoice(c: MapStyleChoice): AppSettings = when (c) {
    MapStyleChoice.AUTO -> copy(mapStyle = MapStyle.TOPO, matchDark = true)
    MapStyleChoice.TOPO -> copy(mapStyle = MapStyle.TOPO, matchDark = false)
    MapStyleChoice.LIGHT -> copy(mapStyle = MapStyle.MINIMAL_LIGHT, matchDark = false)
    MapStyleChoice.DARK -> copy(mapStyle = MapStyle.DARK, matchDark = false)
}

/** Settings (F10, ux.md §3.10): map style, units, account, about. */
@Composable
fun SettingsScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    onBack: () -> Unit,
    onShowIntro: () -> Unit,
    onAccount: () -> Unit,
    onAttribution: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val auth by graph.auth.state.collectAsStateWithLifecycle()
    val profile by graph.profile.profile.collectAsStateWithLifecycle()
    val context = rememberPlatformContext()
    val version = remember(context) { appVersion(context) }
    val choice = settings.styleChoice()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    ListScreen(stringResource(Res.string.settings_title), onBack = onBack, snackbar = snackbar) {
        item { Group(stringResource(Res.string.settings_map)) }
        item {
            Text(
                stringResource(Res.string.settings_style),
                Modifier.padding(horizontal = BtTheme.spacing.screenH),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val options = listOf(
            Triple(MapStyleChoice.AUTO, Res.string.settings_style_auto, Res.string.settings_style_auto_desc),
            Triple(MapStyleChoice.TOPO, Res.string.settings_style_topo, Res.string.settings_style_topo_desc),
            Triple(MapStyleChoice.LIGHT, Res.string.settings_style_light, Res.string.settings_style_light_desc),
            Triple(MapStyleChoice.DARK, Res.string.settings_style_dark, Res.string.settings_style_dark_desc),
        )
        for ((c, title, desc) in options) {
            item(key = c.name) {
                SettingRadioRow(
                    stringResource(title),
                    selected = choice == c,
                    onSelect = { graph.settings.update { it.withStyleChoice(c) } },
                    description = stringResource(desc),
                    leading = { MapStyleSwatch(SwatchStyle.valueOf(c.name)) },
                )
            }
        }
        item { Group(stringResource(Res.string.settings_units)) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH)) {
                val units = listOf(Units.MILES to Res.string.settings_units_mi, Units.KILOMETRES to Res.string.settings_units_km)
                units.forEachIndexed { i, (u, label) ->
                    SegmentedButton(
                        selected = settings.units == u,
                        onClick = { graph.settings.update { it.copy(units = u) } },
                        shape = SegmentedButtonDefaults.itemShape(i, units.size),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
        item { Group(stringResource(Res.string.settings_account)) }
        item {
            val signedIn = auth is AuthState.SignedIn
            NavRow(
                if (signedIn) profile?.displayName.orEmpty() else stringResource(Res.string.settings_account_signed_out),
                onClick = onAccount,
                icon = BtIcons.Person,
            )
        }
        item { Group(stringResource(Res.string.settings_about)) }
        item { NavRow(stringResource(Res.string.settings_privacy), onPrivacy, icon = BtIcons.Lock) }
        // Store requirement: the hosted policy, terms and support pages (the summary above stays in-app).
        for ((label, path) in listOf(
            Res.string.settings_privacy_policy to "privacy",
            Res.string.settings_terms to "terms",
            Res.string.settings_support to "support",
        )) {
            item(key = "web-$path") {
                NavRow(stringResource(label), onClick = {
                    try {
                        uri.openUri(ApiConfig.pageUrl(path))
                    } catch (e: Exception) {
                        scope.launch { snackbar.showSnackbar(getString(Res.string.settings_link_failed)) }
                    }
                }, trailingIcon = BtIcons.OpenInNew)
            }
        }
        item { NavRow(stringResource(Res.string.settings_attribution), onAttribution, icon = BtIcons.Info) }
        item { NavRow(stringResource(Res.string.settings_show_intro), onShowIntro, icon = BtIcons.Hiking, trailingIcon = null) }
        item {
            Text(
                stringResource(Res.string.settings_version, version.name, version.build),
                Modifier.padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.lg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title,
        Modifier.padding(start = BtTheme.spacing.screenH, end = BtTheme.spacing.screenH, top = BtTheme.spacing.xl, bottom = BtTheme.spacing.sm).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Map data & licenses (F10-3): every line of the goal's bundled ATTRIBUTION.md (data/dist or data/roads/dist), dataset version, fonts/icons. */
@Composable
fun AttributionScreen(onBack: () -> Unit) {
    val dataset by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Res.readBytes("files/${com.bainbridgetrails.data.DatasetProfile.current.attributionFile}").decodeToString() }.getOrDefault("") }
    }
    ListScreen(stringResource(Res.string.settings_attribution), onBack = onBack) {
        item {
            Column(Modifier.padding(horizontal = BtTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                val t = text
                if (t != null && t.isBlank()) Text(stringResource(Res.string.attribution_unavailable), style = MaterialTheme.typography.bodyMedium)
                if (t != null) MarkdownLite(t)
                (dataset as? Loadable.Ready)?.let {
                    Text(
                        stringResource(LocalGoal.current.s(Res.string.settings_attribution_dataset), it.value.dataset.version),
                        Modifier.padding(top = BtTheme.spacing.sm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(Res.string.attribution_fonts_title), Modifier.padding(top = BtTheme.spacing.lg).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.settings_attribution_fonts), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val URL = Regex("https?://[^\\s);,]+")

/** Renders the attribution file's small Markdown subset: `#`/`##` headings, `- ` bullets, paragraphs; URLs are links. */
@Composable
private fun MarkdownLite(md: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val lines = md.lines().map { it.trimEnd() }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> Spacer(Modifier.height(2.dp))
            line.startsWith("# ") -> Unit // screen title already says it
            line.startsWith("## ") -> Text(line.removePrefix("## "), Modifier.padding(top = BtTheme.spacing.md).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            else -> {
                // A bullet plus its indented continuation lines ("  (sources: …)") form one block.
                val bullet = line.startsWith("- ")
                val body = StringBuilder(line.removePrefix("- "))
                while (i + 1 < lines.size && lines[i + 1].startsWith("  ")) {
                    i++
                    body.append('\n').append(lines[i].trim())
                }
                val s = body.toString()
                val annotated = buildAnnotatedString {
                    if (bullet) append("•  ")
                    var last = 0
                    for (m in URL.findAll(s)) {
                        append(s.substring(last, m.range.first))
                        withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(m.value) }
                        last = m.range.last + 1
                    }
                    append(s.substring(last))
                }
                Text(annotated, style = MaterialTheme.typography.bodyMedium, color = if (bullet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        i++
    }
}

/** In-app privacy summary (TL-3 wording). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    ListScreen(stringResource(Res.string.privacy_title), onBack = onBack) {
        item {
            Column(Modifier.padding(horizontal = BtTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                Text(stringResource(LocalGoal.current.s(Res.string.privacy_lead)), style = MaterialTheme.typography.headlineSmall)
                for ((t, b) in listOf(
                    Res.string.privacy_phone_title to Res.string.privacy_phone_body,
                    Res.string.privacy_account_title to LocalGoal.current.s(Res.string.privacy_account_body),
                    Res.string.privacy_board_title to LocalGoal.current.s(Res.string.privacy_board_body),
                    Res.string.privacy_delete_title to Res.string.privacy_delete_body,
                )) {
                    Text(stringResource(t), Modifier.padding(top = BtTheme.spacing.lg).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(b), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
