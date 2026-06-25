package com.wearosgpx.mobile.route

/** Builds a minimal GPX 1.1 track from plotted lat/lon points, with optional elevation. */
object GpxBuilder {

    fun build(
        name: String,
        points: List<Pair<Double, Double>>,
        elevations: List<Double?> = emptyList(),
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="WearOsGpx" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <trkseg>\n")
        points.forEachIndexed { i, (lat, lon) ->
            append("      <trkpt lat=\"")
                .append("%.6f".format(lat))
                .append("\" lon=\"")
                .append("%.6f".format(lon))
                .append("\">")
            elevations.getOrNull(i)?.let { append("<ele>").append("%.1f".format(it)).append("</ele>") }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /**
     * Return [gpx] with its track name replaced by [newName] (preserving all geometry +
     * elevation), so renaming a saved route doesn't have to rebuild it. Pure: if there's no
     * <name> element the document is returned unchanged.
     */
    fun renamed(gpx: String, newName: String): String =
        gpx.replaceFirst(
            Regex("<name>.*?</name>", RegexOption.DOT_MATCHES_ALL),
            "<name>${escape(newName)}</name>",
        )

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
