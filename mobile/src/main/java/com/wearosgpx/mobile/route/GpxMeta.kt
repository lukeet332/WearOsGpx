package com.wearosgpx.mobile.route

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads summary metadata from a local GPX file for display in the route list. */
object GpxMeta {

    data class Meta(
        val name: String?,
        val distanceMeters: Double,
        val ascentMeters: Double,
        val pointCount: Int,
    )

    private val POINT_TAGS = setOf("trkpt", "rtept", "wpt")

    fun read(file: File): Meta? = runCatching {
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            var name: String? = null
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            val eles = ArrayList<Double?>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "name" -> if (name == null) name = parser.nextText().trim().ifEmpty { null }
                        in POINT_TAGS -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                lats.add(lat); lons.add(lon)
                                eles.add(readEle(parser, parser.name))
                            }
                        }
                    }
                }
                event = parser.next()
            }

            var dist = 0.0
            for (i in 1 until lats.size) {
                dist += haversine(lats[i - 1], lons[i - 1], lats[i], lons[i])
            }
            var ascent = 0.0
            var prev: Double? = null
            for (e in eles) {
                if (e == null) continue
                prev?.let { if (e > it) ascent += e - it }
                prev = e
            }
            Meta(name, dist, ascent, lats.size)
        }
    }.getOrNull()

    /** Just the (lat, lon) track points — used to build the surrounding basemap. */
    fun readPoints(file: File): List<Pair<Double, Double>> = runCatching {
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            val pts = ArrayList<Pair<Double, Double>>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name in POINT_TAGS) {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) pts.add(lat to lon)
                }
                event = parser.next()
            }
            pts
        }
    }.getOrDefault(emptyList())

    /** Reads an optional <ele> inside the current point element; advances to its END_TAG. */
    private fun readEle(parser: XmlPullParser, pointTag: String): Double? {
        var ele: Double? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == pointTag)) {
            if (event == XmlPullParser.START_TAG && parser.name == "ele") {
                ele = parser.nextText().trim().toDoubleOrNull()
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return ele
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
