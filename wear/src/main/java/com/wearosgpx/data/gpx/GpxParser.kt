package com.wearosgpx.data.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Streaming GPX 1.1 reader built on Android's bundled [XmlPullParser]
 * (no third-party dependency).
 *
 * It collects every point under <trkpt> (track), <rtept> (route), and <wpt>
 * (waypoint), in document order — which covers the common cases of routes
 * exported by Strava, Komoot, etc.
 *
 * Usage:
 *     context.contentResolver.openInputStream(uri).use { stream ->
 *         val route = GpxParser.parse(stream!!)
 *     }
 */
object GpxParser {

    private val POINT_TAGS = setOf("trkpt", "rtept", "wpt")

    @Throws(GpxParseException::class)
    fun parse(input: InputStream): GpxRoute {
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }

            val points = mutableListOf<GeoPoint>()
            var routeName: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "name" -> if (routeName == null) {
                            routeName = parser.nextText().trim().ifEmpty { null }
                        }
                        in POINT_TAGS -> readPoint(parser)?.let(points::add)
                    }
                }
                event = parser.next()
            }

            if (points.isEmpty()) {
                throw GpxParseException("No <trkpt>, <rtept>, or <wpt> points found.")
            }
            return GpxRoute(name = routeName, points = points)
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            throw GpxParseException("Failed to parse GPX: ${e.message}", e)
        }
    }

    /**
     * Reads one point element. The parser is positioned on the START_TAG
     * (trkpt/rtept/wpt). lat/lon live in attributes; <ele> and <time> are
     * optional children. Advances until the matching END_TAG.
     */
    private fun readPoint(parser: XmlPullParser): GeoPoint? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        if (lat == null || lon == null) {
            skipElement(parser)
            return null
        }

        val pointTag = parser.name
        var ele: Double? = null
        var epochMillis: Long? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == pointTag)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ele" -> ele = parser.nextText().trim().toDoubleOrNull()
                    "time" -> epochMillis = parseIsoTime(parser.nextText().trim())
                    else -> skipElement(parser)
                }
            }
            event = parser.next()
        }

        return GeoPoint(
            latitude = lat,
            longitude = lon,
            elevationMeters = ele,
            epochMillis = epochMillis,
        )
    }

    /** ISO-8601 (e.g. 2024-09-01T07:30:15Z) -> epoch millis, or null if absent/bad. */
    private fun parseIsoTime(value: String): Long? =
        if (value.isEmpty()) null
        else try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    /** Consumes an element subtree we don't care about. */
    private fun skipElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
