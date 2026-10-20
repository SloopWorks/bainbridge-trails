package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.RoadReason

/** Roads list groups (docs/roads/ux.md §4.2), in display order. Main roads = state routes + arterials. */
enum class RoadGroup { MAIN, COLLECTORS, RESIDENTIAL, NOT_COUNTED;

    companion object {
        /** The group of a road: uncounted roads (private, unconfirmed, lanes) always go to [NOT_COUNTED]. */
        fun of(category: String?, counted: Boolean): RoadGroup = when {
            !counted -> NOT_COUNTED
            category == "state_route" || category == "arterial" -> MAIN
            category == "collector" -> COLLECTORS
            else -> RESIDENTIAL
        }
    }
}

/** Roads list sort (product.md §7 step 4): by type then A–Z (default, grouped), progress high→low, shortest first. */
enum class RoadSort { GROUPED, PROGRESS, SHORTEST }

/** One Roads-list row: road metadata + current progress. [lengthMi] is the counted length (public part). */
data class RoadListItem(
    val id: String,
    val name: String,
    val category: String?,
    val counted: Boolean,
    val reason: RoadReason?,
    val fraction: Double,
    val status: TrailStatus,
    val lengthMi: Double,
    val partlyPublic: Boolean = false,
    val surface: String? = null,
) {
    val group: RoadGroup get() = RoadGroup.of(category, counted)
}

/** Search + filters + sort of the Roads list. Empty [groups] / [statuses] = all. */
data class RoadListQuery(
    val search: String = "",
    val statuses: Set<TrailStatus> = emptySet(),
    val groups: Set<RoadGroup> = emptySet(),
    val sort: RoadSort = RoadSort.GROUPED,
) {
    val hasFilters: Boolean get() = statuses.isNotEmpty() || groups.isNotEmpty()
    val hasSearch: Boolean get() = TrailListLogic.fold(search).isNotEmpty()

    /** Headers only for the default A–Z view with no search: a ranking or a search result reads as one list. */
    val grouped: Boolean get() = sort == RoadSort.GROUPED && !hasSearch

    fun withoutFilters(): RoadListQuery = copy(statuses = emptySet(), groups = emptySet())
}

/** A run of rows: under a group header when [group] is set, else a flat list. */
data class RoadSection(val group: RoadGroup?, val items: List<RoadListItem>, val done: Int, val total: Int)

/** Pure Roads-list logic (docs/roads/ux.md §4, product.md §7): abbreviations, search, grouping, sorts. */
object RoadListLogic {
    /** Display abbreviations (ux.md §4.3), whole words only; semantics keep the full name. */
    private val DISPLAY = mapOf(
        "Avenue" to "Ave", "Road" to "Rd", "Drive" to "Dr", "Street" to "St", "Place" to "Pl", "Lane" to "Ln",
        "Boulevard" to "Blvd", "Court" to "Ct", "Northeast" to "NE", "Northwest" to "NW", "Southeast" to "SE",
        "Southwest" to "SW",
    )

    /** "Fletcher Bay Road Northeast" → "Fletcher Bay Rd NE". The first word is never abbreviated ("Lane Road"). */
    fun displayName(name: String): String {
        val words = name.split(' ')
        return words.mapIndexed { i, w -> if (i == 0) w else DISPLAY[w] ?: w }.joinToString(" ")
    }

    /** Search synonyms → one canonical token (product.md §7 step 6). Multi-token expansions for route names. */
    private val CANON: Map<String, List<String>> = buildMap {
        fun syn(canon: String, vararg forms: String) { put(canon, listOf(canon)); forms.forEach { put(it, listOf(canon)) } }
        syn("ne", "northeast"); syn("nw", "northwest"); syn("se", "southeast"); syn("sw", "southwest")
        syn("n", "north"); syn("s", "south"); syn("e", "east"); syn("w", "west")
        syn("rd", "road"); syn("ave", "avenue", "av"); syn("st", "street"); syn("dr", "drive"); syn("ln", "lane")
        syn("pl", "place"); syn("ct", "court"); syn("blvd", "boulevard"); syn("wy", "way"); syn("hwy", "highway")
        put("sr", listOf("state", "hwy"))
    }

