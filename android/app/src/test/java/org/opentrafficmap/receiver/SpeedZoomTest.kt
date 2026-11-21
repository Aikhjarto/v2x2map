package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.SpeedZoom

class SpeedZoomTest {
    private fun z(kmh: Double) = SpeedZoom.zoomFor(kmh / 3.6)

    @Test fun closeInTownFartherOnTheMotorway() {
        assertEquals(17.5, z(0.0), 1e-9)
        assertEquals(16.4, z(50.0), 1e-9)
        assertEquals(15.0, z(100.0), 1e-9)
        assertEquals(14.2, z(130.0), 1e-9)
        assertEquals(14.2, z(200.0), 1e-9)
        assertEquals(15.3, z(90.0), 1e-9)                  // interpolated between 80 and 100 km/h
    }

    @Test fun neverZoomsInWhenFaster() {
        var last = Double.MAX_VALUE
        for (kmh in 0..200) { val v = z(kmh.toDouble()); assertTrue("at $kmh", v <= last + 1e-12); last = v }
    }
}
