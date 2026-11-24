package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.PositionPredictor

class PositionPredictorTest {
    private val start = LatLon(48.3, 14.3)

    @Test fun movesForwardBetweenFixesInsteadOfLagging() {
        val p = PositionPredictor()
        p.onFix(start, 20.0, 90.0, 0)                          // 72 km/h heading east
        assertEquals(0.0, Geo.distance(start, p.at(0)!!), 1e-6)
        val half = p.at(500)!!
        assertEquals(10.0, Geo.distance(start, half), 0.05)      // 0.5 s × 20 m/s
        assertEquals(90.0, Geo.bearing(start, half), 0.5)
        assertEquals(30.0, Geo.distance(start, p.at(5_000)!!), 0.1) // capped at 1.5 s
    }

    @Test fun nextFixIsBlendedNotJumped() {
        val p = PositionPredictor()
        p.onFix(start, 20.0, 90.0, 0)
        val shown = p.at(1_000)!!                                 // predicted 20 m east
        val fix = Geo.offset(start, 18.0, 3.0)                   // real fix a bit behind and north
        p.onFix(fix, 20.0, 90.0, 1_000)
        assertEquals(0.0, Geo.distance(shown, p.at(1_000)!!), 1e-3)  // no jump at the fix
        assertEquals(12.0, Geo.distance(fix, p.at(1_600)!!), 0.05)   // correction gone after 600 ms
    }

    @Test fun standingStillAndBigJumps() {
        val p = PositionPredictor()
        p.onFix(start, 0.0, null, 0)
        assertEquals(start, p.at(800))
        val far = Geo.offset(start, 500.0, 0.0)                   // GPS gap: take the new fix as it is
        p.onFix(far, 0.0, null, 900)
        assertEquals(far, p.at(900))
    }

    @Test fun followsTheCurveOfARamp() {
        // circle of radius 60 m around `centre`, driven clockwise at 15 m/s (14.3°/s)
        val centre = Geo.offset(start, 60.0, 0.0)
        val v = 15.0; val w = Math.toDegrees(v / 60.0)
        fun onCircle(tS: Double): Pair<LatLon, Double> {      // position and heading at time t
            val a = Math.toRadians(270.0 + w * tS)                // bearing from centre to the car
            return Geo.offset(centre, 60.0 * Math.sin(a), 60.0 * Math.cos(a)) to Geo.normDeg(w * tS)
        }
        val p = PositionPredictor()
        for (i in 0..2) { val (pos, h) = onCircle(i.toDouble()); p.onFix(pos, v, h, i * 1000L) }
        val predicted = p.at(3_000)!!                            // 1 s after the last fix
        assertEquals(60.0, Geo.distance(centre, predicted), 0.3) // still on the curve, not 1.9 m outside
        assertEquals(0.0, Geo.distance(onCircle(3.0).first, predicted), 0.5)
    }
}
