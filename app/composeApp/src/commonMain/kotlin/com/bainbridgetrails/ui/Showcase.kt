package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_challenge_progress
import com.bainbridgetrails.resources.play_gate_dark
import com.bainbridgetrails.resources.play_gate_quiet
import com.bainbridgetrails.resources.play_gate_tide
import com.bainbridgetrails.resources.play_gate_tide_unknown
import com.bainbridgetrails.resources.play_kraken_stretch_progress
import com.bainbridgetrails.resources.play_safety_shore
import com.bainbridgetrails.resources.play_tag_remembrance
import com.bainbridgetrails.resources.play_tier_sea_glass
import com.bainbridgetrails.resources.play_treasure_collected
import com.bainbridgetrails.resources.play_treasure_expires
import com.bainbridgetrails.resources.play_walk_there
import com.bainbridgetrails.ui.components.BarItem
import com.bainbridgetrails.ui.components.BtBottomBar
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.components.DropdownChip
import com.bainbridgetrails.ui.components.EmptyKind
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.FilterChipRow
import com.bainbridgetrails.ui.components.GateKind
import com.bainbridgetrails.ui.components.GateNote
import com.bainbridgetrails.ui.components.LiveTrailRow
import com.bainbridgetrails.ui.components.LocateButton
import com.bainbridgetrails.ui.components.LocateMode
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.components.MapSheet
import com.bainbridgetrails.ui.components.MapSheetDefaults
import com.bainbridgetrails.ui.components.MetaChip
import com.bainbridgetrails.ui.components.OfflineNote
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.ProgressBar
import com.bainbridgetrails.ui.components.ProgressRing
import com.bainbridgetrails.ui.components.RecordingDot
import com.bainbridgetrails.ui.components.RecordingPill
import com.bainbridgetrails.ui.components.ResultTrailRow
import com.bainbridgetrails.ui.components.RewardChip
import com.bainbridgetrails.ui.components.SearchField
import com.bainbridgetrails.ui.components.SecondaryButton
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SignInPrompt
import com.bainbridgetrails.ui.components.SkeletonTrailRow
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatTileRow
import com.bainbridgetrails.ui.components.StateChip
import com.bainbridgetrails.ui.components.StatusChip
import com.bainbridgetrails.ui.components.StatusGlyph
import com.bainbridgetrails.ui.components.TrailRow
import com.bainbridgetrails.ui.components.WaterLines
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.of
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * Debug-only design-system showcase (`screen=showcase` launch option): every token and component in one scroll,
 * with a System / Light / Dark switch, for visual checks and screenshots. [section] jumps to a page
 * (`components`, `states`, `play`: docs/play/ux.md §3.19).
 */
@Composable
fun ShowcaseScreen(modifier: Modifier = Modifier, section: String? = null) {
    var mode by rememberSaveable { mutableStateOf(0) } // 0 system, 1 light, 2 dark
    val content: @Composable () -> Unit = { ShowcaseContent(modifier, mode, { mode = it }, section) }
    when (mode) {
        1 -> BtTheme(dark = false, content = content)
        2 -> BtTheme(dark = true, content = content)
        else -> content()
    }
}

