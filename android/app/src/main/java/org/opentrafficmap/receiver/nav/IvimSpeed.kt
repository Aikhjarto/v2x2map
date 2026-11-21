package org.opentrafficmap.receiver.nav

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Speed limit from an IVIM road sign (ISO TS 19321), valid inside its relevance zone.
 * [line] runs in the direction of travel (C-Roads: from the gantry downstream).
 */
data class IvimSpeedZone(
    val key: String,              // provider:iviId:zoneId
    val limitKph: Int,            // lowest limit over the lanes of this zone
    val lanesDiffer: Boolean,     // lanes carry different limits — the car's lane is unknown
    val line: List<LatLon>,
    val lastSeenMs: Long,
    val validToMs: Long?,
)

object IvimSpeed {
    /** Max distance from the zone polyline (about 3–4 lanes). */
    const val LATERAL_M = 15.0
    /** Max difference between driving direction and zone direction (filters the other carriageway). */
    const val HEADING_TOLERANCE_DEG = 45.0

    data class Hit(val limitKph: Int, val lanesDiffer: Boolean)

    /** Lowest limit of all zones the car is in, driving in their direction; null if none. */
    fun limitAt(zones: List<IvimSpeedZone>, pos: LatLon, headingDeg: Double?): Hit? {
        if (headingDeg == null) return null
        val hits = zones.filter { z -> inZone(z.line, pos, headingDeg) }
        if (hits.isEmpty()) return null
        val min = hits.minOf { it.limitKph }
        return Hit(min, hits.any { it.lanesDiffer } || hits.any { it.limitKph != min })
    }

    internal fun inZone(line: List<LatLon>, p: LatLon, headingDeg: Double): Boolean {
        if (line.size < 2) return false
        val kx = 111_320.0 * cos(Math.toRadians(p.lat)); val ky = 110_540.0
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 0.01) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d <= LATERAL_M && kotlin.math.abs(Geo.angleDiff(headingDeg, Geo.bearing(a, b))) <= HEADING_TOLERANCE_DEG) return true
        }
        return false
    }
}
