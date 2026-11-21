package org.opentrafficmap.receiver.nav

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One turn instruction. [type] is the raw Valhalla maneuver type (see ManeuverKind). */
data class RouteManeuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,      // "Turn right onto Ringstraße."
    val verbalPre: String?,        // "Turn right onto Ringstraße." (said right before)
    val streetNames: List<String>,
    val lengthM: Double,
    val timeS: Double,
    val beginShapeIndex: Int,
    val roundaboutExit: Int = 0,
)

class Route(
    val points: List<LatLon>,
    val maneuvers: List<RouteManeuver>,
    val lengthM: Double,
    val timeS: Double,
) {
    /** cumDist[i] = metres from start to points[i]. */
    val cumDist: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val shapeLengthM: Double get() = if (cumDist.isEmpty()) 0.0 else cumDist.last()

    /** Distance along the route at which maneuver [m] happens. */
    fun maneuverDist(m: Int): Double =
        cumDist[maneuvers[m].beginShapeIndex.coerceIn(0, points.lastIndex)]

    /** Point at [d] metres along the route. */
    fun pointAt(d: Double): LatLon {
        if (points.size < 2 || d <= 0) return points.first()
        if (d >= shapeLengthM) return points.last()
        var lo = 0; var hi = points.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumDist[mid] <= d) lo = mid else hi = mid }
        val seg = cumDist[hi] - cumDist[lo]
        return Geo.interpolate(points[lo], points[hi], if (seg <= 0) 0.0 else (d - cumDist[lo]) / seg)
    }

    fun bearingAt(d: Double): Double {
        val a = pointAt(d); val b = pointAt(d + 15)
        return if (a == b) 0.0 else Geo.bearing(a, b)
    }
}

data class Place(val name: String, val detail: String, val pos: LatLon)

class RoutingException(msg: String) : IOException(msg)

/**
 * Online routing via Valhalla (default: the free FOSSGIS instance used by openstreetmap.org)
 * and search via Photon (komoot). Both are free, keyless and OSM-based.
 * All calls are blocking — run them off the main thread.
 */
