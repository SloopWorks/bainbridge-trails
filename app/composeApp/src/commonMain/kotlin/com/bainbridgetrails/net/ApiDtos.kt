package com.bainbridgetrails.net

import kotlinx.serialization.Serializable

// Wire types for docs/mvp/api.md (v1). Field names match the JSON exactly; unknown fields are ignored.

@Serializable
data class MetricsDto(
    val islandPct: Double = 0.0,
    val islandCoveredM: Double = 0.0,
    val islandTotalM: Double = 0.0,
    val coveredM: Double = 0.0,
    val trailsCompleted: Int = 0,
    val trailsTotal: Int = 0,
)

/** `GET /v1/me`. [customName] null = anonymous; [nameStatus] = `anonymous` | `ok` | `blocked`. */
@Serializable
data class MeDto(
    val userId: String,
    val provider: String,
    val displayName: String,
    val customName: String? = null,
    val anonName: String,
    val isAnonymous: Boolean,
    val nameStatus: String,
    val hiddenFromBoard: Boolean = false,
    val metrics: MetricsDto = MetricsDto(),
) {
    /** Custom name hit a newly added blocklist term; the anon name is shown (`name_reset_notice`). */
    val nameBlocked: Boolean get() = nameStatus == "blocked"
}

@Serializable
data class AuthResponse(
    val token: String,
    val expiresAt: Long,
    val isNewUser: Boolean,
    val user: MeDto,
)

/** `POST /v1/auth/google|apple`. Nulls are omitted on the wire. */
@Serializable
data class IdTokenRequest(
    val idToken: String,
    val nonce: String? = null,
    val anonName: String? = null,
    val authorizationCode: String? = null,
)

@Serializable
data class DevAuthRequest(val deviceId: String, val anonName: String? = null)

@Serializable
data class WalkDto(val id: String, val durationS: Long)

/** `PUT /v1/coverage`. [segments] = `CoverageState.encode()` form (or a bit subset of it). */
@Serializable
data class CoverageUpload(
    val datasetVersion: String,
    val tickM: Int = 5,
    val segments: Map<String, String>,
    val walks: List<WalkDto> = emptyList(),
)

/** `PUT|GET /v1/coverage` 200. */
@Serializable
data class CoverageResponse(
    val datasetVersion: String,
    val tickM: Int = 5,
    val segments: Map<String, String> = emptyMap(),
    val completedTrailIds: List<String> = emptyList(),
    val metrics: MetricsDto = MetricsDto(),
    val accepted: Boolean? = null,
    val newlyCoveredM: Double? = null,
    val ignoredSegments: List<String> = emptyList(),
)

@Serializable
data class LeaderboardEntryDto(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val islandPct: Double,
    val trailsCompleted: Int,
    val isYou: Boolean = false,
)

/** `GET /v1/leaderboard`. [you] is live (TL-11) and null when signed out, hidden or at 0%. */
@Serializable
data class LeaderboardDto(
    val entries: List<LeaderboardEntryDto> = emptyList(),
    val you: LeaderboardEntryDto? = null,
    val totalRanked: Int = 0,
)

/** A count below `minCount` is null ("Be one of the first to finish this trail"). */
@Serializable
data class TrailStatDto(
    val walkers: Int? = null,
    val completedCount: Int? = null,
    val medianPct: Double? = null,
)

@Serializable
data class TrailStatsDto(
    val datasetVersion: String,
    val completionThreshold: Double = 0.85,
    val minCount: Int = 5,
    val trails: Map<String, TrailStatDto> = emptyMap(),
)

@Serializable
data class ReportNameRequest(val userId: String)

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody)

@Serializable
internal data class ErrorBody(
    val code: String,
    val message: String = "",
    val retryAfterSeconds: Long? = null,
    val newlyCoveredM: Double? = null,
    val allowedM: Double? = null,
)
