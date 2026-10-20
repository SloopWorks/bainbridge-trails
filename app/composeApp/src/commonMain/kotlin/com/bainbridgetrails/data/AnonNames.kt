package com.bainbridgetrails.data

import kotlin.random.Random

/**
 * Anonymous names "Adjective Noun NN" (product §4 minus Wandering/Thimbleberry, TL-9), generated on device.
 * The lists MUST match `backend/src/moderation/names.ts` exactly (server validates anon names against them);
 * `AnonNamesTest` checks parity by parsing that file.
 */
object AnonNames {
    val ADJECTIVES: List<String> = listOf(
        "Mossy", "Misty", "Foggy", "Rainy", "Drizzly", "Sunny", "Breezy", "Salty", "Sandy", "Rocky", "Shady", "Ferny",
        "Leafy", "Golden", "Silver", "Copper", "Amber", "Russet", "Emerald", "Velvet", "Gentle", "Nimble", "Steady", "Swift",
        "Curious", "Rambling", "Cheerful", "Hardy", "Brave", "Patient", "Clever", "Bright", "Dappled",
        "Speckled", "Cozy", "Lucky", "Merry", "Jolly", "Tidal", "Coastal", "Hidden", "Winding", "Mellow", "Plucky", "Calm",
        "Sleepy", "Hushed",
    )
    val NOUNS: List<String> = listOf(
        "Heron", "Madrona", "Salal", "Cedar", "Hemlock", "Alder", "Maple", "Fern", "Trillium", "Huckleberry", "Salmonberry",
        "Snowberry", "Oceanspray", "Kingfisher", "Eagle", "Osprey", "Raven", "Wren", "Towhee", "Junco",
        "Chickadee", "Flicker", "Owl", "Grebe", "Loon", "Sandpiper", "Merganser", "Otter", "Seal", "Orca", "Salmon",
        "Geoduck", "Oyster", "Crab", "Urchin", "Slug", "Newt", "Treefrog", "Chipmunk", "Raccoon", "Beaver", "Kelp",
        "Eelgrass", "Driftwood", "Tidepool", "Chanterelle", "Lichen",
    )

    private val ADJ_SET = ADJECTIVES.toSet()
    private val NOUN_SET = NOUNS.toSet()
    private val FORMAT = Regex("^(\\S+) (\\S+) ([1-9][0-9])$")

    /** Uniform over the 47 × 47 × 90 space (Shuffle regenerates all three parts). */
    fun generate(random: Random = Random.Default): String =
        "${ADJECTIVES[random.nextInt(ADJECTIVES.size)]} ${NOUNS[random.nextInt(NOUNS.size)]} ${10 + random.nextInt(90)}"

    /** Same check as the server's `isAnonName`. */
    fun isAnonName(s: String): Boolean {
        val m = FORMAT.matchEntire(s) ?: return false
        return m.groupValues[1] in ADJ_SET && m.groupValues[2] in NOUN_SET
    }
}
