package org.opentrafficmap.receiver.nav

/**
 * Map zoom level for a driving speed: close in town, farther out on the motorway, linearly
 * interpolated between the breakpoints. Shared by the phone map (auto-zoom setting) and the
 * Android Auto map.
 */
object SpeedZoom {
    // (speed in km/h, zoom level)
    private val CURVE = listOf(0.0 to 17.5, 10.0 to 17.5, 30.0 to 17.0, 50.0 to 16.4, 80.0 to 15.6, 100.0 to 15.0, 130.0 to 14.2)

    fun zoomFor(speedMps: Double): Double {
        val kmh = (speedMps * 3.6).coerceAtLeast(0.0)
        if (kmh >= CURVE.last().first) return CURVE.last().second
        for (i in 1 until CURVE.size) {
            val (s1, z1) = CURVE[i]
            if (kmh <= s1) {
                val (s0, z0) = CURVE[i - 1]
                return z0 + (z1 - z0) * (kmh - s0) / (s1 - s0)
            }
        }
        return CURVE.last().second
    }

    /** Within this distance of a traffic light with lane geometry (MAPEM) the map zooms in on it. */
    const val LANE_ZOOM_RANGE_M = 250.0
    /** Shown beyond the stop line, so the intersection itself stays in view. */
    private const val LANE_ZOOM_BEYOND_M = 40.0
    const val LANE_ZOOM_MAX = 19.0

    /**
     * Zoom at which the stop line [distM] ahead plus the intersection behind it fit into [availPx]
     * screen pixels (from the own car to the top of the visible area). [pxPerWorldPx] is the screen's
     * pixels per 256-px web-Mercator world pixel. Capped at [LANE_ZOOM_MAX]; never below 0.
     */
    fun laneZoom(distM: Double, availPx: Double, latDeg: Double, pxPerWorldPx: Double): Double {
        val need = distM.coerceAtLeast(0.0) + LANE_ZOOM_BEYOND_M
        val metresPerWorldPxAtZ0 = 40_075_016.686 * kotlin.math.cos(Math.toRadians(latDeg)) / 256.0
        val z = kotlin.math.log2(availPx * metresPerWorldPxAtZ0 / (pxPerWorldPx * need))
        return z.coerceIn(0.0, LANE_ZOOM_MAX)
    }
}
