package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailStatus

/** Trails list sort (product F3 step 2): Name A–Z (default), Progress high→low (ties by name), Length short→long. */
enum class TrailSort { NAME, PROGRESS, LENGTH }

/** Park filter (single choice): every trail, trails in no park, or one named park. */
sealed interface ParkFilter {
    data object All : ParkFilter
    data object NoPark : ParkFilter
    data class Named(val park: String) : ParkFilter
}

/** One Trails-list row's data: dataset metadata + current progress. */
data class TrailListItem(
    val trailId: String,
    val name: String,
    val park: String?,
    val fraction: Double,
    val status: TrailStatus,
    val lengthMi: Double,
)

/** Search + filters + sort of the Trails list. [statuses] empty = any status. */
data class TrailListQuery(
    val search: String = "",
    val statuses: Set<TrailStatus> = emptySet(),
    val park: ParkFilter = ParkFilter.All,
    val sort: TrailSort = TrailSort.NAME,
) {
    val hasFilters: Boolean get() = statuses.isNotEmpty() || park != ParkFilter.All
    val hasSearch: Boolean get() = TrailListLogic.fold(search).isNotEmpty()

    fun withoutFilters(): TrailListQuery = copy(statuses = emptySet(), park = ParkFilter.All)
}

/** Pure Trails-list logic (product F3): accent/case-insensitive search, filters, stable sorts. */
object TrailListLogic {
    /**
     * Search/sort key: lowercase, Latin diacritics stripped, apostrophes dropped, whitespace collapsed
     * ("Nute’s Pond" → "nutes pond", "T’Chookwop" → "tchookwop").
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        var space = false
        for (ch in s.lowercase()) {
            val c = DIACRITICS[ch] ?: ch.toString()
            when {
                ch == '\'' || ch == '’' || ch == '‘' || ch == '`' -> Unit
                ch.isWhitespace() -> space = sb.isNotEmpty()
                else -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun matches(item: TrailListItem, query: TrailListQuery): Boolean {
        if (query.statuses.isNotEmpty() && item.status !in query.statuses) return false
        when (val p = query.park) {
            ParkFilter.All -> Unit
            ParkFilter.NoPark -> if (item.park != null) return false
            is ParkFilter.Named -> if (item.park != p.park) return false
        }
        val q = fold(query.search)
        if (q.isEmpty()) return true
        return q in fold(item.name) || (item.park != null && q in fold(item.park))
    }

    private val byName: Comparator<TrailListItem> = compareBy<TrailListItem> { fold(it.name) }.thenBy { it.trailId }

    fun comparator(sort: TrailSort): Comparator<TrailListItem> = when (sort) {
        TrailSort.NAME -> byName
        TrailSort.PROGRESS -> compareByDescending<TrailListItem> { it.fraction }.then(byName)
        TrailSort.LENGTH -> compareBy<TrailListItem> { it.lengthMi }.then(byName)
    }

    fun apply(items: List<TrailListItem>, query: TrailListQuery): List<TrailListItem> =
        items.filter { matches(it, query) }.sortedWith(comparator(query.sort))

    /** Distinct park names, A–Z (accent-insensitive). */
    fun parks(items: List<TrailListItem>): List<String> =
        items.mapNotNull { it.park }.distinct().sortedBy { fold(it) }

    /** True when nothing has been walked yet (`trails_first_walk_banner`). */
    fun noProgressYet(items: List<TrailListItem>): Boolean = items.all { it.fraction <= 0.0 }

    private val DIACRITICS: Map<Char, String> = buildMap {
        fun map(chars: String, to: String) = chars.forEach { put(it, to) }
        map("àáâãäåāăą", "a"); map("æ", "ae"); map("çćĉċč", "c"); map("ďđ", "d"); map("èéêëēĕėęě", "e")
        map("ĝğġģ", "g"); map("ĥħ", "h"); map("ìíîïĩīĭįı", "i"); map("ĵ", "j"); map("ķ", "k"); map("ĺļľŀł", "l")
        map("ñńņňŉ", "n"); map("òóôõöøōŏő", "o"); map("œ", "oe"); map("ŕŗř", "r"); map("śŝşš", "s"); map("ß", "ss")
        map("ţťŧ", "t"); map("ùúûüũūŭůűų", "u"); map("ŵ", "w"); map("ýÿŷ", "y"); map("źżž", "z"); map("þ", "th"); map("ð", "d")
    }
}
