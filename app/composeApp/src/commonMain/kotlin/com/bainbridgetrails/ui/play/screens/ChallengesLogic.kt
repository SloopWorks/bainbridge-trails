package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.net.PlayChallengeDto
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.play.TrailChallengeStatus
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.ChallengeScope
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.TrailRewardFormat
import com.bainbridgetrails.ui.play.components.TrailValue
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** ux.md § 2.5 states. */
enum class ChallengeState { Active, Done, Ended, Upcoming }

/** Your community standing on an island challenge: a band word and how many share it. Nothing else, ever (PF9-4, PD-4). */
data class ChallengeBand(val band: String, val count: Int)

/**
 * One ChallengeCard. [title] / [body] are the pack's copy (placeholders filled); [windowStart] / [windowEnd] island
 * dates, inclusive (a window ending Mon 00:00 ends on Sunday). [progress] / [target] are counts (solo: your completed
 * features; island: stretches repelled of stretches attacked), null when unknown. [reward] null for an ended card.
 * Trails `climb` / `parks` ([metric], plan D5): progress / target in the compare unit (climb **decimetres**, parks a
 * count); a climb card also carries both as shown in the Settings unit ([climbProgress] clamped, [climbTarget]
 * nearest; trails ux § 1, § 7), formatted here, outside composition.
 */
data class ChallengeCardModel(
    val itemId: String,
    val occKey: String,
    val islandScope: Boolean,
    val title: String,
    val body: String,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val state: ChallengeState,
    val progress: Int?,
    val target: Int?,
    val reward: Int?,
    val band: ChallengeBand?,
    val metric: ChallengeMetric? = null,
    val climbProgress: TrailValue? = null,
    val climbTarget: TrailValue? = null,
)

data class ChallengesModel(val solo: List<ChallengeCardModel>, val island: List<ChallengeCardModel>, val ended: List<ChallengeCardModel>) {
    val isEmpty: Boolean get() = solo.isEmpty() && island.isEmpty() && ended.isEmpty()
}

/** The strings a card's lines are built from (values/strings.xml; production passes `stringResource`s). */
data class ChallengeCopy(
    /** `play_challenge_window` "%1$s – %2$s". */
    val window: String,
    /** `play_challenge_progress` "%1$s of %2$s". */
    val progress: String,
    /** `play_kraken_island_progress` "%1$d of %2$d stretches sent back by islanders". */
    val islandProgress: String,
    /** `play_challenge_band` "You were in the %1$s band, with %2$d others" + the three band words. */
    val band: String,
    val bandTop: String,
    val bandMiddle: String,
    val bandStarting: String,
    /** `play_challenge_progress_climb` "%1$s of %2$s climbed" (null: [progress]). */
    val progressClimb: String? = null,
    /** `play_challenge_progress_parks` resolved with the card's target as quantity, "%1$d of %2$d parks" (null: [progress]). */
    val progressParks: String? = null,
)

/**
 * The goal vocabulary for the pack's `{goal.item}` / `{goal.items}` copy tokens (schedule.md § 4.7): production passes
 * `play_goal_item` / `play_goal_items` through `LocalGoal` (roads twins "road" / "roads"), so the words never mix.
 */
data class GoalNouns(val item: String, val items: String) {
    fun fill(text: String): String = text.replace("{goal.items}", items).replace("{goal.item}", item)
}

/**
 * A card's lines: [window] days only, never a clock or a countdown (PP-8, PD-4). [progressSpoken] is [progress] with
 * spoken units for the merged node ("311 of 492 feet climbed", trails ux § 7).
 */
data class ChallengeCardText(val window: String, val progress: String?, val band: String?, val progressSpoken: String? = progress)

/**
 * The Challenges screen and ChallengeCard (ux.md § 2.5, § 3.16; product § 4.5): this week's challenges from the agenda
 * (active, then upcoming), merged with the server's `{band, count}` standings (`GET /v1/play/challenges`) and, for
 * solo progress when the server has not answered, the Logbook's completions inside the window. Ended challenges of
 * the last 7 days come from the server list. Nothing timed: windows are island days. Trails `climb` / `parks` cards
 * ([trail], `PlayRepository.trailChallenges` over [trailOccurrences], plan D5) take the personal target and local
 * progress from their status, `{target}` in [units]; a hidden status (nothing left) has no card (trails ux § 7).
 */
