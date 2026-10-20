package com.bainbridgetrails.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation routes. Tabs sit in the bottom bar; the rest are pushed on top of the tab shell. */
sealed interface Route

@Serializable data object MapRoute : Route
@Serializable data object TrailsRoute : Route
@Serializable data object RecordRoute : Route
@Serializable data object MeRoute : Route

@Serializable data class TrailDetailRoute(val trailId: String) : Route
@Serializable data object LeaderboardRoute : Route
@Serializable data object ProfileRoute : Route
@Serializable data object SettingsRoute : Route
@Serializable data object OnboardingRoute : Route
/** Full-screen celebration for a walk's newly completed trails (dataset order). */
@Serializable data class CelebrationRoute(val sessionId: String, val trailIds: List<String>) : Route

/** Debug spike: the map style comparison screen (`screen=style` launch option). */
@Serializable data object StyleSpikeRoute : Route

/** Debug: design-system showcase (`screen=showcase` launch option). */
@Serializable data object ShowcaseRoute : Route
