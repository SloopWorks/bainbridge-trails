@file:Suppress("EnumEntryName", "EnumNaming") // enum constants are the JSON keywords (schedule.md §4.3)

package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.geo.GeoPoint
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * The content pack `play-content.json`, schema 1 (schedule.md §4.2–§4.6; this file is the normative definition).
 * Decoding is strict about values (a wrong enum or type is an error, never a silent default) and lenient about keys
 * (`_note`, `_v2` and future fields are ignored). Semantic checks live in [PlayConfigValidator]; evaluation of
 * recurrences is the scheduler's (L1-5). Time expressions stay strings here and are parsed by [TimeExpr].
 */

@Serializable
data class PlayConfig(
    val schema: Int,
    val version: String,
    /**
     * The pack epoch: the start date of `every: "always"` items without `notBefore` (schedule.md §4.4), and week 0 of
     * the treasure set's `everyNthWeek` count. Null in packs that predate it.
     */
    val epoch: LocalDate? = null,
    val minAppBuild: Int = 0,
    val tz: String,
    val islandSeed: String,
    val kill: Kill = Kill(),
    val rules: Rules = Rules(),
    val caps: Caps = Caps(),
    val climate: List<SeasonSpan> = DEFAULT_CLIMATE,
    val aggregates: Aggregates = Aggregates(),
    val windows: Map<String, Recurrence> = emptyMap(),
    val places: Map<String, Place> = emptyMap(),
    val pools: Map<String, Pool> = emptyMap(),
    val quietZones: List<QuietZone> = emptyList(),
    val excludedParks: List<String> = emptyList(),
    val quietDays: List<String> = emptyList(),
    val pauses: List<Pause> = emptyList(),
    val ferry: Ferry? = null,
    val tides: TideRef? = null,
    val items: List<Item>,
) {
    /**
     * [item]'s `when` with a `{"window": id}` reference resolved: the named window's fields, overridden by every field
     * given inline (schedule.md §4.4 semantics 4). Null when the window does not exist.
     */
    fun effectiveWhen(item: Item): Recurrence? {
        val w = item.`when`
        val name = w.window ?: return w
        val base = windows[name] ?: return null
        return Recurrence(
            window = null,
            every = w.every ?: base.every,
            on = w.on.ifEmpty { base.on },
            inMonths = w.inMonths.ifEmpty { base.inMonths },
            dates = w.dates.ifEmpty { base.dates },
            fallback = w.fallback ?: base.fallback,
            from = w.from ?: base.from,
            to = w.to ?: base.to,
            days = w.days ?: base.days,
            season = w.season.ifEmpty { base.season },
            except = w.except.ifEmpty { base.except },
            anchor = w.anchor ?: base.anchor,
        )
    }

    companion object {
        const val SUPPORTED_SCHEMA = 1
        const val ISLAND_TZ = "America/Los_Angeles"
        val DEFAULT_CLIMATE = listOf(SeasonSpan("wet", "10-15", "04-30"), SeasonSpan("dry", "07-01", "09-15"))

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = false
            isLenient = false
            explicitNulls = true
        }

        /**
         * Decodes a pack. `schema` is read first, so a pack of an unsupported schema is rejected ([Rule.SCHEMA])
         * before its (possibly unknown) shape is decoded; any decoding failure is one [Rule.PARSE] error.
         */
        fun decode(text: String): PlayConfigResult {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject
                    ?: return reject(Rule.PARSE, "$", "the pack is not a JSON object")
            } catch (e: SerializationException) {
                return reject(Rule.PARSE, "$", e.message ?: "invalid JSON")
            }
            val schema = (root["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (schema != SUPPORTED_SCHEMA) {
                return reject(Rule.SCHEMA, "schema", "schema ${root["schema"]} not supported (this app reads $SUPPORTED_SCHEMA)")
            }
            return try {
                PlayConfigResult.Ok(json.decodeFromJsonElement(serializer(), root))
            } catch (e: SerializationException) {
                reject(Rule.PARSE, "$", e.message ?: "decode failed")
            } catch (e: IllegalArgumentException) {
                reject(Rule.PARSE, "$", e.message ?: "decode failed")
            }
        }

        private fun reject(rule: Rule, path: String, message: String) =
            PlayConfigResult.Rejected(listOf(PlayConfigError(rule, path, message)))
    }
}

