package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Scope

/**
 * The density caps (schedule.md §3.4 #4): at most `maxPlacementsPerCell` placements per chart square for the goal and
 * at most `maxVisiblePlacements` island-wide, filled in a fixed order: heroes, challenges, riddles, island treasure,
 * island lore, eggs, other island items, then (v2) personal items, so personal content never displaces shared content.
 * Within a class: by item id, then occurrence key, then placement index. A hero's multi-segment obstacle counts once
 * per square. Overflow is dropped for the day, deterministically. Ambient decor, hidden items (eggs: nothing on the map
 * until found) and placements without a point are not counted.
 */
object Density {
    data class Result(val occurrences: List<Occurrence>, val dropped: List<String>)

    /** Fill-order class: lower fills first. */
    fun rank(occ: Occurrence): Int = when {
        occ.scope == Scope.personal -> 7
        occ.hero -> 0
        occ.type == ItemType.challenge -> 1
        occ.type == ItemType.riddle -> 2
        occ.type == ItemType.treasure -> 3
        occ.type == ItemType.lore -> 4
        occ.type == ItemType.egg -> 5
        else -> 6
    }

    fun apply(occurrences: List<Occurrence>, maxPerCell: Int, maxVisible: Int): Result {
        val order = occurrences.sortedWith(compareBy<Occurrence>({ rank(it) }, { it.itemId }, { it.key }))
        val perCell = HashMap<String, Int>()
        var visible = 0
        val dropped = ArrayList<String>()
        val kept = HashMap<String, Occurrence>(occurrences.size * 2)
        for (o in order) {
            if (o.type == ItemType.ambient || o.hidden) {
                kept[o.key] = o
                continue
            }
            val seenSquares = HashSet<String>()
            val keep = ArrayList<ResolvedPlacement>(o.placements.size)
            for (p in o.placements) {
                val sq = p.square
                if (sq == null) {
                    keep += p
                    continue
                }
                // a hero obstacle's placements in one square are one obstacle there
                if (o.hero && o.type == ItemType.obstacle && sq in seenSquares) {
                    keep += p
                    continue
                }
                val n = perCell[sq] ?: 0
                if (n >= maxPerCell || visible >= maxVisible) {
                    dropped += p.key
                    continue
                }
                perCell[sq] = n + 1
                visible++
                seenSquares += sq
                keep += p
            }
            kept[o.key] = if (keep.size == o.placements.size) o else o.copy(placements = keep)
        }
        // keep the caller's order
        return Result(occurrences.map { kept.getValue(it.key) }, dropped)
    }
}
