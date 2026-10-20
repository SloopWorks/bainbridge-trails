package com.bainbridgetrails.map

actual fun directionsUri(lat: Double, lon: Double, label: String?): String =
    "maps://?daddr=$lat,$lon" + (label?.let { "&q=${encodeQuery(it)}" } ?: "")