    /** Folded, canonical search tokens of [s] ("North Madison Avenue Northeast" → [n, madison, ave, ne]). */
    fun tokens(s: String): List<String> =
        TrailListLogic.fold(s).split(' ', '-', '.', ',', '/').filter { it.isNotEmpty() }.flatMap { CANON[it] ?: listOf(it) }

    /**
     * Abbreviation-, case- and accent-insensitive token-prefix match: every query token is a prefix of a name token,
     * in order ("madison ave ne" finds North Madison Avenue Northeast; "sr 305" and "305" find State Highway 305).
     */
    fun matchesSearch(name: String, query: String): Boolean {
        val q = tokens(query)
        if (q.isEmpty()) return true
        val n = tokens(name)
        var i = 0
        for (t in n) {
            if (i < q.size && t.startsWith(q[i])) i++
        }
        return i == q.size
    }

    fun matches(item: RoadListItem, query: RoadListQuery): Boolean {
        if (query.groups.isNotEmpty() && item.group !in query.groups) return false
        // Uncounted roads carry no status: a status filter hides them.
        if (query.statuses.isNotEmpty() && (!item.counted || item.status !in query.statuses)) return false
        return matchesSearch(item.name, query.search)
    }

    private val byName: Comparator<RoadListItem> = compareBy<RoadListItem> { TrailListLogic.fold(it.name) }.thenBy { it.id }

    fun comparator(sort: RoadSort): Comparator<RoadListItem> = when (sort) {
        RoadSort.GROUPED -> compareBy<RoadListItem> { it.group.ordinal }.then(byName)
        RoadSort.PROGRESS -> compareByDescending<RoadListItem> { it.fraction }.then(byName)
        RoadSort.SHORTEST -> compareBy<RoadListItem> { it.lengthMi }.then(byName)
    }

    /**
     * The list as sections. Grouped view: one section per non-empty group in [RoadGroup] order, with per-group
     * complete/total counted over *all* of that group's roads (filters don't change a header's "2 of 32"). Flat view
     * (search or a ranking sort): one section; rankings list counted roads only, a search also finds uncounted ones
     * (after the counted matches).
     */
    fun sections(items: List<RoadListItem>, query: RoadListQuery): List<RoadSection> {
        val shown = items.filter { matches(it, query) }
        if (!query.grouped) {
            val counted = shown.filter { it.counted }.sortedWith(comparator(query.sort))
            val rest = if (query.hasSearch) shown.filter { !it.counted }.sortedWith(byName) else emptyList()
            val all = counted + rest
            return if (all.isEmpty()) emptyList() else listOf(RoadSection(null, all, all.count { it.status == TrailStatus.COMPLETE && it.counted }, all.size))
        }
        val byGroup = items.groupBy { it.group }
        return RoadGroup.entries.mapNotNull { g ->
            val rows = shown.filter { it.group == g }.sortedWith(byName)
            if (rows.isEmpty()) return@mapNotNull null
            val groupAll = byGroup[g].orEmpty()
            RoadSection(g, rows, groupAll.count { it.counted && it.status == TrailStatus.COMPLETE }, groupAll.size)
        }
    }

    /** Count line: counted roads shown / counted roads in the dataset, and how many of the shown are complete. */
    data class Summary(val shown: Int, val total: Int, val complete: Int)

    fun summary(items: List<RoadListItem>, query: RoadListQuery): Summary {
        val counted = items.filter { it.counted }
        val shown = counted.filter { matches(it, query) }
        return Summary(shown.size, counted.size, shown.count { it.status == TrailStatus.COMPLETE })
    }

    /** Nothing counted has been walked yet (first-walk banner). */
    fun noProgressYet(items: List<RoadListItem>): Boolean = items.none { it.counted && it.fraction > 0.0 }
}
