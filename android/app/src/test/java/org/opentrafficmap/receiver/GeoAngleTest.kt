package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.receiver.nav.Geo

class GeoAngleTest {
    @Test fun angleDiffIsShortestForAnyInput() {
        assertEquals(20.0, Geo.angleDiff(10.0, 350.0), 1e-9)      // across north, clockwise
        assertEquals(-20.0, Geo.angleDiff(350.0, 10.0), 1e-9)     // across north, counter-clockwise
        assertEquals(0.0, Geo.angleDiff(10.0, 730.0), 1e-9)       // old formula returned -360 here
        assertEquals(20.0, Geo.angleDiff(10.0, -370.0), 1e-9)
        assertEquals(-70.0, Geo.angleDiff(-400.0, 30.0), 1e-9)   // -400 = 320
        for (a in -1080..1080 step 37) for (b in -1080..1080 step 41) {
            val d = Geo.angleDiff(a.toDouble(), b.toDouble())
            assert(d >= -180.0 && d < 180.0) { "angleDiff($a, $b) = $d" }
            assertEquals(0.0, Geo.angleDiff(b + d, a.toDouble()), 1e-6)
        }
    }

    @Test fun normDeg() {
        assertEquals(10.0, Geo.normDeg(730.0), 1e-9)
        assertEquals(350.0, Geo.normDeg(-10.0), 1e-9)
        assertEquals(0.0, Geo.normDeg(-720.0), 1e-9)
    }
}
