package org.opentrafficmap.receiver.nav

import kotlin.math.max

data class NavState(
    val snapped: LatLon,
    val distAlong: Double,          // metres travelled along the route shape
    val lateralM: Double,           // distance of the GPS fix from the route
    val nextManeuver: Int,          // index into route.maneuvers of the upcoming maneuver
    val distToNextM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val offRoute: Boolean,          // persistent deviation → caller should reroute
    val arrived: Boolean,
    val routeBearing: Double,
)

/**
 * Map-matches GPS fixes to a [Route] and tracks progress. Searches a window
 * around the previous match so that a route that loops back on itself
 * doesn't make the position jump.
 */
class NavigationEngine(val route: Route) {

    private var lastIdx = 0
    private var offCount = 0
    var state: NavState? = null; private set

    fun update(pos: LatLon, accuracyM: Double = 10.0, bearing: Double? = null, speedMps: Double = 0.0): NavState {
        val pts = route.points
        var bestIdx = lastIdx; var bestT = 0.0; var bestD = Double.MAX_VALUE; var bestScore = Double.MAX_VALUE
        val from = max(0, lastIdx - 5)
        val to = minOf(pts.size - 1, lastIdx + 400)
        for (i in from until to) {
            val (t, d) = Geo.projectOnSegment(pos, pts[i], pts[i + 1])
            var score = d
            if (bearing != null && speedMps > 3) {
                val segB = Geo.bearing(pts[i], pts[i + 1])
                if (kotlin.math.abs(Geo.angleDiff(bearing, segB)) > 100) score += 40.0 // opposite carriageway
            }
            if (score < bestScore) { bestScore = score; bestD = d; bestIdx = i; bestT = t }
        }
        // If we lost the route completely (e.g. after a tunnel), fall back to a global search.
        if (bestD > 150 && (from > 0 || to < pts.size - 1)) {
            for (i in 0 until pts.size - 1) {
                val (t, d) = Geo.projectOnSegment(pos, pts[i], pts[i + 1])
                if (d < bestD) { bestD = d; bestIdx = i; bestT = t }
            }
        }
        lastIdx = bestIdx
        val segLen = route.cumDist[bestIdx + 1] - route.cumDist[bestIdx]
        val along = route.cumDist[bestIdx] + segLen * bestT
        val snapped = Geo.interpolate(pts[bestIdx], pts[bestIdx + 1], bestT)

        // Upcoming maneuver = first one whose position is still ahead of us (5 m tolerance).
        var next = route.maneuvers.indices.firstOrNull { route.maneuverDist(it) > along + 5 } ?: -1
        if (next < 0) next = route.maneuvers.lastIndex
        val distNext = max(0.0, route.maneuverDist(next) - along)

        val remaining = max(0.0, route.shapeLengthM - along)
        // Scale Valhalla's time estimate by the remaining fraction of each maneuver.
        var remS = 0.0
        for (m in next until route.maneuvers.size) remS += route.maneuvers[m].timeS
        if (next > 0) {
            val cur = route.maneuvers[next - 1]
            val curStart = route.maneuverDist(next - 1)
            val curLen = route.maneuverDist(next) - curStart
            if (curLen > 0) remS += cur.timeS * ((route.maneuverDist(next) - along) / curLen).coerceIn(0.0, 1.0)
        }

        val threshold = 35.0 + accuracyM.coerceAtMost(40.0)
        offCount = if (bestD > threshold) offCount + 1 else 0
        val arrived = remaining < 25.0 && bestD < 60
        val s = NavState(
            snapped = snapped, distAlong = along, lateralM = bestD,
            nextManeuver = next, distToNextM = distNext,
            remainingM = remaining, remainingS = remS,
            offRoute = offCount >= 3, arrived = arrived,
            routeBearing = route.bearingAt(along),
        )
        state = s
        return s
    }

    /** Projects an arbitrary point onto the route ahead of the current position.
     *  Returns (metres ahead along the route, lateral distance) or null if behind / not near. */
    fun aheadOnRoute(p: LatLon, maxLateralM: Double, maxAheadM: Double): Pair<Double, Double>? {
        val st = state ?: return null
        val pts = route.points
        var bestD = Double.MAX_VALUE; var bestAlong = 0.0
        val startIdx = max(0, lastIdx)
        for (i in startIdx until pts.size - 1) {
            if (route.cumDist[i] - st.distAlong > maxAheadM + 100) break
            val (t, d) = Geo.projectOnSegment(p, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestAlong = route.cumDist[i] + (route.cumDist[i + 1] - route.cumDist[i]) * t }
        }
        val ahead = bestAlong - st.distAlong
        return if (bestD <= maxLateralM && ahead > -10 && ahead <= maxAheadM) ahead.coerceAtLeast(0.0) to bestD else null
    }
}
