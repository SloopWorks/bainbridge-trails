package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Place
import com.bainbridgetrails.play.config.Placement
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.Pool
import com.bainbridgetrails.play.config.Recurrence
import com.bainbridgetrails.play.config.Every
import com.bainbridgetrails.play.config.Repeat
import com.bainbridgetrails.play.config.Resolve
import com.bainbridgetrails.play.config.ResolveBy
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.Tier
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.DayPlan
import com.bainbridgetrails.play.schedule.FakePlaceIndex
import com.bainbridgetrails.play.schedule.PlayScheduler
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.TrailRef
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * A deterministic pack for claim tests over [FakePlaceIndex.island] plus two kraken shore roads of known length
 * (Manitou 1,158 m, product §3.9 / PF7-1; Gertie Johnson 337 m). Week 2026-10-26: Thu 29 is a kraken day (K1 =
 * both roads), the island is in PDT (civil dusk ≈ 18:20).
 */
object ClaimFixture {
    const val MANITOU_M = 1158.0
    val manitouStart = GeoPoint(lat = 47.6500, lon = -122.5000)
    val manitouEnd = GeoMath.offset(manitouStart, MANITOU_M, 0.0)
    private val gertieStart = GeoPoint(lat = 47.6650, lon = -122.5060)

    val index: FakePlaceIndex = FakePlaceIndex.island().let { base ->
        val features = (base.features(Goal.trails) + base.features(Goal.roads)).map { info ->
            FakePlaceIndex.Feature(info, base.segments(if (info.goal == Goal.trails) TrailRef(info.id) else RoadRef(info.id)))
        }
        FakePlaceIndex(
            features + listOf(
                FakePlaceIndex.road("road-manitou-beach-drive-northeast", listOf(manitouStart, manitouEnd)),
                FakePlaceIndex.road("road-northeast-gertie-johnson-road", listOf(gertieStart, GeoMath.offset(gertieStart, 337.0, 0.0))),
            ),
        )
    }

    val chest = GeoPoint(lat = 47.6905, lon = -122.5248)
    val frogRock = GeoPoint(lat = 47.6910, lon = -122.5245)
    val inMemorialZone = GeoPoint(lat = 47.6150, lon = -122.5108)

    private val always = Recurrence(every = Every.always)

    val treasure = TestPack.treasure.copy(
        id = "treasure-test", where = mapOf(Goal.roads to Placement(at = "place:chest")), count = 1,
        tiers = listOf(Tier(TierName.sea_glass, 25, 1)), resolve = Resolve(ResolveBy.proximity, radiusM = 20),
    )
    val riddle = TestPack.riddle.copy(
        id = "riddle-frog", goals = listOf(Goal.roads, Goal.trails), stars = 1,
        where = mapOf(Goal.roads to Placement(at = "place:frog-rock"), Goal.trails to Placement(at = "place:frog-rock")),
        resolve = Resolve(ResolveBy.riddle, answer = "place:frog-rock", radiusM = 30),
    )
    /** Not valid in a published pack (the validator rejects anchors in a quiet zone): the evaluator must refuse it too. */
    val quietEgg = Item(
        id = "egg-quiet", type = ItemType.egg, `when` = always, goals = listOf(Goal.roads),
        where = mapOf(Goal.roads to Placement(at = "place:in-memorial-zone")), marks = 20, repeat = Repeat.once,
        resolve = Resolve(ResolveBy.proximity, radiusM = 15), copy = TestPack.egg.copy, a11y = TestPack.egg.a11y,
    )

    val config: PlayConfig = TestPack.base.copy(
        epoch = LocalDate(2026, 10, 26),
        places = TestPack.base.places + mapOf(
            "chest" to Place(chest.lat, chest.lon, radiusM = 20),
            "frog-rock" to Place(frogRock.lat, frogRock.lon, radiusM = 30),
            "in-memorial-zone" to Place(inMemorialZone.lat, inMemorialZone.lon, radiusM = 15),
        ),
        pools = TestPack.base.pools + mapOf(
            "coast-k1" to Pool(listOf("stretch:road-manitou-beach-drive-northeast", "stretch:road-northeast-gertie-johnson-road"), Goal.roads),
        ),
        items = listOf(treasure, riddle, quietEgg, TestPack.kraken, TestPack.challenge, TestPack.memorial),
    )

    /** A whole year of synthetic tides at Eagle Harbor, every event between [lowCm] and [highCm]. */
    fun tides(lowCm: Int, highCm: Int, year: Int = 2026): TideTable {
        val events = (0 until 365 * 4).joinToString(",") { i -> "[${if (i == 0) 0 else 360},${if (i % 2 == 0) highCm else lowCm},\"${if (i % 2 == 0) "H" else "L"}\"]" }
        return TideTable.parse("""{"station":"9445882","datum":"MLLW","units":"cm","year":$year,"t0":"$year-01-01T00:00Z","e":[$events]}""")
    }

    val lowTide = tides(40, 200)
    val highTide = tides(300, 400)

    fun scheduler(tides: TideTable? = lowTide) = PlayScheduler(config, index, tides, null)

    fun plan(date: LocalDate, tides: TideTable? = lowTide, goal: Goal = Goal.roads): DayPlan = scheduler(tides).dayPlan(date, goal)

    fun evaluator() = ClaimEvaluator(config, index)

    val thu: LocalDate = LocalDate(2026, 10, 29)

    fun at(date: LocalDate, h: Int, m: Int = 0): Instant = IslandTime.instantOf(date, LocalTime(h, m))

    /**
     * Fixes every [stepS] s at [speed] m/s along the straight line [from] → [to] (lateral offset [northM]), starting at
     * [start], each with [accuracyM].
     */
    fun walk(
        from: GeoPoint,
        to: GeoPoint,
        start: Instant,
        speed: Double = 1.3,
        stepS: Int = 2,
        accuracyM: Double? = 10.0,
        northM: Double = 0.0,
    ): List<GatedFix> {
        val len = GeoMath.distanceM(from, to)
        val n = (len / (speed * stepS)).toInt()
        return (0..n).map { i ->
            val f = if (n == 0) 0.0 else i.toDouble() / n
            val p = GeoPoint(lat = from.lat + f * (to.lat - from.lat), lon = from.lon + f * (to.lon - from.lon))
            val q = GeoMath.offset(p, 0.0, northM)
            GatedFix(start.toEpochMilliseconds() + (i * stepS * 1000.0).roundToLong(), q.lat, q.lon, accuracyM)
        }
    }

    /** An east-west pass by [p] from [beforeM] west to [afterM] east, [northM] north of it. */
    fun passBy(p: GeoPoint, start: Instant, northM: Double, speed: Double = 1.3, stepS: Int = 2, accuracyM: Double? = 10.0, beforeM: Double = 150.0, afterM: Double = 150.0) =
        walk(GeoMath.offset(p, -beforeM, 0.0), GeoMath.offset(p, afterM, 0.0), start, speed, stepS, accuracyM, northM)
}