object ChallengesLogic {
    fun model(
        config: PlayConfig,
        goal: Goal,
        agenda: PlayAgenda?,
        server: PlayChallengesDto?,
        logbook: List<LogEntry>,
        now: Instant,
        nouns: GoalNouns,
        trail: Map<String, TrailChallengeStatus> = emptyMap(),
        units: Units = Units.MILES,
    ): ChallengesModel {
        val items = config.items.associateBy { it.id }
        fun cardAt(item: Item, occ: String, start: Instant, end: Instant?, dto: PlayChallengeDto?) =
            card(item, goal, occ, start, end, now, dto, logbook, nouns, trail[occ], units)
        val byOcc = server?.current.orEmpty().associateBy { it.occ }
        val cards = ArrayList<ChallengeCardModel>()
        // A hidden trails status (nothing left, D5) counts as seen: no card from the agenda or the server list.
        val seen = trail.filterValues { it.hidden }.keys.toHashSet()
        agenda?.active.orEmpty().map { it.occurrence }.filter { it.type == ItemType.challenge && !it.hidden }.forEach { o ->
            val item = items[o.itemId] ?: return@forEach
            if (seen.add(o.key)) cards += cardAt(item, o.key, o.start, o.end, byOcc[o.key] ?: server.forItem(o.itemId))
        }
        val weekEnd = IslandTime.instantOf(IslandTime.weekId(IslandTime.dateOf(now)), kotlinx.datetime.LocalTime(0, 0)) + 7.days
        agenda?.upcoming.orEmpty().filter { it.type == ItemType.challenge && it.start < weekEnd }.forEach { u ->
            val item = items[u.itemId] ?: return@forEach
            if (seen.add(u.key)) cards += cardAt(item, u.key, u.start, u.end, null)
        }
        server?.current.orEmpty().filter { it.occ !in seen }.forEach { dto ->
            val item = items[dto.id] ?: return@forEach
            val start = parse(dto.windowStart) ?: return@forEach
            val end = parse(dto.windowEnd) ?: return@forEach
            if (end > now || end < now - 7.days) return@forEach
            seen += dto.occ
            cards += cardAt(item, dto.occ, start, end, dto)
        }
        val (ended, live) = cards.partition { it.state == ChallengeState.Ended }
        return ChallengesModel(live.filter { !it.islandScope }, live.filter { it.islandScope }, ended)
    }

    private fun PlayChallengesDto?.forItem(id: String): PlayChallengeDto? = this?.current?.firstOrNull { it.id == id && it.open }

    /**
     * What the trails challenge statuses read from the Logbook: its `complete` entries (feature, island date; progress P,
     * and a new completion is when the sticky completed set grows). `rememberTrailChallenges` re-reads when this changes,
     * not on every find or board change. Null = no Logbook yet.
     */
    fun completionKey(logbook: Logbook?): List<Pair<String?, LocalDate>>? =
        logbook?.entries?.mapNotNull { e -> (e.contentId to e.date).takeIf { e.kind == LogKind.complete } }

    /**
     * The challenge occurrences to ask `PlayRepository.trailChallenges` about: the active ones and this agenda's
     * upcoming ones (as window-only occurrences: no progress yet, so the target is what remains now), so a card,
     * chip count or What's On row with nothing left is hidden wherever it shows.
     */
    fun trailOccurrences(agenda: PlayAgenda): List<Occurrence> {
        val active = agenda.active.map { it.occurrence }.filter { it.type == ItemType.challenge }
        val keys = active.mapTo(HashSet()) { it.key }
        return active + agenda.upcoming.filter { it.type == ItemType.challenge && it.key !in keys }.map { u ->
            Occurrence(
                key = u.key, itemId = u.itemId, type = u.type, scope = Scope.personal, start = u.start, end = u.end, hero = false,
                placements = emptyList(), marks = 0, variant = null, sensable = false, daySpans = emptyList(),
                resolvable = emptyList(), tideGated = false,
            )
        }
    }

