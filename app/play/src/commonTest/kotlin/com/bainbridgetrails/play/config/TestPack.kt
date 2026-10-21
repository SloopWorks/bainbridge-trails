package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.schedule.FakePlaceIndex
import com.bainbridgetrails.play.schedule.FakePlaceIndex.Companion.CEMETERY
import com.bainbridgetrails.play.schedule.FakePlaceIndex.Companion.MEMORIAL
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive

/** A small valid pack over [FakePlaceIndex.island]: one item per v1 type. Fixtures derive from it with `copy`. */
object TestPack {
    val index = FakePlaceIndex.island()

    fun on(vararg tokens: String) = tokens.map { JsonPrimitive(it) }
    fun weekly(day: String, from: String, to: String, days: Int) =
        Recurrence(every = Every.week, on = on(day), from = from, to = to, days = days)

    private val always = Recurrence(every = Every.always)
    private fun copy(title: String) = Copy(title, "A short body.")
    private fun a11y(label: String) = A11y(label, "$label, announced.")

    val treasure = Item(
        id = "treasure-weekly-roads", type = ItemType.treasure, lore = "treasure", goals = listOf(Goal.roads),
        `when` = weekly("mon", "05:00", "24:00", 7),
        where = mapOf(Goal.roads to Placement(pick = 2, from = "pool:play-safe-roads")), count = 2,
        tiers = listOf(Tier(TierName.sea_glass, 25, 1), Tier(TierName.agate, 40, 1)),
        repeat = Repeat.weekly_set, resolve = Resolve(ResolveBy.proximity, radiusM = 25),
        copy = copy("{tier} on {place}"), a11y = a11y("{tier}, {marks} marks"), fx = "treasure_glint",
    )
    val egg = Item(
        id = "egg-frog", type = ItemType.egg, lore = "frog", hidden = true, `when` = always,
        where = mapOf(Goal.roads to Placement(at = "place:frog-rock"), Goal.trails to Placement(at = "place:frog-rock")),
        marks = 20, resolve = Resolve(ResolveBy.proximity, radiusM = 15), copy = copy("Frog egg"), a11y = a11y("Easter egg"),
    )
    val riddle = Item(
        id = "riddle-forest", type = ItemType.riddle, lore = "riddle", goals = listOf(Goal.trails), `when` = always,
        where = mapOf(Goal.trails to Placement(along = "pool:forest", pick = 1)), stars = 2,
        resolve = Resolve(ResolveBy.riddle, answer = "pool:forest", minM = 100),
        copy = copy("Riddle"), a11y = a11y("Riddle, 2 stars"), fx = "riddle_marker",
    )
    val kraken = Item(
        id = "kraken-roads", type = ItemType.obstacle, lore = "kraken", goals = listOf(Goal.roads), hero = true, priority = 60,
        `when` = weekly("thu", "06:00", "20:00", 4),
        where = mapOf(Goal.roads to Placement(rotate = listOf("pool:coast-k1"), epoch = LocalDate(2026, 10, 29))),
        bands = listOf(Band(60, 60), Band(80, 70), Band(95, 80)), repeat = Repeat.per_attack,
        resolve = Resolve(ResolveBy.segments, minShare = 0.6), copy = copy("The kraken is ashore"),
        a11y = a11y("Kraken at {coast}"), fx = "kraken_attack",
    )
    val challenge = Item(
        id = "ch-sweep", type = ItemType.challenge, lore = "challenge", `when` = weekly("sat", "00:00", "24:00", 2),
        marks = 100, resolve = Resolve(ResolveBy.challenge),
        challenge = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.completed, target = mapOf(Goal.trails to 3, Goal.roads to 5)),
        copy = copy("Weekend sweep"), a11y = a11y("Weekend sweep"),
    )
    val lore = Item(
        id = "lore-frog", type = ItemType.lore, lore = "frog", `when` = always,
        where = mapOf(Goal.roads to Placement(at = "place:frog-rock")), goals = listOf(Goal.roads),
        marks = 15, resolve = Resolve(ResolveBy.proximity, radiusM = 60), copy = copy("Frog Rock"), a11y = a11y("Frog Rock"),
        fx = "frog_rock",
    )
    val memorial = Item(
        id = "lore-memorial", type = ItemType.lore, lore = "remembrance", `when` = always,
        where = mapOf(Goal.roads to Placement(at = "place:memorial-card"), Goal.trails to Placement(at = "place:memorial-card")),
        marks = 0, resolve = Resolve(ResolveBy.none), copy = copy("Nidoto Nai Yoni"), a11y = a11y("Remembrance"),
    )
    val ferry = Item(
        id = "ferry-sprite", type = ItemType.ambient, lore = "ferry",
        `when` = Recurrence(
            every = Every.day, from = "05:00", to = "23:00",
            anchor = Anchor(AnchorAt.ferry_depart_bainbridge, before = "0m", after = "35m", pick = AnchorPick.all),
        ),
        where = mapOf(Goal.roads to Placement(at = "place:frog-rock")), goals = listOf(Goal.roads),
        resolve = Resolve(ResolveBy.none), copy = copy("Ferry crossing"), a11y = a11y("Ferry"), fx = "ferry_crossing",
    )
    val owl = Item(
        id = "lore-owl", type = ItemType.creature, scope = Scope.personal, lore = "forest", goals = listOf(Goal.trails),
        enabled = false, `when` = Recurrence(every = Every.day, from = "sunrise+30m", to = "sunset-30m"),
        where = mapOf(Goal.trails to Placement(along = "pool:forest", pick = 1)), marks = 15, repeat = Repeat.per_season,
        resolve = Resolve(ResolveBy.proximity, radiusM = 60), copy = copy("Barred owl"), a11y = a11y("Barred owl"),
    )

    val base = PlayConfig(
        schema = 1, version = "2027.01.0", tz = "America/Los_Angeles", islandSeed = "5eed1b1a2026c0de",
        windows = mapOf(
            "exclusion-remembrance" to Recurrence(
                every = Every.year, inMonths = listOf(MonthId.mar), on = listOf(JsonPrimitive(30)), from = "00:00", to = "24:00",
            ),
        ),
        places = mapOf(
            "frog-rock" to Place(47.6910, -122.5245, radiusM = 60),
            "memorial-card" to Place(47.6153, -122.5100, radiusM = 30),
            "in-memorial-zone" to Place(47.6150, -122.5108, radiusM = 15),
        ),
        pools = mapOf(
            "coast-k1" to Pool(listOf("stretch:road-safe-coast", "stretch:road-safe-a"), Goal.roads),
            "forest" to Pool(listOf("park:Grand Forest"), Goal.trails),
            "west" to Pool(listOf("trail:trail-west-loop-trail"), Goal.trails),
        ),
        quietZones = listOf(
            QuietZone("exclusion-memorial", "trail:$MEMORIAL", 200),
            QuietZone("blakely-cemetery", "trail:$CEMETERY", 100),
        ),
        excludedParks = listOf("Pritchard Park"),
        quietDays = listOf("exclusion-remembrance"),
        items = listOf(treasure, egg, riddle, kraken, challenge, lore, memorial, ferry, owl),
    )

    fun PlayConfig.replace(item: Item): PlayConfig = copy(items = items.map { if (it.id == item.id) item else it })
    fun PlayConfig.plus(item: Item): PlayConfig = copy(items = items + item)
    fun PlayConfig.withPool(id: String, vararg members: String, goal: Goal? = null): PlayConfig =
        copy(pools = pools + (id to Pool(members.toList(), goal)))
}
