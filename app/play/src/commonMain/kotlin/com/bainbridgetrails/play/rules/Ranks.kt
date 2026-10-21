package com.bainbridgetrails.play.rules

/** One rung of the ladder: [number] 1..11 (the `play_rank_<n>` copy keys), [title] in English, [from] marks. */
data class Rank(val number: Int, val title: String, val from: Int)

/** product §3.7.1: cumulative marks per goal; the same ladder in both apps. */
object Ranks {
    val LADDER: List<Rank> = listOf(
        "Walk-on" to 0, "Dock Walker" to 250, "Beachcomber" to 750, "Salal Scout" to 1_500,
        "Madrona Wanderer" to 3_000, "Heron Watcher" to 5_000, "Fog Navigator" to 8_000, "Tide Reader" to 12_000,
        "Kraken Tamer" to 17_000, "Island Cartographer" to 23_000, "Keeper of the Chart" to 30_000,
    ).mapIndexed { i, (title, from) -> Rank(i + 1, title, from) }

    fun of(marks: Int): Rank = LADDER.last { marks >= it.from }

    /** The next rank, or null at the top. */
    fun next(marks: Int): Rank? = LADDER.firstOrNull { it.from > marks }

    /** Marks still needed for [next], or null at the top. */
    fun toNext(marks: Int): Int? = next(marks)?.let { it.from - marks }

    /** The rank reached when a total goes from [before] to [after], or null when it stays (PF6-3). */
    fun rankUp(before: Int, after: Int): Rank? = of(after).takeIf { it.number > of(before).number }
}
