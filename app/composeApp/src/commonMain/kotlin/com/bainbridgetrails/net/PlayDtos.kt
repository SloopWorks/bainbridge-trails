package com.bainbridgetrails.net

import kotlinx.serialization.Serializable

/*
 * Wire shapes of docs/mvp/api.md "Play" (owned here, plan.md §0: `net/ApiDtos.kt` stays L2-9's). Tolerant reads:
 * every field has a default, unknown keys are ignored (ApiClient.json), so a newer Worker never breaks an older app.
 * The content pack itself is not a DTO: it is kept as text and decoded by `:play`'s `PlayConfig.decode` (strict,
 * validated); tide years likewise go to `TideTable.parse`.
 */

/** `GET /v1/play/content?v=` (schedule.md § 4.8). */
sealed interface PlayContentResponse {
    /** `304`: the pack named by `v` / `If-None-Match` is current. */
    data class NotModified(val etag: String?) : PlayContentResponse

    /** `200`: the newest pack as raw text (validated by the caller before use) and its `ETag`. */
    data class Fresh(val body: String, val etag: String?) : PlayContentResponse
}

/** `GET /v1/play/me?dataset=` — the Play chip summary and the Logbook restore (product § 3.6.5). */
@Serializable
data class PlayMeDto(
    val dataset: String = ApiClient.TRAILS,
    /** The server's current week id (its Monday, island time). */
    val weekId: String = "",
    val total: Int = 0,
    /** This server week's finds + W-DAY marks. */
    val week: Int = 0,
    /** Claim counts per kind (`treasure`, `egg`, `riddle`, `ink`, `lore`, `challenge`). */
    val perKind: Map<String, Int> = emptyMap(),
    /** Every claimed occurrence key, sorted: re-mark them found, never claim them again. */
    val occ: List<String> = emptyList(),
    /**
     * Server-only credits of closed weeks (branch review round 1 P0-2): W-WEEK (`week`), the kraken's retreat
     * (`retreat`) and the island challenge (`island_challenge`), one row per kind and week. The Logbook mirrors each
     * row once (`"<kind>@<week>"`), so its total meets [total].
     */
    val log: List<PlayLogDto> = emptyList(),
    /**
     * Trails only (Play trails v1.1, plan D3): every trails rule key the server has paid (`climb:100`, `mile:5`,
     * `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`), sorted. Read only by the Logbook restore, which adds
     * them to the phone's ledger on its Restored write. Absent on roads (empty).
     */
    val paid: List<String> = emptyList(),
)

/** One server-only credit: [week] = the week id (its Monday), [kind] = `week` | `retreat` | `island_challenge`. */
@Serializable
data class PlayLogDto(val week: String = "", val kind: String = "", val marks: Int = 0)

/** `GET /v1/play/challenges?dataset=` (schedule.md § 7.3, PD-4): `{band, count}` standings only. */
@Serializable
data class PlayChallengesDto(
    val dataset: String = ApiClient.TRAILS,
    /** Community counts below this are null. */
    val minCount: Int = 5,
    val current: List<PlayChallengeDto> = emptyList(),
)

@Serializable
data class PlayChallengeDto(
    val id: String,
    /** Occurrence key (`ch-krakenweek@2026-10-29`). */
    val occ: String,
    /** `solo` | `island`. */
    val scope: String = "solo",
    /** The schedule's window in island wall time with offset (`2026-10-29T06:00-07:00`); never a user's time. */
    val windowStart: String = "",
    val windowEnd: String = "",
    /** False after the window end while late contributions are still accepted. */
    val open: Boolean = true,
    /** Your contributions; null when signed out. */
    val progress: Int? = null,
    val target: Int = 0,
    val you: PlayBandDto? = null,
    val bands: List<PlayBandCountDto> = emptyList(),
    /** Island challenges: the attack's stretches and how many reached the player minimum. */
    val island: PlayIslandProgressDto? = null,
)

/** `band`: `top` | `middle` | `starting`. */
@Serializable
data class PlayBandDto(val band: String)

/** Participants in [band]; null below `minCount`, else rounded to 5. */
@Serializable
data class PlayBandCountDto(val band: String, val count: Int? = null)

@Serializable
data class PlayIslandProgressDto(val stretches: Int = 0, val repelled: Int = 0)

// --- claims on PUT /v1/coverage (api.md "Claims on PUT /v1/coverage"; wired into CoverageUpload by L2-9) -------------

/**
 * One claim. [kind] ∈ `treasure`, `egg`, `riddle`, `ink`, `lore`, `challenge`; [band] (60 / 80 / 95) only on `ink`.
 * [capped] `true` = the client's daily found cap paid it 0 (PD-23): the server books the key with 0 marks; omitted
 * otherwise. Never a mark, a time, a day of the walk or a coordinate (PF15-4).
 */
@Serializable
data class PlayClaimDto(val id: String, val kind: String, val weekId: String, val band: Int? = null, val capped: Boolean? = null)

/** One walk's claims, tied by [walkId] to a `walks[]` entry of the same upload. [hints]: riddle item id → 0..2. */
@Serializable
data class PlayWalkClaimsDto(
    val walkId: String,
    val claims: List<PlayClaimDto> = emptyList(),
    val dayKey: String? = null,
    val hints: Map<String, Int>? = null,
)

/**
 * The `play` block of the coverage response. [capped]: accepted with 0 marks (the weekly cap, a `capped:true` claim, or
 * a repeat whose stored marks are 0); [dayAccepted]: walk ids whose `dayKey` earned W-DAY (absent = none did).
 */
@Serializable
data class PlayUploadResultDto(
    val accepted: List<String> = emptyList(),
    val rejected: List<PlayRejectedClaimDto> = emptyList(),
    val capped: List<String> = emptyList(),
    val dayAccepted: List<String> = emptyList(),
    val total: Int = 0,
    val week: Int = 0,
)

/** [code]: `stale_config`, `unknown_item`, `bad_occurrence`, `not_this_walk`, `too_early`, `too_late`, `no_bits`. */
@Serializable
data class PlayRejectedClaimDto(val id: String, val code: String)
