package com.bainbridgetrails.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RP-1 guard (product.md §2.4, E15): a trail string must never leak into the roads build.
 * - every key in values/strings*.xml whose text matches /trail/i has a `roads_` twin with the same placeholders,
 *   or is on [TRAIL_ONLY] (screens the roads build never shows);
 * - every twin of a trail key is registered in [GoalVocabulary.Roads];
 * - roads text says "trail" only where the copy means trails ([ROADS_MAY_SAY_TRAIL]).
 * Parses the XML from the source tree (host tests run with the module as working directory).
 */
class GoalVocabularyGuardTest {
    private data class Entry(val key: String, val plural: Boolean, val texts: List<String>, val file: String)

    private val entries: Map<String, Entry> by lazy {
        val dir = listOf("src/commonMain/composeResources/values", "composeApp/src/commonMain/composeResources/values")
            .map(::File).first { it.isDirectory }
        val out = LinkedHashMap<String, Entry>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
            val xml = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                out[it.groupValues[1]] = Entry(it.groupValues[1], false, listOf(it.groupValues[2]), f.name)
            }
            Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                val items = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
                out[m.groupValues[1]] = Entry(m.groupValues[1], true, items, f.name)
            }
        }
        out
    }

    private fun placeholders(e: Entry): Set<String> =
        e.texts.flatMap { t -> Regex("""%\d+\$[sd]""").findAll(t).map { it.value }.toList() }.toSet()

    private val trailWord = Regex("trail", RegexOption.IGNORE_CASE)

    @Test
    fun parsedTheResources() {
        assertTrue(entries.size > 200, "parsed only ${entries.size} entries")
        assertTrue("roads_app_name" in entries)
    }

    @Test
    fun everyTrailStringHasARoadsTwin() {
        val problems = mutableListOf<String>()
        entries.values.filter { !it.key.startsWith("roads_") && it.texts.any(trailWord::containsMatchIn) }.forEach { e ->
            if (e.key in TRAIL_ONLY) return@forEach
            val twin = entries["roads_${e.key}"]
            when {
                twin == null -> problems += "${e.key} (${e.file}) says \"trail\" but has no roads_${e.key} and is not allowlisted"
                twin.plural != e.plural -> problems += "roads_${e.key}: plural/string kind differs from ${e.key}"
                placeholders(twin) != placeholders(e) && e.key !in EXTRA_ARGS ->
                    problems += "roads_${e.key}: placeholders ${placeholders(twin)} != ${placeholders(e)}"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun extraArgOverridesKeepTheTrailArgs() {
        EXTRA_ARGS.forEach { k ->
            val trail = placeholders(entries.getValue(k))
            val roads = placeholders(entries.getValue("roads_$k"))
            assertTrue(roads.containsAll(trail), "roads_$k must keep $k's arguments")
        }
    }

    @Test
    fun everyTwinIsRegisteredInGoalVocabularyRoads() {
        val strings = GoalVocabulary.Roads.stringOverrides.map { (k, v) -> k.key to v.key }.toMap()
        val plurals = GoalVocabulary.Roads.pluralOverrides.map { (k, v) -> k.key to v.key }.toMap()
        val problems = mutableListOf<String>()
        entries.values.filter { it.key.startsWith("roads_") }.forEach { twin ->
            val base = entries[twin.key.removePrefix("roads_")] ?: return@forEach // roads-only key
            val registered = if (base.plural) plurals[base.key] else strings[base.key]
            if (registered != twin.key) problems += "${twin.key} is not registered for ${base.key} in GoalVocabulary.Roads"
        }
        (strings + plurals).forEach { (k, v) -> if (v != "roads_$k") problems += "$k maps to $v, expected roads_$k" }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun roadsCopyMentionsTrailsOnlyOnPurpose() {
        val leaks = entries.values.filter { it.key.startsWith("roads_") && it.key !in ROADS_MAY_SAY_TRAIL && it.texts.any(trailWord::containsMatchIn) }
        assertEquals(emptyList(), leaks.map { it.key })
    }

    /**
     * Every Kotlin reference to an overridden trail key goes through the vocabulary on the same line
     * (`LocalGoal.current.s(Res.string.x)`, `goal.text(Res.string.x)`, …), so a call site cannot forget the roads
     * twin. [INDIRECT] lists the few that hand the key to a holder which is looked up where it is rendered.
     */
    @Test
    fun everyOverriddenKeyIsLookedUpThroughTheVocabulary() {
        val keys = GoalVocabulary.Roads.stringOverrides.keys.map { "Res.string.${it.key}" } +
            GoalVocabulary.Roads.pluralOverrides.keys.map { "Res.plurals.${it.key}" }
        val wrapped = Regex("""\.(s|p|string|plural|text|pluralText)\(\s*$""")
        val problems = mutableListOf<String>()
        kotlinSources().forEach { (path, text) ->
            text.lines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("import ")) return@forEachIndexed
                keys.forEach { ref ->
                    Regex(Regex.escape(ref) + """\b""").findAll(line).forEach { m ->
                        val key = ref.substringAfterLast('.')
                        if (!wrapped.containsMatchIn(line.substring(0, m.range.first)) && "$path:$key" !in INDIRECT) {
                            problems += "$path:${i + 1} uses $ref without LocalGoal / AppGraph.goal"
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /**
     * Android string resources (notifications, launcher label) have no `roads_` lookup: composeApp's androidMain
     * res must not say "trail" at all, and androidApp's main res may only where the roads flavour overrides the key.
     */
    @Test
    fun androidStringResourcesDoNotLeakTrails() {
        val composeAndroid = moduleFile("src/androidMain/res/values")
        val appMain = moduleFile("../androidApp/src/main/res/values")
        val appRoads = moduleFile("../androidApp/src/roads/res/values")
        val roadsKeys = androidStrings(appRoads).keys
        val problems = mutableListOf<String>()
        androidStrings(composeAndroid).forEach { (k, v) -> if (trailWord.containsMatchIn(v)) problems += "composeApp androidMain $k says \"trail\": \"$v\"" }
        androidStrings(appMain).forEach { (k, v) ->
            if (trailWord.containsMatchIn(v) && k !in roadsKeys) problems += "androidApp main $k says \"trail\" and src/roads/res does not override it"
        }
        androidStrings(appRoads).forEach { (k, v) -> if (trailWord.containsMatchIn(v)) problems += "androidApp roads $k says \"trail\": \"$v\"" }
        assertTrue(androidStrings(appMain).isNotEmpty(), "parsed no androidApp strings")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun moduleFile(path: String): File =
        listOf(File(path), File("composeApp", path)).firstOrNull { it.exists() } ?: error("$path not found from ${File(".").absolutePath}")

    private fun androidStrings(dir: File): Map<String, String> =
        dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().flatMap { f ->
            val xml = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()

    private fun kotlinSources(): List<Pair<String, String>> {
        val root = moduleFile("src/commonMain/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "GoalVocabulary.kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath.substringAfter("com/bainbridgetrails/") to it.readText() }.toList()
            .also { assertTrue(it.size > 50, "found only ${it.size} sources") }
    }

    @Test
    fun trailsVocabularyIsIdentity() {
        assertTrue(GoalVocabulary.Trails.stringOverrides.isEmpty())
        assertTrue(GoalVocabulary.Trails.pluralOverrides.isEmpty())
        assertEquals(GoalVocabulary.Roads, GoalVocabulary.of(DatasetProfile.ROADS))
        assertEquals(GoalVocabulary.Trails, GoalVocabulary.of(DatasetProfile.TRAILS))
    }

    @Test
    fun islandTotalRoundsDownToWholeUnits() {
        assertEquals(162, GoalVocabulary.wholeUnitsDown(261_532.8, Units.MILES)) // roads: 162.51 mi
        assertEquals(65, GoalVocabulary.wholeUnitsDown(105_396.0, Units.MILES)) // trails: 65.49 mi
        assertEquals(124, GoalVocabulary.wholeUnitsDown(200_000.0, Units.MILES)) // E2: 200 km -> "over 124 miles"
        assertEquals(261, GoalVocabulary.wholeUnitsDown(261_532.8, Units.KILOMETRES))
        assertEquals(1, GoalVocabulary.wholeUnitsDown(1_609.344, Units.MILES)) // exact unit is not lost to float noise
        assertEquals(0, GoalVocabulary.wholeUnitsDown(0.0, Units.MILES))
    }

    private companion object {
        /** Trail-only screens/rows the roads build hides (product.md §3.4/3.5 "→ hidden"). */
        val TRAIL_ONLY = setOf(
            "detail_trailheads", "detail_trailhead_row", "detail_official_map", "detail_official_map_host",
            "trails_filter_park", "trails_filter_park_all", "trails_filter_park_none", "trails_park_sheet_title",
        )

        /** Overridden keys stored in a holder and looked up through the vocabulary where they are rendered ("file:key"). */
        val INDIRECT = setOf(
            "nav/AppShell.kt:nav_trails", // Tab.label -> stringResource(LocalGoal.current.s(it.label))
            "record/PermissionFlow.kt:perm_location_body", // Rationale.body / body -> LocalGoal.current.s(body)
            "record/PermissionFlow.kt:perm_precise_body",
            "ui/TrailsScreen.kt:trails_sort_length", // TrailSort.label -> LocalGoal.current.s(…label)
        )

        /** Overrides that add arguments (product.md §3.10 note: the trail text ignores them). */
        val EXTRA_ARGS = setOf("stat_island_info")

        /** Roads copy that is about trails on purpose. */
        val ROADS_MAY_SAY_TRAIL = setOf(
            "roads_perm_precise_body", "roads_results_no_progress_hint", "roads_board_separate_note",
            "roads_owner_parks", // "Bainbridge Island Parks & Trails District" is a name
        )
    }
}
