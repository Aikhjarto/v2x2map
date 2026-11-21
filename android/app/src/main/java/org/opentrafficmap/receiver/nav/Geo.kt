package org.opentrafficmap.receiver.nav

import kotlin.math.*

/** Plain lat/lon pair, degrees. Pure Kotlin (no Android deps) so it is unit-testable on the JVM. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_R = 6_371_008.8

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing a→b, degrees 0..360 (0 = north, clockwise). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest signed difference a-b in degrees, range -180..180. */
    fun angleDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    fun interpolate(a: LatLon, b: LatLon, t: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /**
     * Projects [p] onto segment a-b using a local equirectangular approximation
     * (accurate to well under a metre for segment lengths we see in routes).
     * @return fraction t in 0..1 along the segment and the lateral distance in metres.
     */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-18) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + dx * tc; val cy = ay + dy * tc
        val distDeg = sqrt(cx * cx + cy * cy)
        return tc to Math.toRadians(distDeg) * EARTH_R
    }
}

/** Google / Valhalla encoded polyline decoder (precision 6 for Valhalla, 5 for OSRM/Google). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>(encoded.length / 4)
        var i = 0; var lat = 0L; var lon = 0L
        while (i < encoded.length) {
            var shift = 0; var result = 0L; var b: Int
            do { b = encoded[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0; result = 0L
            do { b = encoded[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    fun encode(points: List<LatLon>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val la = (p.lat * factor).roundToLong(); val lo = (p.lon * factor).roundToLong()
            enc(la - pLat); enc(lo - pLon); pLat = la; pLon = lo
        }
        return sb.toString()
    }
}
