package com.bainbridgetrails.ui.play.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Show on map" for Play (ux.md § 2.8, § 3.16): What's On rows and the kraken-week ChallengeSheet ask the Map tab to
 * select an occurrence and open its EncounterSheet. A pending request like [com.bainbridgetrails.map.MapFocus]: the
 * Map tab resolves it ([PlayFocusLogic]) once its markers are ready, then [consume]s it. Process-wide.
 */
object PlayMapFocus {
    private val _key = MutableStateFlow<String?>(null)

    /** The occurrence key, placement key or item id to show; null = none pending. */
    val key: StateFlow<String?> = _key.asStateFlow()

    fun request(key: String) {
        _key.value = key
    }

    fun consume() {
        _key.value = null
    }
}

/** Where the Map lands for a [PlayMapFocus] request: the marker whose sheet opens and the area the camera fits. */
data class PlayFocusTarget(
    val select: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    /** One point (or nearly): centre on it at [PlayFocusLogic.POINT_ZOOM] instead of fitting. */
    val isPoint: Boolean get() = east - west < 1e-4 && north - south < 1e-4
    val centerLon: Double get() = (west + east) / 2
    val centerLat: Double get() = (south + north) / 2
}

object PlayFocusLogic {
    /** ux.md § 2.8: the camera fits the item at z ≥ 14. */
    const val MIN_ZOOM = 14.0
    const val POINT_ZOOM = 15.5

    /**
     * Resolves [key] against the map's [markers]: a marker's own key first (a treasure placement, a riddle), else every
     * marker of that occurrence (the week's treasures, the kraken) or that item. Selects the first unfound one (then the
     * first) and fits all of them. Null when nothing on the map matches (the request is dropped).
     */
    fun target(key: String, markers: List<PlayMarker>): PlayFocusTarget? {
        val group = markers.filter { it.key == key }.ifEmpty { markers.filter { it.occurrenceKey == key } }.ifEmpty { markers.filter { it.itemId == key } }
        if (group.isEmpty()) return null
        val pick = group.sortedWith(compareBy({ it.found }, { it.key })).first()
        return PlayFocusTarget(pick.key, group.minOf { it.lon }, group.minOf { it.lat }, group.maxOf { it.lon }, group.maxOf { it.lat })
    }
}
