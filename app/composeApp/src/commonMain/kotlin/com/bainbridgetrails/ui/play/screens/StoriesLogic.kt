package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.play.rules.Ranks

/** Ranks screen rows (ux.md § 2.7, PF12): earned below the current rank, the current one, future above. */
enum class RankRowState { Earned, Current, Future }

object RanksLogic {
    fun rows(total: Int): List<Pair<Rank, RankRowState>> {
        val current = Ranks.of(total)
        return Ranks.LADDER.map { r ->
            r to when {
                r.number < current.number -> RankRowState.Earned
                r.number == current.number -> RankRowState.Current
                else -> RankRowState.Future
            }
        }
    }
}

/** An island story card (ux.md § 3.17): [met] = found once; remembrance cards last, no art. */
data class StoryCard(val item: Item, val met: Boolean) {
    val remembrance: Boolean get() = item.lore == Marks.REMEMBRANCE
}

data class StoriesModel(val cards: List<StoryCard>, val met: Int, val total: Int)

/** Island stories: every enabled lore card of the goal, readable anytime; eggs are never listed. */
object StoriesLogic {
    fun model(config: PlayConfig?, goal: Goal, found: Set<String>): StoriesModel {
        val lore = config?.items.orEmpty().filter { it.type == ItemType.lore && it.enabled && goal in it.goals }
        val cards = lore.map { item -> StoryCard(item, found.any { it == item.id || it.startsWith("${item.id}@") || it.startsWith("${item.id}#") }) }
            .sortedBy { it.remembrance }
        return StoriesModel(cards, cards.count { it.met }, cards.size)
    }
}
