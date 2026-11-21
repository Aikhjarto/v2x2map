package org.opentrafficmap.receiver.nav

import java.net.URLDecoder

/**
 * Extracts a destination from text shared to V2X2MAP (Android share sheet or a geo: link):
 * geo: URIs, Google Maps / OpenStreetMap / OsmAnd URLs, plain "lat, lon" text, or as a last
 * resort the text itself as a search query. Pure Kotlin, no network — Google short links are
 * returned as [Result.ShortLink] for the caller to expand.
 */
object ShareParser {

    sealed class Result {
        data class Coords(val pos: LatLon, val name: String?) : Result()
        /** Search queries to try in order (e.g. the full text, then single lines). */
        data class Query(val candidates: List<String>) : Result()
        data class ShortLink(val url: String, val name: String?) : Result()
    }

    private val GEO = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)[^\s?]*(\?\S*)?""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?![\d.])""")
    private val LATLON = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")
    private val G_PLACE_EXACT = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val AT_COORDS = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val G_PLACE_NAME = Regex("""/maps/(?:place|search)/([^/@?#]+)""")
    private val FRAGMENT_MAP = Regex("""(?:^|map=)\d+(?:\.\d+)?/(-?\d+\.\d+)/(-?\d+\.\d+)""")

    fun parse(text: String): Result? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val label = label(t)
        GEO.find(t)?.let { m -> parseGeo(m.value, label)?.let { return it } }
        for (u in urls(t)) parseUrl(u, label)?.let { return it }
        PLAIN.find(t)?.let { m -> coords(m.groupValues[1], m.groupValues[2])?.let { return Result.Coords(it, label) } }
        return textQuery(t)
    }

    /** The shared text without links, as search queries: all lines joined, then each line (last first). */
    fun textQuery(text: String): Result.Query? {
        val lines = text.lines().map { it.replace(URL, "").replace(GEO, "").trim().trim(',', ';', '-', '·') }
            .filter { it.length >= 2 }
        if (lines.isEmpty()) return null
        return Result.Query((listOf(lines.joinToString(", ")) + lines.reversed()).distinct())
    }

    fun parseGeo(uri: String, label: String?): Result? {
        val m = GEO.find(uri) ?: return null
        val q = m.groupValues[3].removePrefix("?").let { params(it)["q"] }
        val qm = q?.let { LATLON.find(it) }
        val qLabel = qm?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
        coords(m.groupValues[1], m.groupValues[2])?.let { return Result.Coords(it, qLabel ?: label) }
        if (qm != null) coords(qm.groupValues[1], qm.groupValues[2])?.let { return Result.Coords(it, qLabel ?: label) }
        return q?.takeIf { it.isNotBlank() }?.let { Result.Query(listOf(it)) }
    }

    fun parseUrl(url: String, label: String?): Result? {
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val rest = afterScheme.removePrefix(afterScheme.substringBefore('/').substringBefore('?').substringBefore('#'))
        val path = rest.substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "").substringBefore('#')
        val fragment = rest.substringAfter('#', "")
        val p = params(query)

        // EU cookie-consent interstitial: the real URL is in "continue"
        if (host == "consent.google.com") return p["continue"]?.let { parseUrl(it, label) }

        val placeName = G_PLACE_NAME.find(path)?.groupValues?.get(1)?.let { decode(it) }
        val name = placeName ?: label

        G_PLACE_EXACT.find(url)?.let { m -> coords(m.groupValues[1], m.groupValues[2])?.let { return Result.Coords(it, name) } }
        for ((a, b) in listOf("mlat" to "mlon", "lat" to "lon", "lat" to "lng")) {
            val la = p[a]; val lo = p[b]
            if (la != null && lo != null) coords(la, lo)?.let { return Result.Coords(it, name) }
        }
        for (k in listOf("q", "query", "daddr", "destination", "ll", "pin")) {
            val m = p[k]?.let { LATLON.find(it) } ?: continue
            coords(m.groupValues[1], m.groupValues[2])?.let {
                return Result.Coords(it, m.groupValues[3].takeIf { s -> s.isNotBlank() } ?: name)
            }
        }
        AT_COORDS.find(path)?.let { m -> coords(m.groupValues[1], m.groupValues[2])?.let { return Result.Coords(it, name) } }
        FRAGMENT_MAP.find(fragment)?.let { m -> coords(m.groupValues[1], m.groupValues[2])?.let { return Result.Coords(it, name) } }

        if (host == "maps.app.goo.gl" || (host == "goo.gl" && path.startsWith("/maps"))) return Result.ShortLink(url, label)

        val isGoogleMaps = host.startsWith("maps.google.") ||
            (host.contains("google.") && path.startsWith("/maps"))
        if (isGoogleMaps) {
            for (k in listOf("q", "query", "daddr", "destination"))
                p[k]?.takeIf { it.isNotBlank() }?.let { return Result.Query(listOf(it)) }
            placeName?.let { return Result.Query(listOf(it)) }
        }
        return null
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun urls(t: String) = URL.findAll(t).map { it.value.trimEnd(')', ']', '>', '.', ',', '"', '\'') }.toList()

    /** First line that isn't a link — Google Maps puts the place name there. */
    private fun label(t: String): String? = t.lines().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !URL.containsMatchIn(it) && !GEO.containsMatchIn(it) && !PLAIN.containsMatchIn(it) }
        ?.take(80)

    private fun coords(a: String, b: String): LatLon? {
        val lat = a.toDoubleOrNull() ?: return null
        val lon = b.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return LatLon(lat, lon)
    }

    private fun params(query: String): Map<String, String> = query.split('&').mapNotNull { kv ->
        if (kv.isEmpty()) return@mapNotNull null
        val k = decode(kv.substringBefore('='))
        k to decode(kv.substringAfter('=', ""))
    }.toMap()

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
