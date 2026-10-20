package com.bainbridgetrails.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Show on map" requests from other screens (Trail detail mini map / overflow): the Map tab selects the trail and
 * fits the camera to it, then [consume]s the request. Process-wide, like the tab's own saved state.
 */
object MapFocus {
    private val _trailId = MutableStateFlow<String?>(null)
    val trailId: StateFlow<String?> = _trailId.asStateFlow()

    fun request(trailId: String) {
        _trailId.value = trailId
    }

    fun consume() {
        _trailId.value = null
    }
}