class RoutingClient(
    private val valhallaBase: String = DEFAULT_VALHALLA,
    private val photonBase: String = DEFAULT_PHOTON,
    private val language: String = "en-US",
    private val userAgent: String = "V2X2MAP-Auto",
) {
    fun route(
        from: LatLon,
        to: LatLon,
        headingDeg: Double? = null,
        avoid: List<LatLon> = emptyList(),
    ): Route {
        val start = linkedMapOf<String, Any?>("lat" to from.lat, "lon" to from.lon)
        if (headingDeg != null) { start["heading"] = headingDeg.toInt(); start["heading_tolerance"] = 60 }
        val req = linkedMapOf<String, Any?>(
            "locations" to listOf(start, mapOf("lat" to to.lat, "lon" to to.lon)),
            "costing" to "auto",
            "units" to "kilometers",
            "language" to language,
            "directions_options" to mapOf("units" to "kilometers", "language" to language),
        )
        if (avoid.isNotEmpty())
            req["exclude_locations"] = avoid.map { mapOf("lat" to it.lat, "lon" to it.lon) }
        val body = MiniJson.stringify(req)
        val text = httpGet("$valhallaBase/route?json=" + URLEncoder.encode(body, "UTF-8"))
        return parseValhalla(text)
    }

    fun search(query: String, near: LatLon?, limit: Int = 8): List<Place> {
        val lang = language.substringBefore('-').let { if (it in setOf("de", "en", "fr")) it else "en" }
        var url = "$photonBase/api/?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=$limit&lang=$lang"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
        return parsePhoton(httpGet(url))
    }

    /** Posted speed limits along [route] (Valhalla trace_attributes on the route's own shape). */
    fun speedLimits(route: Route): SpeedLimits =
        SpeedLimits.parse(httpGet("$valhallaBase/trace_attributes", body = SpeedLimits.requestBody(route)), route)

    /** GET [url], or POST [body] as JSON when given. */
    private fun httpGet(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { MiniJson.parse(text).str("error") }.getOrNull()
                throw RoutingException(msg ?: "HTTP $code")
            }
            return text
        } finally { c.disconnect() }
    }

    companion object {
        const val DEFAULT_VALHALLA = "https://valhalla1.openstreetmap.de"
        const val DEFAULT_PHOTON = "https://photon.komoot.io"

        fun parseValhalla(text: String): Route {
            val root = MiniJson.parse(text)
            val trip = root.obj("trip") ?: throw RoutingException(root.str("error") ?: "no route")
            val leg = trip.arr("legs")?.firstOrNull() ?: throw RoutingException("no legs")
            val pts = PolylineCodec.decode(leg.str("shape") ?: throw RoutingException("no shape"), 6)
            val mans = (leg.arr("maneuvers") ?: emptyList()).map { m ->
                RouteManeuver(
                    type = m.num("type")?.toInt() ?: 0,
                    instruction = m.str("instruction") ?: "",
                    verbalAlert = m.str("verbal_transition_alert_instruction"),
                    verbalPre = m.str("verbal_pre_transition_instruction"),
                    streetNames = (m.arr("street_names") ?: emptyList()).filterIsInstance<String>(),
                    lengthM = (m.num("length") ?: 0.0) * 1000.0,
                    timeS = m.num("time") ?: 0.0,
                    beginShapeIndex = m.num("begin_shape_index")?.toInt() ?: 0,
                    roundaboutExit = m.num("roundabout_exit_count")?.toInt() ?: 0,
                )
            }
            if (pts.size < 2 || mans.isEmpty()) throw RoutingException("empty route")
            val sum = trip.obj("summary")
            return Route(pts, mans, (sum.num("length") ?: 0.0) * 1000.0, sum.num("time") ?: 0.0)
        }

        fun parsePhoton(text: String): List<Place> {
            val feats = MiniJson.parse(text).arr("features") ?: return emptyList()
            return feats.mapNotNull { f ->
                val coords = (f.obj("geometry") as Map<*, *>?)?.get("coordinates") as? List<*> ?: return@mapNotNull null
                val lon = (coords.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
                val lat = (coords.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
                val p = f.obj("properties")
                val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ")
                val name = p.str("name") ?: street.ifEmpty { p.str("city") ?: "?" }
                val detail = listOf(
                    if (p.str("name") != null) street else "",
                    listOfNotNull(p.str("postcode"), p.str("city")).joinToString(" "),
                    p.str("country") ?: "",
                ).filter { it.isNotBlank() }.joinToString(", ")
                Place(name, detail, LatLon(lat, lon))
            }
        }
    }
}

/** Valhalla maneuver type constants we care about. */
object ManeuverKind {
    const val NONE = 0; const val START = 1; const val START_RIGHT = 2; const val START_LEFT = 3
    const val DESTINATION = 4; const val DESTINATION_RIGHT = 5; const val DESTINATION_LEFT = 6
    const val BECOMES = 7; const val CONTINUE = 8
    const val SLIGHT_RIGHT = 9; const val RIGHT = 10; const val SHARP_RIGHT = 11
    const val UTURN_RIGHT = 12; const val UTURN_LEFT = 13
    const val SHARP_LEFT = 14; const val LEFT = 15; const val SLIGHT_LEFT = 16
    const val RAMP_STRAIGHT = 17; const val RAMP_RIGHT = 18; const val RAMP_LEFT = 19
    const val EXIT_RIGHT = 20; const val EXIT_LEFT = 21
    const val STAY_STRAIGHT = 22; const val STAY_RIGHT = 23; const val STAY_LEFT = 24
    const val MERGE = 25; const val ROUNDABOUT_ENTER = 26; const val ROUNDABOUT_EXIT = 27
    const val FERRY_ENTER = 28; const val FERRY_EXIT = 29
    const val MERGE_RIGHT = 37; const val MERGE_LEFT = 38

    /** Turn angle for drawing an arrow icon: 0 straight, +right, -left, ±180 u-turn. */
    fun arrowAngle(type: Int): Int = when (type) {
        SLIGHT_RIGHT, RAMP_RIGHT, EXIT_RIGHT, STAY_RIGHT, MERGE_RIGHT -> 45
        RIGHT, START_RIGHT, DESTINATION_RIGHT -> 90
        SHARP_RIGHT -> 135
        UTURN_RIGHT -> 180
        UTURN_LEFT -> -180
        SHARP_LEFT -> -135
        LEFT, START_LEFT, DESTINATION_LEFT -> -90
        SLIGHT_LEFT, RAMP_LEFT, EXIT_LEFT, STAY_LEFT, MERGE_LEFT -> -45
        else -> 0
    }
}
