package com.bainbridgetrails.play.geo

import kotlinx.serialization.Serializable

/**
 * The one WGS84 point (degrees) in Play: schedule placements, claim targets, effect anchors, pack landmarks.
 * Always construct it with named arguments — `GeoPoint(lat = …, lon = …)` — so a swapped pair cannot compile silently
 * past review (GeoJSON and MapLibre order lon,lat; this type orders lat,lon).
 */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double)