sealed interface PlayConfigResult {
    data class Ok(val config: PlayConfig) : PlayConfigResult
    data class Rejected(val errors: List<PlayConfigError>) : PlayConfigResult
}

@Serializable
enum class Goal { trails, roads }

@Serializable
enum class ItemType { treasure, egg, obstacle, riddle, challenge, lore, creature, ambient }

/** `personal` is v2. */
@Serializable
enum class Scope { island, personal }

@Serializable
enum class Repeat { once, weekly_set, per_attack, per_season }

@Serializable
enum class ReducedMotion { static_icon }

@Serializable
enum class TierName { sea_glass, agate, glass_float, ships_bell }

@Serializable
enum class ResolveBy { proximity, segments, riddle, challenge, none }

@Serializable
enum class ChallengeScope { solo, island }

/** v1: `completed`, `stretches_repelled`; v2 adds the other two. There is no timing metric (PD-4). */
@Serializable
enum class ChallengeMetric { completed, stretches_repelled, squares_lifted, new_metres }

@Serializable
enum class Every { day, week, month, year, dates, always }

@Serializable
enum class MonthId { jan, feb, mar, apr, may, jun, jul, aug, sep, oct, nov, dec;

    /** 1..12 */
    val number: Int get() = ordinal + 1
}

@Serializable
enum class AnchorAt {
    @SerialName("tide.high") tide_high,
    @SerialName("tide.low") tide_low,
    @SerialName("ferry.arrive.bainbridge") ferry_arrive_bainbridge,
    @SerialName("ferry.depart.bainbridge") ferry_depart_bainbridge,
    ;

    val isTide: Boolean get() = this == tide_high || this == tide_low
}

@Serializable
enum class AnchorPick { highest, lowest, all }

/** The one kill switch (PD-11). */
@Serializable
data class Kill(val all: Boolean = false, val types: List<ItemType> = emptyList(), val items: List<String> = emptyList())

/** `"HH:MM"` island-local clock span. */
@Serializable
data class ClockSpan(val from: String, val to: String)

/** §3.4 knobs. [roadSafety] may only be made stricter (validated). */
@Serializable
data class Rules(
    val playHours: ClockSpan = ClockSpan("06:00", "21:00"),
    val ambientHours: ClockSpan = ClockSpan("05:00", "23:00"),
    val heroMaxConcurrent: Int = 1,
    val maxPlacementsPerCell: Int = 3,
    val maxVisiblePlacements: Int = 40,
    /** 8 ft MLLW at 9445882 (product T-2, PD-8). */
    val krakenMaxTideM: Double = 2.44,
    val roadSafety: RoadSafety = RoadSafety(),
)

/** product §4.0.2 / PD-7, verbatim. Evaluated by `geo/RoadSafety.kt`. */
@Serializable
data class RoadSafety(
    val categories: List<String> = listOf("residential", "collector", "arterial"),
    val maxSpeedMph: Int = 25,
    val nullSpeedOnly: List<String> = listOf("residential"),
    val ownershipPrefix: String = "public_",
    val requirePublic: Boolean = true,
)

/** product §3.5, exactly (the server reads its own copy, never client values). */
@Serializable
data class Caps(
    val foundPerDay: Int = 400,
    val foundPerWeek: Int = 2800,
    val weekLateDays: Int = 7,
    val findGraceDays: Int = 2,
    val clockAheadMin: Int = 60,
    val keptConfigs: Int = 8,
)

/** A climate flavour span, `"MM-DD"` inclusive, may wrap the year end. */
@Serializable
data class SeasonSpan(val id: String, val from: String, val to: String)

