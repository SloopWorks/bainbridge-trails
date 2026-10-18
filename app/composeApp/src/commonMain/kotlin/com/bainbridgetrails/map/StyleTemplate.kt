package com.bainbridgetrails.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val placeholder = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

/** Replaces every `{{name}}` in [template] with [values]`[name]`; an unknown name is an error. */
internal fun renderStyleTemplate(template: String, values: Map<String, String>): String =
    placeholder.replace(template) { match ->
        val name = match.groupValues[1]
        requireNotNull(values[name]) { "Style placeholder {{$name}} has no value (known: ${values.keys.sorted()})" }
    }

/** `pmtiles://file://` URL for an absolute file path (percent-encodes characters unsafe in a URL path). */
internal fun pmtilesFileUrl(absolutePath: String): String {
    require(absolutePath.startsWith("/")) { "Not an absolute path: $absolutePath" }
    val encoded = buildString {
        for (b in absolutePath.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "/-._~") append(ch)
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return "pmtiles://file://$encoded"
}

/** The top-level `version` of a trails.json document. */
internal fun metadataVersion(trailsJson: String): String =
    Json.parseToJsonElement(trailsJson).jsonObject["version"]?.jsonPrimitive?.content
        ?: error("trails.json has no version")
