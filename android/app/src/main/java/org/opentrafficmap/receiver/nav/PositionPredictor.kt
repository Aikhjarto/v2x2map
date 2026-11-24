package org.opentrafficmap.receiver.nav

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where to draw the own car between GPS fixes (about 1 Hz).
 *
 * The last fix is moved forward with its speed and heading, so the arrow stays where the car is now.
 * (Interpolating from the previous to the latest fix, as before, kept it one full fix interval
 * behind: about 14 m at 50 km/h, 28 m at 100 km/h, which looked like an offset against the road.)
 * In curves it follows an arc with the turn rate of the last two fixes (straight ahead would drift
 * to the outside of a ramp by a few metres). The jump between the predicted and the next real fix
 * is blended away over [blendMs].
 * Times in milliseconds of any monotonic clock.
 */
class PositionPredictor(
    private val maxPredictS: Double = 1.5,
    private val blendMs: Double = 600.0,
    /** Larger jumps (e.g. the first fix after a GPS gap) are taken as they are. */
    private val maxBlendM: Double = 40.0,
) {
    private var fix: LatLon? = null
    private var speed = 0.0
    private var heading: Double? = null
    private var turnDegPerS = 0.0
    private var fixTime = 0L
    private var corrE = 0.0
    private var corrN = 0.0

    fun onFix(p: LatLon, speedMps: Double, headingDeg: Double?, now: Long) {
        val shown = at(now)
        val prevHeading = heading
        val dtS = (now - fixTime) / 1000.0
        turnDegPerS = if (headingDeg != null && prevHeading != null && fix != null && speedMps > 3.0 && dtS in 0.2..2.5)
            (Geo.angleDiff(headingDeg, prevHeading) / dtS).coerceIn(-MAX_TURN_DEG_S, MAX_TURN_DEG_S) else 0.0
        fix = p; speed = speedMps; heading = headingDeg; fixTime = now
        corrE = 0.0; corrN = 0.0
        if (shown != null) {
            val e = eastM(p, shown); val n = northM(p, shown)
            if (hypot(e, n) < maxBlendM) { corrE = e; corrN = n }
        }
    }

    /** Position to draw at [now], null before the first fix. */
    fun at(now: Long): LatLon? {
        val f = fix ?: return null
        val dtMs = (now - fixTime).coerceAtLeast(0L).toDouble()
        var e = 0.0; var n = 0.0
        val h = heading
        if (h != null && speed > 1.0) {
            val t = (dtMs / 1000.0).coerceAtMost(maxPredictS)
            val h0 = Math.toRadians(h)
            val w = Math.toRadians(turnDegPerS)
            if (kotlin.math.abs(w) < 1e-3) { e = speed * t * sin(h0); n = speed * t * cos(h0) }
            else {                                   // arc: heading h0 + w·t, clockwise from north
                val r = speed / w
                e = r * (cos(h0) - cos(h0 + w * t)); n = r * (sin(h0 + w * t) - sin(h0))
            }
        }
        val k = (1.0 - dtMs / blendMs).coerceIn(0.0, 1.0)
        e += corrE * k; n += corrN * k
        return if (e == 0.0 && n == 0.0) f else Geo.offset(f, e, n)
    }

    private fun eastM(from: LatLon, to: LatLon) =
        Math.toRadians(to.lon - from.lon) * EARTH_R * cos(Math.toRadians(from.lat))
    private fun northM(from: LatLon, to: LatLon) = Math.toRadians(to.lat - from.lat) * EARTH_R

    private companion object {
        const val EARTH_R = 6_371_000.0
        /** Faster heading changes are GPS noise (a tight ramp at 40 km/h turns about 15°/s). */
        const val MAX_TURN_DEG_S = 30.0
    }
}