/** Baked in at publish (PD-10). Square ids are chart-square ids `"row:col"`. */
@Serializable
data class Aggregates(val asOf: String? = null, val lowTrafficSquares: LowTrafficSquares = LowTrafficSquares())

@Serializable
data class LowTrafficSquares(val trails: List<String> = emptyList(), val roads: List<String> = emptyList())

/** [feature] = `"trail:<id>"` (buffer around every segment) or `"place:<id>"` (buffer around the point). */
@Serializable
data class QuietZone(val id: String, val feature: String, val bufferM: Int)

@Serializable
data class Place(
    val lat: Double,
    val lon: Double,
    val radiusM: Int = 40,
    val goals: List<Goal>? = null,
    /** True = the owner must confirm the spot on site before the items using it are enabled. */
    val verify: Boolean = false,
    /** Card coordinate when the trigger is snapped to a Play-safe line (product §4.0.2). */
    val landmark: GeoPoint? = null,
)

/** [members]: feature refs or place refs. A pool with no [goal] serves either goal (each member by its own kind). */
@Serializable
data class Pool(val members: List<String>, val goal: Goal? = null)

/** (v2) A top-level event pause: suppresses every item's placements on [features] during [window]. */
@Serializable
data class Pause(val window: String, val features: List<String>)

@Serializable
data class Ferry(
    val route: String,
    val crossingMin: Int,
    val terminals: Map<String, FerryTerminal> = emptyMap(),
    /** Kept raw: the runtime timetable is `time/FerryTimetable` (L1-3), fed from the pack by the scheduler. */
    val timetables: List<JsonObject> = emptyList(),
)

@Serializable
data class FerryTerminal(val lat: Double, val lon: Double, val verify: Boolean = false)

@Serializable
data class TideRef(val station: String, val years: List<Int>)

/**
 * A recurrence (schedule.md §4.4). In `windows` it is a full definition; in an item's `when` it may instead name a
 * [window] and override any of its fields. [on] is kept as raw JSON (weekday, month day or nth weekday tokens; see
 * [DayRule]) so a bad token is a located validation error rather than an opaque decode failure.
 */
@Serializable
data class Recurrence(
    val window: String? = null,
    val every: Every? = null,
    val on: List<JsonPrimitive> = emptyList(),
    @SerialName("in") val inMonths: List<MonthId> = emptyList(),
    val dates: List<LocalDate> = emptyList(),
    val fallback: Recurrence? = null,
    val from: String? = null,
    val to: String? = null,
    val days: Int? = null,
    val season: List<String> = emptyList(),
    val except: List<String> = emptyList(),
    val anchor: Anchor? = null,
) {
    /** [on] parsed; tokens that do not parse are dropped (the validator reports them). */
    fun dayRules(): List<DayRule> = on.mapNotNull { DayRule.parse(it) }
}

/** Day tokens of `on`: `"thu"`, `15` / `-1` (last day of month), `"3sat"` / `"last-sun"`. */
sealed interface DayRule {
    data class Weekday(val day: DayOfWeek) : DayRule
    data class MonthDay(val day: Int) : DayRule
    /** [n] 1..5, or -1 for `last-`. */
    data class NthWeekday(val n: Int, val day: DayOfWeek) : DayRule

    companion object {
        val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
        )
        private val NTH = Regex("^([1-5])(mon|tue|wed|thu|fri|sat|sun)$")
        private val LAST = Regex("^last-(mon|tue|wed|thu|fri|sat|sun)$")

        fun parse(p: JsonPrimitive): DayRule? {
            if (!p.isString) {
                val n = p.intOrNull ?: return null
                return if (n in 1..31 || n == -1) MonthDay(n) else null
            }
            val s = p.content
            WEEKDAYS[s]?.let { return Weekday(it) }
            NTH.matchEntire(s)?.let { return NthWeekday(it.groupValues[1].toInt(), WEEKDAYS.getValue(it.groupValues[2])) }
            LAST.matchEntire(s)?.let { return NthWeekday(-1, WEEKDAYS.getValue(it.groupValues[1])) }
            return null
        }
    }
}