    private fun card(
        item: Item,
        goal: Goal,
        occ: String,
        start: Instant,
        end: Instant?,
        now: Instant,
        dto: PlayChallengeDto?,
        logbook: List<LogEntry>,
        nouns: GoalNouns,
        status: TrailChallengeStatus?,
        units: Units,
    ): ChallengeCardModel {
        val spec = item.challenge
        val island = spec?.scope == ChallengeScope.island
        val metric = spec?.metric?.takeIf { !island && (it == ChallengeMetric.climb || it == ChallengeMetric.parks) }
        val startDate = IslandTime.dateOf(start)
        val endDate = end?.let { IslandTime.dateOf(it - 1.milliseconds) } ?: startDate
        val (progress, target) = if (island) {
            dto?.island?.let { it.repelled to it.stretches } ?: (null to null)
        } else if (metric != null) {
            // D5: the personal target (local status, else the server's, else the pack default); progress as ch-sweep.
            val t = status?.target ?: dto?.target?.takeIf { it > 0 } ?: spec.target?.get(goal)?.let { TrailRewards.compareTarget(metric, it) }
            (dto?.progress ?: status?.progress) to t
        } else {
            val t = spec?.target?.get(goal) ?: dto?.target?.takeIf { it > 0 }
            val local = if (spec?.metric == ChallengeMetric.completed) {
                logbook.count { it.kind == LogKind.complete && it.date in startDate..endDate }
            } else {
                null
            }
            (dto?.progress ?: local) to t
        }
        val done = progress != null && target != null && target > 0 && progress >= target
        val state = when {
            now < start -> ChallengeState.Upcoming
            done -> ChallengeState.Done
            end != null && now >= end -> ChallengeState.Ended
            else -> ChallengeState.Active
        }
        val band = if (island) {
            dto?.you?.band?.let { b -> dto.bands.firstOrNull { it.band == b }?.count?.let { ChallengeBand(b, it) } }
        } else {
            null
        }
        val shownProgress = if (state == ChallengeState.Upcoming) null else progress
        val climbTarget = if (metric == ChallengeMetric.climb && target != null) TrailRewardFormat.climbStep(target / 10.0, units) else null
        val climbProgress = if (climbTarget != null && target != null && shownProgress != null) {
            TrailRewardFormat.climbProgress(shownProgress / 10.0, target / 10.0, units)
        } else {
            null
        }
        val body = nouns.fill(item.copy.body.replace("{target}", climbTarget?.text ?: target?.toString().orEmpty()))
        return ChallengeCardModel(
            itemId = item.id,
            occKey = occ,
            islandScope = island,
            title = item.copy.title,
            body = body,
            windowStart = startDate,
            windowEnd = endDate,
            state = state,
            progress = shownProgress,
            target = target,
            reward = if (state == ChallengeState.Ended) null else item.marks.takeIf { it > 0 },
            band = band,
            metric = metric,
            climbProgress = climbProgress,
            climbTarget = climbTarget,
        )
    }

    /**
     * The card's lines from [copy]: window days only, progress as text, the band line only when its count is known.
     * Climb: "311 of 492 ft climbed" from the card's shown values (clamped, trails ux § 1), spoken with [spokenTarget]
     * (the target through its `play_unit_*_a11y` plural, "492 feet"); parks: [ChallengeCopy.progressParks], capped.
     */
    fun text(card: ChallengeCardModel, copy: ChallengeCopy, spokenTarget: String? = null): ChallengeCardText {
        val window = PlayFormat.fill(copy.window, PlayFormat.day(card.windowStart), PlayFormat.day(card.windowEnd))
        val p = card.progress
        val t = card.target
        val cp = card.climbProgress
        val ct = card.climbTarget
        val progress = when {
            p == null || t == null -> null
            card.islandScope -> PlayFormat.fill(copy.islandProgress, p, t)
            cp != null && ct != null -> PlayFormat.fill(copy.progressClimb ?: copy.progress, cp.number, ct.text)
            card.metric == ChallengeMetric.parks -> PlayFormat.fill(copy.progressParks ?: copy.progress, p.coerceAtMost(t), t)
            else -> PlayFormat.fill(copy.progress, p.coerceAtMost(t), t)
        }
        val progressSpoken = if (progress != null && cp != null && ct != null && spokenTarget != null) {
            PlayFormat.fill(copy.progressClimb ?: copy.progress, cp.number, spokenTarget)
        } else {
            progress
        }
        val band = card.band?.let { b ->
            val word = when (b.band) {
                "top" -> copy.bandTop
                "middle" -> copy.bandMiddle
                "starting" -> copy.bandStarting
                else -> return@let null
            }
            PlayFormat.fill(copy.band, word, b.count)
        }
        return ChallengeCardText(window, progress, band, progressSpoken)
    }

    /** `2026-10-29T06:00-07:00` (seconds optional) → instant; null when unreadable. */
    internal fun parse(s: String): Instant? {
        if (s.isBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching {
                val i = s.indexOfAny(charArrayOf('+', '-', 'Z'), startIndex = 16)
                if (i < 0) null else Instant.parse(s.substring(0, i) + ":00" + s.substring(i))
            }.getOrNull()
    }
}
