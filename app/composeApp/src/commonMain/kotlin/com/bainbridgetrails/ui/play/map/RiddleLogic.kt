package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.ui.play.components.PlayFormat

import com.bainbridgetrails.play.PlayState
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.schedule.ParkRef
import com.bainbridgetrails.play.schedule.PlaceRef
import com.bainbridgetrails.play.schedule.PoolRef
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.StretchRef
import com.bainbridgetrails.play.schedule.TrailRef
import kotlinx.datetime.LocalDate

/** The `play_riddle_*` strings the riddle logic formats. */
data class RiddleCopy(val wrong: String, val solvedStamp: String, val claimed: String) {
    companion object {
        fun english(s: (String) -> String) = RiddleCopy(s("play_riddle_wrong"), s("play_riddle_solved_stamp"), s("play_riddle_claimed"))
    }
}

/**
 * One answer attempt (ux.md §2.4): the trail / road ids under a map tap ([featureIds], the pure hit test), a landmark
 * picked from the list ([placeId]), or the tapped point ([at]: a place answer accepts a tap within
 * [RiddleLogic.PLACE_TAP_M] of the landmark).
 */
data class RiddlePick(val featureIds: List<String> = emptyList(), val placeId: String? = null, val at: GeoPoint? = null)

sealed interface RiddleOutcome {
    /** Not the answer: show `play_riddle_wrong`; nothing else changes (PF8-6). */
    data object Wrong : RiddleOutcome

    /** Solved from home: [state] has the solve and a 0-mark Logbook stamp (PF8-1). */
    data class Solved(val state: PlayState) : RiddleOutcome
}

enum class RiddleStage { UNSOLVED, SOLVED, CLAIMED }

/**
 * What the RiddleSheet shows (ux.md §2.4): [message] `play_riddle_wrong` after a wrong answer (no red, no ✕);
 * [stamp] the solved stamp line; [claimed] `play_riddle_claimed`; [answerRow] the two answer buttons ([mapPicker],
 * [listPicker]: the list is always offered, the screen-reader default path, PF8-5); [walkThere] the CTA after a solve.
 */
data class RiddleUi(
    val stage: RiddleStage,
    val message: String?,
    val stamp: String?,
    val claimed: String?,
    val answerRow: Boolean,
    val mapPicker: Boolean,
    val listPicker: Boolean,
    val walkThere: Boolean,
)

/** One row of the RiddleAnswerPicker: a trail / road (GoalVocabulary names) or a landmark. */
data class RiddleOption(val name: String, val pick: RiddlePick)

/**
 * Riddle answers (product § 4.4, PF8-1, PF8-5, PF8-6): solving from home is a stamp (0 marks), claiming is a walk
 * (L1-6's claim pass). Answer refs: `place:<id>`, `trail:` / `road:` / `stretch:<id>`, `park:<name>`, `pool:<id>`.
 */
object RiddleLogic {
    /** A map tap this close to a place answer's landmark (or its snapped trigger point) is that place. */
    const val PLACE_TAP_M = 120.0

    fun isCorrect(answer: String, pick: RiddlePick, config: PlayConfig, parkOf: (String) -> String?): Boolean =
        matches(Ref.parse(answer) ?: return false, pick, config, parkOf, depth = 0)

    private fun matches(ref: Ref, pick: RiddlePick, config: PlayConfig, parkOf: (String) -> String?, depth: Int): Boolean = when (ref) {
        is PlaceRef -> pick.placeId == ref.id || run {
            val place = config.places[ref.id] ?: return@run false
            val at = pick.at ?: return@run false
            val targets = listOfNotNull(place.landmark?.let { GeoPoint(lat = it.lat, lon = it.lon) }, GeoPoint(lat = place.lat, lon = place.lon))
            targets.any { GeoMath.distanceM(it, at) <= PLACE_TAP_M }
        }
        is TrailRef -> ref.id in pick.featureIds
        is RoadRef -> ref.id in pick.featureIds
        is StretchRef -> ref.featureId in pick.featureIds
        is ParkRef -> pick.featureIds.any { parkOf(it) == ref.name }
        is PoolRef -> depth < 2 && config.pools[ref.id]?.members.orEmpty().any { m ->
            Ref.parse(m)?.let { matches(it, pick, config, parkOf, depth + 1) } ?: (m in pick.featureIds)
        }
        else -> false
    }

