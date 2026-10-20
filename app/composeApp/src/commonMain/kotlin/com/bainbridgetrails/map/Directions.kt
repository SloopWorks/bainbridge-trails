package com.bainbridgetrails.map

/**
 * URI that opens the platform maps app at / with directions to [lat],[lon] (product F4 step 4):
 * Android `geo:` (any maps app), iOS `maps://` (Apple Maps). [label] names the pin where supported.
 */
expect fun directionsUri(lat: Double, lon: Double, label: String?): String

/** Percent-encodes [s] for a URI query component. */
internal fun encodeQuery(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
        else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}
