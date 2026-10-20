package com.bainbridgetrails.map

actual fun directionsUri(lat: Double, lon: Double, label: String?): String =
    "geo:0,0?q=$lat,$lon" + (label?.let { "(${encodeQuery(it)})" } ?: "")