@Composable
private fun ShowcaseContent(modifier: Modifier, mode: Int, onMode: (Int) -> Unit, section: String?) {
    var page by rememberSaveable {
        mutableStateOf(
            when (section) {
                "components" -> 1
                "states" -> 2
                "play" -> 3
                else -> 0
            },
        )
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Design system")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Tokens", "Components", "States", "Play").forEachIndexed { i, l ->
                        SegmentedButton(page == i, { page = i }, SegmentedButtonDefaults.itemShape(i, 4), icon = {}) { Text(l, maxLines = 1) }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("System", "Light", "Dark").forEachIndexed { i, l ->
                        SegmentedButton(mode == i, { onMode(i) }, SegmentedButtonDefaults.itemShape(i, 3), icon = {}) { Text(l, maxLines = 1) }
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (page) {
                    0 -> TokensPage()
                    1 -> ComponentsPage()
                    2 -> StatesPage()
                    else -> PlayShowcasePage()
                }
                Spacer(Modifier.height(140.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
        var tab by remember { mutableStateOf(0) }
        var recording by remember { mutableStateOf(false) }
        BtBottomBar(
            start = listOf(
                BarItem("Map", BtIcons.Map, BtIcons.MapFilled, tab == 0, { tab = 0 }),
                BarItem("Trails", BtIcons.Hiking, BtIcons.HikingFilled, tab == 1, { tab = 1 }),
            ),
            end = listOf(BarItem("Me", BtIcons.Person, BtIcons.PersonFilled, tab == 2, { tab = 2 })),
            recordLabel = if (recording) "Recording" else "Record",
            recording = recording,
            onRecord = { recording = !recording },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TokensPage() {
    Section("Type") {
        Text("12.4%", style = BtTheme.type.statHero)
        Text("Walk every trail", style = MaterialTheme.typography.displaySmall)
        Text("Gazzam Lake Main Trail", style = MaterialTheme.typography.headlineMedium)
        Text("Gazzam Lake Main Trail", style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic))
        Text("Trails on this walk", style = MaterialTheme.typography.titleLarge)
        Text("Title medium · system", style = MaterialTheme.typography.titleMedium)
        Text("Body large — record a walk and the trails you cover light up.", style = MaterialTheme.typography.bodyLarge)
        Text("Body medium on surface variant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("1:08:22", style = BtTheme.type.statLive)
            Text("2.41 mi", style = BtTheme.type.statLive)
        }
    }
    Section("Scheme") {
        val cs = MaterialTheme.colorScheme
        Swatches(
            listOf(
                "primary" to cs.primary, "pri.cont" to cs.primaryContainer, "secondary" to cs.secondary, "sec.cont" to cs.secondaryContainer,
                "tertiary" to cs.tertiary, "surface" to cs.surface, "cont.low" to cs.surfaceContainerLow, "container" to cs.surfaceContainer,
                "cont.high" to cs.surfaceContainerHigh, "outline" to cs.outline, "out.var" to cs.outlineVariant, "error" to cs.error,
            ),
        )
    }
    Section("Status (line · accent · container)") {
        for (s in TrailStatus.entries) {
            val c = BtTheme.colors.of(s)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(c.line, MaterialTheme.shapes.small))
                Box(Modifier.size(28.dp).background(c.accent, MaterialTheme.shapes.small))
                Box(Modifier.size(28.dp).background(c.container, MaterialTheme.shapes.small))
                StatusGlyph(s, size = 24.dp)
                StatusChip(s)
            }
        }
    }
    Section("App colors") {
        val x = BtTheme.colors
        Swatches(
            listOf(
                "recording" to x.recording, "userTrack" to x.userTrack, "waterLine" to x.waterLine, "positive" to x.positive,
                "mapChrome" to x.mapChrome, "notCounted" to x.notCounted, "casing" to x.nearlyDoneCasing, "selection" to x.selectionCasing,
            ),
        )
    }
}

@Composable
private fun Swatches(items: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, color) in items) {
            Column(Modifier.size(76.dp, 64.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(1.dp).background(color, MaterialTheme.shapes.small),
                )
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

private val Samples = listOf(
    Triple("Arbor Trail", 0.0, TrailStatus.NOT_STARTED),
    Triple("Gazzam Lake Main Trail", 0.42, TrailStatus.PARTWAY),
    Triple("Grand Forest West Loop", 0.78, TrailStatus.NEARLY_DONE),
    Triple("Close Tract Trail", 0.91, TrailStatus.COMPLETE),
)

@Composable
private fun ComponentsPage() {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf(TrailStatus.PARTWAY)) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchField(query, { query = it }, "Search trails or parks")
    }
    FilterChipRow(filters, { s -> filters = if (s in filters) filters - s else filters + s }, Modifier.padding(vertical = 4.dp)) {
        DropdownChip("Park", {})
        DropdownChip("Sort: Name", {})
    }
    Section("Rings · bars") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            for ((i, t) in Samples.withIndex()) ProgressRing(t.second, t.third, size = 48.dp, animationDelayMs = i * 30)
            ProgressRing(0.42, TrailStatus.PARTWAY, size = 32.dp, gained = 0.19)
        }
        for (t in Samples) ProgressBar(t.second, t.third)
    }
    Column {
        SectionHeader("Trails", Modifier.padding(horizontal = 16.dp), action = "All" to {})
        for ((i, t) in Samples.withIndex()) {
            TrailRow(t.first, "Gazzam Lake Preserve · 1.4 mi", t.second, t.third, {}, animationDelayMs = i * 30)
        }
    }
    Section("Walk rows") {
        LiveTrailRow("Gazzam Lake Main Trail", 0.12, 0.31, TrailStatus.PARTWAY)
        ResultTrailRow("Grand Forest West Loop", 0.61, 0.78, TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE)
        ResultTrailRow("Close Tract Trail", 0.70, 0.88, TrailStatus.NEARLY_DONE, TrailStatus.COMPLETE)
    }
    Section("Stat tiles") {
        StatTileRow {
            StatTile("12.4%", "Island", Modifier.weight(1f), emphasis = true)
            StatTile("9 / 114", "Trails completed", Modifier.weight(1f))
            StatTile("14.2", "Trail miles walked", Modifier.weight(1f))
        }
    }
    Section("Buttons · chips") {
        PrimaryButton("Start walk", {}, Modifier.fillMaxWidth(), icon = BtIcons.DirectionsWalk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Saving", {}, Modifier.weight(1f), loading = true)
            SecondaryButton("Details", {}, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StateChip("GPS ok", leading = { RecordingDot(size = 8.dp) })
            StateChip("Unverified", icon = BtIcons.Info, onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            MapIconButton(BtIcons.Trophy, "Leaderboard", {})
            LocateButton(LocateMode.Idle, {})
            LocateButton(LocateMode.Following, {})
            LocateButton(LocateMode.Unavailable, {})
        }
        RecordingPill(4_102_000, 3_878.0, Units.MILES, {})
    }
}

@Composable
private fun StatesPage() {
    EmptyState("No trails match", body = "Try a different search or clear the filters.", action = "Clear filters" to {})
    EmptyState("Leaderboard needs a connection", body = "Connect to see the leaderboard.", kind = EmptyKind.Offline, action = "Try again" to {})
    EmptyState("Trails didn't load", body = "Something went wrong reading the trail data.", kind = EmptyKind.Error, action = "Try again" to {})
    Section("Inline") {
        OfflineNote("Offline — showing saved data from 3 min ago")
        SignInPrompt("Sign in to put this on the leaderboard", "Sign in", {}, onDismiss = {}, dismissLabel = "Not now")
    }
    Section("Skeleton") { repeat(3) { SkeletonTrailRow() } }
    Section("Motif") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WaterLines(Modifier.size(96.dp))
            WaterLines(Modifier.size(64.dp))
        }
        Text("Reduced motion: ${BtTheme.motion.reduced}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/**
 * Showcase "Play" page (docs/play/ux.md §3.19; plan L2-1): the four Play roles with hex and live contrast, Play type,
 * the FxArt placeholder glyphs, chips and the shared components. Later stages add pins, sheets, cards and rows here.
 * Debug-only; nothing here is reachable with Play off in a release build.
 */
@Composable
private fun PlayShowcasePage() {
    val play = BtTheme.colors.play
    val cs = MaterialTheme.colorScheme
    val chrome = BtTheme.colors.mapChrome.compositeOver(cs.surface)
    Section("Play roles") {
        RoleRow("ink", play.ink, "surface ${ratio(play.ink, cs.surface)} · high ${ratio(play.ink, cs.surfaceContainerHigh)} · chrome ${ratio(play.ink, chrome)}")
        RoleRow("seaGlass", play.seaGlass, "text: surface ${ratio(play.seaGlass, cs.surface)} · low ${ratio(play.seaGlass, cs.surfaceContainerLow)} · chrome ${ratio(play.seaGlass, chrome)}")
        RoleRow("lamp", play.lamp, "light only; inside ink ${ratio(play.lamp, play.ink)}")
        RoleRow("fog", play.fog, "veil, alpha ${(play.fog.alpha * 100).roundToInt()} %")
        RoleRow("seaGlassWash", play.seaGlassWash, "derived; onSurface ${ratio(cs.onSurface, play.seaGlassWash)} · onSurfaceVariant ${ratio(cs.onSurfaceVariant, play.seaGlassWash)}")
    }
    Section("Play type") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            Column {
                Text("+25", style = BtTheme.type.marksDelta, color = play.seaGlass)
                Text("marksDelta", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Column {
                Text("1,240", style = BtTheme.type.marksTotal)
                Text("marksTotal", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
    Section("Play glyphs (placeholder ink)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in FxGlyph.entries) GlyphCell(g.name, g)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in 0..3) FxGlyphImage(FxGlyph.Marks, size = 32.dp, ink = play.seaGlass, ticks = t)
            Text("rank ticks 0–3", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in listOf(BtTheme.spacing.playArtTileSheet, BtTheme.spacing.playArtTileRow, BtTheme.spacing.playArtTileLog)) {
                ArtTile(FxGlyph.SeaGlass, s)
            }
            ArtTile(FxGlyph.LanternLit, BtTheme.spacing.playArtTileSheet, wash = true)
            Text("art tiles 56 / 40 / 32, found on wash", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    Section("Play chips") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RewardChip("25")
            RewardChip("25", claimed = true)
            RewardChip("1,240")
            MetaChip(stringResource(Res.string.play_treasure_expires))
            MetaChip(stringResource(Res.string.play_treasure_collected), icon = BtIcons.Check, iconTint = play.seaGlass)
            MetaChip(stringResource(Res.string.play_tag_remembrance), outlined = true)
        }
    }
    Section("PlayBar") {
        PlayBar(1f / 3f, stringResource(Res.string.play_challenge_progress, "1", "3"))
        PlayBar(0.5f, stringResource(Res.string.play_kraken_stretch_progress, 50, 60), tick = 0.6f)
        PlayBar(1f, stringResource(Res.string.play_challenge_progress, "3", "3"))
    }
    Section("GateNote") {
        GateNote(GateKind.Dark, stringResource(Res.string.play_gate_dark, "7:21 am"))
        GateNote(GateKind.Tide, stringResource(Res.string.play_gate_tide, "2:40 pm"))
        GateNote(GateKind.Tide, stringResource(Res.string.play_gate_tide_unknown))
        GateNote(GateKind.Quiet, stringResource(Res.string.play_gate_quiet))
        GateNote(GateKind.Safety, stringResource(Res.string.play_safety_shore))
    }
    Section("MapSheet · maxHeightFraction 0.6") {
        Box(Modifier.fillMaxWidth().height(320.dp).background(cs.surfaceVariant, MaterialTheme.shapes.medium)) {
            MapSheet(onDismiss = {}, modifier = Modifier.align(Alignment.BottomCenter), maxHeightFraction = MapSheetDefaults.EncounterMaxHeightFraction) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtTile(FxGlyph.SeaGlass, BtTheme.spacing.playArtTileSheet)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.play_tier_sea_glass), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text("Grand Forest West · 240 m away", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RewardChip("25")
                    MetaChip(stringResource(Res.string.play_treasure_expires))
                }
                Text(
                    "A pale green pebble, frosted by the Sound. It rolled up the bank in last winter's storm and waits by the bridge.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                GateNote(GateKind.Dark, stringResource(Res.string.play_gate_dark, "7:21 am"), Modifier.padding(vertical = 8.dp))
                PrimaryButton(stringResource(Res.string.play_walk_there), {}, Modifier.fillMaxWidth(), icon = BtIcons.DirectionsWalk)
            }
        }
    }
}

@Composable
private fun RoleRow(name: String, color: Color, note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(1.dp).background(color, MaterialTheme.shapes.small),
        )
        Column {
            Text("$name  ${hex(color)}", style = MaterialTheme.typography.labelLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlyphCell(name: String, glyph: FxGlyph) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceBright, MaterialTheme.shapes.medium)
                .border(1.dp, BtTheme.colors.play.ink.copy(alpha = 0.25f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            FxGlyphImage(glyph, size = 32.dp, ink = if (glyph == FxGlyph.Marks) BtTheme.colors.play.seaGlass else BtTheme.colors.play.ink)
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Paper art tile (ux §2.3): `surfaceBright`, 1dp ink border, medium shape; [wash] = the found state. */
@Composable
private fun ArtTile(glyph: FxGlyph, size: Dp, wash: Boolean = false) {
    val play = BtTheme.colors.play
    Box(
        Modifier.size(size)
            .background(if (wash) play.seaGlassWash else MaterialTheme.colorScheme.surfaceBright, MaterialTheme.shapes.medium)
            .border(1.dp, play.ink, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        FxGlyphImage(glyph, size = size * (FxArt.ARTBOARD + 4f) / (FxArt.ARTBOARD * 1.6f))
    }
}

private fun ratio(a: Color, b: Color): String {
    val la = a.luminance()
    val lb = b.luminance()
    val r = (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    return ((r * 10).roundToInt() / 10f).toString()
}

private fun hex(c: Color): String {
    val argb = c.toArgb()
    val rgb = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
    val a = (argb ushr 24) and 0xFF
    return if (a == 0xFF) "#$rgb" else "#$rgb @ ${(a * 100 / 255)}%"
}
