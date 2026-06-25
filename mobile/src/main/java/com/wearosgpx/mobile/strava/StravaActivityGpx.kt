package com.wearosgpx.mobile.strava

import com.wearosgpx.mobile.sync.RunPayload
import java.time.Instant

/**
 * Builds an *activity* GPX from a finished run for upload to Strava.
 *
 * Unlike a route GPX, this has per-point `<time>` (required, or Strava treats the
 * file as a route, not an activity) and embeds heart rate via the Garmin
 * TrackPointExtension that Strava understands. Points are sorted + de-duplicated by
 * timestamp so the track is monotonic in time.
 */
object StravaActivityGpx {

    fun build(payload: RunPayload): String {
        val points = payload.points
            .sortedBy { it.epochMillis }
            .distinctBy { it.epochMillis }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="WearOsGpx" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""",
        ).append('\n')
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(payload.title)).append("</name>\n")
        sb.append("    <type>running</type>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lon)).append("\">\n")
            p.ele?.let { sb.append("        <ele>").append(fmt(it)).append("</ele>\n") }
            sb.append("        <time>").append(Instant.ofEpochMilli(p.epochMillis).toString()).append("</time>\n")
            val hr = p.hr?.toInt()
            if (hr != null && hr >= 1) {
                sb.append("        <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>")
                    .append(hr.coerceIn(1, 300))
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun fmt(v: Double): String = "%.6f".format(v)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
