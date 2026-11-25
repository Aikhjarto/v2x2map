package org.opentrafficmap.receiver.nav

/**
 * Posted speed limits along a [Route], from Valhalla's trace_attributes (OSM maxspeed).
 * Stored as distance ranges along the route shape so the lookup works with [NavState.distAlong].
 */
class SpeedLimits(private val startM: DoubleArray, private val endM: DoubleArray, private val kph: IntArray) {

    /** Limit in km/h at [distAlong] metres along the route, or null if unknown / unlimited. */
    fun at(distAlong: Double): Int? {
        var lo = 0; var hi = startM.lastIndex; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (startM[mid] <= distAlong) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0 || distAlong > endM[idx] + 1.0) return null
        return kph[idx].takeIf { it > 0 }
    }

    val isEmpty: Boolean get() = kph.none { it > 0 }

    companion object {
        /** Request for POST /trace_attributes: walk the route's own shape and return per-edge limits. */
        fun requestBody(route: Route): String = MiniJson.stringify(linkedMapOf(
            "encoded_polyline" to PolylineCodec.encode(route.points),
            "shape_match" to "edge_walk",
            "costing" to "auto",
            "filters" to linkedMapOf(
                "attributes" to listOf("edge.speed_limit", "edge.begin_shape_index", "edge.end_shape_index"),
                "action" to "include",
            ),
        ))

        /**
         * Parses the trace_attributes response. Shape indices refer to [route].points.
         * Limits outside 5..150 km/h (missing, 0 = unknown, 255 = unlimited) are treated as unknown.
         */
        fun parse(text: String, route: Route): SpeedLimits {
            val root = MiniJson.parse(text)
            val edges = root.arr("edges") ?: throw RoutingException(root.str("error") ?: "no edges")
            val last = route.points.lastIndex
            val rows = edges.mapNotNull { e ->
                val b = e.num("begin_shape_index")?.toInt()?.coerceIn(0, last) ?: return@mapNotNull null
                val en = e.num("end_shape_index")?.toInt()?.coerceIn(0, last) ?: return@mapNotNull null
                val lim = e.num("speed_limit")?.toInt()?.takeIf { it in 5..150 } ?: 0
                Triple(route.cumDist[b], route.cumDist[maxOf(b, en)], lim)
            }.sortedBy { it.first }
            return SpeedLimits(
                DoubleArray(rows.size) { rows[it].first },
                DoubleArray(rows.size) { rows[it].second },
                IntArray(rows.size) { rows[it].third },
            )
        }
    }
}

/**
 * Speeding warning with hysteresis: on when the speed exceeds the limit by more than the
 * tolerance (5 km/h, or 5 % above 100 km/h), off again once it drops 2 km/h below that.
 * [flashing] is the stronger level (more than 10 % over, never below the warning), same hysteresis.
 */
class SpeedWarning {
    var speeding = false; private set
    var flashing = false; private set

    fun update(speedKph: Double, limitKph: Int?): Boolean {
        if (limitKph == null) { reset(); return false }
        val threshold = limitKph + if (limitKph > 100) limitKph * 0.05 else 5.0
        speeding = over(speedKph, threshold, speeding)
        flashing = speeding && over(speedKph, kotlin.math.max(threshold, limitKph * 1.10), flashing)
        return speeding
    }

    private fun over(speedKph: Double, threshold: Double, on: Boolean) =
        if (on) speedKph > threshold - 2.0 else speedKph > threshold

    fun reset() { speeding = false; flashing = false }
}