    /**
     * Checks [pick] against [item]'s answer. Right → the item is solved on [today] and a `stamp` Logbook entry with 0
     * marks is added once (idempotent: a second solve adds nothing). Wrong → [RiddleOutcome.Wrong], state untouched.
     */
    fun answer(
        state: PlayState,
        item: Item,
        pick: RiddlePick,
        config: PlayConfig,
        parkOf: (String) -> String?,
        today: LocalDate,
        newId: () -> String,
    ): RiddleOutcome {
        val answer = item.resolve.answer ?: return RiddleOutcome.Wrong
        if (!isCorrect(answer, pick, config, parkOf)) return RiddleOutcome.Wrong
        return RiddleOutcome.Solved(solve(state, item, today, newId))
    }

    /** The solve itself (also the debug path): `solved[item] = today` and one 0-mark stamp. */
    fun solve(state: PlayState, item: Item, today: LocalDate, newId: () -> String): PlayState {
        if (item.id in state.solved) return state
        val stamp = LogEntry(newId(), LogKind.stamp, item.copy.title, LogCopy.why(LogKind.stamp), today, 0, contentId = "${item.id}#solved")
        return state.copy(solved = state.solved + (item.id to today.toString()), logbook = state.logbook.add(stamp))
    }

    fun stage(state: PlayState, item: Item, claimed: Boolean): RiddleStage = when {
        claimed -> RiddleStage.CLAIMED
        item.id in state.solved -> RiddleStage.SOLVED
        else -> RiddleStage.UNSOLVED
    }

    fun ui(stage: RiddleStage, wrong: Boolean, copy: RiddleCopy, claimedMarks: Int? = null): RiddleUi = when (stage) {
        RiddleStage.UNSOLVED -> RiddleUi(stage, if (wrong) copy.wrong else null, null, null, answerRow = true, mapPicker = true, listPicker = true, walkThere = false)
        RiddleStage.SOLVED -> RiddleUi(stage, null, copy.solvedStamp, null, answerRow = false, mapPicker = false, listPicker = false, walkThere = true)
        RiddleStage.CLAIMED -> RiddleUi(
            stage, null, null, PlayFormat.fill(copy.claimed, claimedMarks?.toString() ?: ""),
            answerRow = false, mapPicker = false, listPicker = false, walkThere = false,
        )
    }

    /**
     * The picker's rows (ux.md §2.4 `RiddleAnswerPicker`): [features] (id → name, the goal's trails or roads) plus the
     * pack's landmarks (each `place:` anchor of a lore card, named by the card), alphabetical, one row per name.
     */
    fun options(features: List<Pair<String, String>>, config: PlayConfig): List<RiddleOption> {
        val landmarks = config.items.filter { it.type == com.bainbridgetrails.play.config.ItemType.lore && it.lore != "remembrance" }
            .flatMap { item -> item.where.values.mapNotNull { (Ref.parse(it.at ?: return@mapNotNull null) as? PlaceRef)?.id }.distinct().map { it to item.copy.title } }
            .filter { (id, _) -> config.places[id]?.landmark != null }
            .map { (id, name) -> RiddleOption(name, RiddlePick(placeId = id)) }
        val lines = features.map { (id, name) -> RiddleOption(name, RiddlePick(featureIds = listOf(id))) }
        return (landmarks + lines).distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
    }

    /** Case-insensitive contains, the `SearchField` filter. */
    fun filter(options: List<RiddleOption>, query: String): List<RiddleOption> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) options else options.filter { q in it.name.lowercase() }
    }
}
