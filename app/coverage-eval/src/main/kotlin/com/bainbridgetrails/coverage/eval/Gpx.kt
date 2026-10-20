package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.Fix
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every `<trkpt lat lon>` (any namespace, all tracks and segments, document order) with its `<time>` (ISO-8601 with
 * offset or Z) as epoch ms. accuracyM is null (GPX has no accuracy; hdop is not metres). Points without a parseable
 * time, or with missing/out-of-range lat/lon, are rejected with [IllegalArgumentException].
 *
 * GPX is untrusted input: DOCTYPE declarations are refused outright, so no external or internal entities (XXE,
 * billion laughs); external DTD/schema access is disabled too. Malformed XML -> [IllegalArgumentException].
 */
fun readGpx(xml: String): List<Fix> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val doc = try {
        factory.newDocumentBuilder().apply { setErrorHandler(ThrowingErrorHandler) }.parse(InputSource(StringReader(xml)))
    } catch (e: SAXException) {
        throw IllegalArgumentException("invalid GPX: ${e.message}", e)
    }
    val pts = doc.getElementsByTagNameNS("*", "trkpt")
    return (0 until pts.length).map { i ->
        val el = pts.item(i) as Element
        val lat = el.getAttribute("lat").toDoubleOrNull()
        val lon = el.getAttribute("lon").toDoubleOrNull()
        require(lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0) { "trkpt $i: bad lat/lon" }
        val times = el.getElementsByTagNameNS("*", "time")
        require(times.length > 0) { "trkpt $i has no <time>" }
        val text = times.item(0).textContent.trim()
        val ms = try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("trkpt $i: bad <time> '$text'", e)
        }
        Fix(ms, lon, lat)
    }
}

private object ThrowingErrorHandler : ErrorHandler {
    override fun warning(e: SAXParseException) {}
    override fun error(e: SAXParseException) = throw e
    override fun fatalError(e: SAXParseException) = throw e
}
