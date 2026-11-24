package org.opentrafficmap.receiver.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A road in one driving direction: [points] run the way cars drive on it. */
data class DirectedRoad(val key: String, val points: List<LatLon>)

data class RoadSnap(val pos: LatLon, val bearingDeg: Double, val roadKey: String, val offsetM: Double)

/**
 * Puts the own position onto the road being driven on (like OsmAnd's snap to road), without a route.
 *
 * Roads come per driving direction from Valhalla's /locate (see [parseLocate]), so a heading that does
 * not match the carriageway's direction never snaps — this keeps the arrow off the opposite carriageway
 * of a motorway. It only snaps when the fix is close to a road ([maxOffsetM], from the GPS accuracy),
 * heads along it (±[maxAngleDeg]) and the car is moving; otherwise the raw fix is drawn. The road of
 * the previous snap is preferred by [stickyM] so that parallel roads (slip road next to the motorway)
 * do not make the arrow jump back and forth.
 *
 * [needsRoadsAround] tells the caller where to fetch roads next. Not thread-safe: use from one thread.
 */
class RoadSnapper(
    private val maxAngleDeg: Double = 40.0,
    private val stickyM: Double = 4.0,
    private val maxRoads: Int = 600,
) {
    private val roads = LinkedHashMap<String, DirectedRoad>()
    private val queried = ArrayList<LatLon>()
    private var lastKey: String? = null

    val roadCount get() = roads.size

    fun addRoads(rs: List<DirectedRoad>, queriedAt: List<LatLon>) {
        rs.forEach { roads.remove(it.key); roads[it.key] = it }          // re-insert: most recent last
        while (roads.size > maxRoads) roads.remove(roads.keys.first())
        queried += queriedAt
        while (queried.size > 300) queried.removeAt(0)
    }

    /** Forgets roads and queries far from [p] (keeps memory small on long drives). */
    fun prune(p: LatLon, keepM: Double = 3_000.0) {
        roads.values.removeAll { r -> r.points.none { Geo.distance(it, p) < keepM } }
        queried.removeAll { Geo.distance(it, p) > keepM }
    }

    /**
     * Where to look up roads next, empty while the road 40 m ahead is already loaded. Returns three
     * points 0/60/120 m ahead (overlapping circles of [QUERY_RADIUS_M]): about one request per 100 m.
     */
    fun needsRoadsAround(p: LatLon, headingDeg: Double?): List<LatLon> {
        val probe = if (headingDeg == null) p else ahead(p, headingDeg, 40.0)
        if (queried.any { Geo.distance(it, probe) < QUERY_RADIUS_M * 0.9 }) return emptyList()
        return if (headingDeg == null) listOf(p) else listOf(0.0, 60.0, 120.0).map { ahead(p, headingDeg, it) }
    }

    fun snap(p: LatLon, headingDeg: Double?, speedMps: Double, accuracyM: Double): RoadSnap? {
        if (headingDeg == null || speedMps < MIN_SPEED_MPS) { lastKey = null; return null }
        val maxOffset = maxOffsetM(accuracyM)
        var best: RoadSnap? = null; var bestScore = Double.MAX_VALUE
        for (r in roads.values) {
            for (i in 0 until r.points.size - 1) {
                val a = r.points[i]; val b = r.points[i + 1]
                val (t, off) = Geo.projectOnSegment(p, a, b)
                if (off > maxOffset) continue
                val brg = Geo.bearing(a, b)
                val dAng = abs(Geo.angleDiff(headingDeg, brg))
                if (dAng > maxAngleDeg || Geo.distance(a, b) < 0.5) continue
                val score = off + dAng * 0.25 - (if (r.key == lastKey) stickyM else 0.0)
                if (score < bestScore) {
                    bestScore = score
                    best = RoadSnap(Geo.interpolate(a, b, t), brg, r.key, off)
                }
            }
        }
        lastKey = best?.roadKey
        return best
    }

    private fun ahead(p: LatLon, hdg: Double, d: Double): LatLon {
        val r = Math.toRadians(hdg)
        return Geo.offset(p, d * sin(r), d * cos(r))
    }

    companion object {
        const val QUERY_RADIUS_M = 35.0
        const val MIN_SPEED_MPS = 2.0

        /** GPS reports a 68 % radius; roads within 1.5× of it (10..25 m) are candidates. */
        fun maxOffsetM(accuracyM: Double) = (accuracyM * 1.5).coerceIn(10.0, 25.0)

        /** Body for Valhalla /locate: all roads within the radius of each point, with shapes. */
        fun locateRequest(points: List<LatLon>): String = MiniJson.stringify(mapOf(
            "locations" to points.map { mapOf("lat" to it.lat, "lon" to it.lon, "radius" to QUERY_RADIUS_M.toInt()) },
            "costing" to "auto", "verbose" to true))

        /**
         * Directed roads from a /locate answer: one per edge that cars may use, points turned into the
         * driving direction ("forward" = the edge runs along the stored shape).
         */
        fun parseLocate(text: String): List<DirectedRoad> {
            val out = LinkedHashMap<String, DirectedRoad>()
            val locs = MiniJson.parse(text) as? List<*> ?: return emptyList()
            for (loc in locs) for (e in loc.arr("edges").orEmpty()) {
                val edge = e.obj("edge") ?: continue
                if ((edge.obj("access") as Map<*, *>?)?.get("car") != true) continue
                val info = e.obj("edge_info") ?: continue
                val shape = info.str("shape") ?: continue
                var pts = runCatching { PolylineCodec.decode(shape, 6) }.getOrNull() ?: continue
                if (pts.size < 2) continue
                val forward = (edge as Map<*, *>)["forward"] != false
                if (!forward) pts = pts.reversed()
                val first = pts.first(); val last = pts.last()
                val key = "${info.num("way_id")?.toLong()}:%.6f,%.6f>%.6f,%.6f".format(java.util.Locale.ROOT, first.lat, first.lon, last.lat, last.lon)
                out[key] = DirectedRoad(key, pts)
            }
            return out.values.toList()
        }
    }
}