@Serializable
data class Anchor(
    val at: AnchorAt,
    val before: String = "0m",
    val after: String = "0m",
    val pick: AnchorPick? = null,
    val minHeightM: Double? = null,
    val maxHeightM: Double? = null,
    val max: Int? = null,
    val fallback: ClockSpan? = null,
)

/**
 * One of (schedule.md §4.5): `{at}` · `{pick, from}` · `{along, pick?}` · `{segments, minM?}` · `{rotate, epoch}`.
 * The validator checks that exactly one shape is used.
 */
@Serializable
data class Placement(
    val at: String? = null,
    val pick: Int? = null,
    val from: String? = null,
    val along: String? = null,
    val segments: String? = null,
    val minM: Int? = null,
    val rotate: List<String>? = null,
    val epoch: LocalDate? = null,
) {
    /** Every ref this placement names. */
    val refs: List<String> get() = listOfNotNull(at, from, along, segments) + rotate.orEmpty()
}

@Serializable
data class Tier(
    val tier: TierName,
    val marks: Int,
    val count: Int,
    val everyNthWeek: Int? = null,
    @SerialName("else") val elseTier: TierName? = null,
)

@Serializable
data class Band(val pct: Int, val marks: Int)

@Serializable
data class Spread(
    val maxPerSquare: Int? = null,
    val minApartM: Int? = null,
    val lowTrafficShare: Double? = null,
    val quadrants: Int? = null,
)

@Serializable
data class Resolve(
    val by: ResolveBy,
    val radiusM: Int? = null,
    val minShare: Double? = null,
    val minM: Int? = null,
    /** riddle: a place, feature or pool ref. */
    val answer: String? = null,
)

@Serializable
data class ChallengeSpec(
    val scope: ChallengeScope,
    val metric: ChallengeMetric,
    val target: Map<Goal, Int>? = null,
    val minPlayersPerStretch: Int? = null,
    val retreatMarks: Int? = null,
)

@Serializable
data class Copy(val title: String, val body: String, val found: String? = null, val missed: String? = null)

@Serializable
data class A11y(val label: String, val announce: String, val reducedMotion: ReducedMotion = ReducedMotion.static_icon)

@Serializable
data class VariantWhen(
    val season: List<String> = emptyList(),
    val climate: List<String> = emptyList(),
    val window: String? = null,
    val month: List<MonthId> = emptyList(),
)

/** First match wins; variants never change rewards or windows. */
@Serializable
data class Variant(val `when`: VariantWhen, val fx: String? = null, val copy: Copy? = null)

@Serializable
data class Item(
    /** `[a-z0-9-]{3,48}`, unique, immutable once published (PD-11). */
    val id: String,
    val type: ItemType,
    val scope: Scope = Scope.island,
    /** Art/fx family: kraken, frog, ferry, treasure, riddle, remembrance, … */
    val lore: String? = null,
    /** false = draft. */
    val enabled: Boolean = true,
    val hero: Boolean = false,
    val priority: Int = 0,
    val yields: Boolean = false,
    val goals: List<Goal> = listOf(Goal.trails, Goal.roads),
    val `when`: Recurrence,
    /** Absent for a goal → the item is omitted for that goal. */
    val where: Map<Goal, Placement> = emptyMap(),
    val count: Int = 1,
    val marks: Int = 0,
    val tiers: List<Tier> = emptyList(),
    val bands: List<Band> = emptyList(),
    val stars: Int? = null,
    val repeat: Repeat = Repeat.once,
    val hidden: Boolean = false,
    val shore: Boolean = false,
    val canopy: Boolean = false,
    val spotCooldown: Int = 0,
    val spread: Spread? = null,
    val resolve: Resolve,
    val challenge: ChallengeSpec? = null,
    val variants: List<Variant> = emptyList(),
    val copy: Copy,
    val a11y: A11y,
    val sources: List<String> = emptyList(),
    /** snake_case effect id (effects.md §3). */
    val fx: String? = null,
    val notBefore: LocalDate? = null,
    val expires: LocalDate? = null,
)

