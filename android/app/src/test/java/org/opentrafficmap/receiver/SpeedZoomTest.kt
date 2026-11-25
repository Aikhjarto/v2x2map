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

    @Test fun laneZoomFitsStopLineAndIntersection() {
        // 200 px above the car, 160 dpi (0.9 screen px per world px), 48° N
        val far = SpeedZoom.laneZoom(200.0, 200.0, 48.0, 0.9)
        val near = SpeedZoom.laneZoom(30.0, 200.0, 48.0, 0.9)
        assertTrue("closer zooms in: $far → $near", near > far)
        // 240 m (200 + 40 beyond) must fit into the 200 px at that zoom
        val mPerPx = 40_075_016.686 * Math.cos(Math.toRadians(48.0)) / (256.0 * Math.pow(2.0, far) * 0.9)
        assertEquals(240.0, 200 * mPerPx, 1e-6)
        assertEquals(SpeedZoom.LANE_ZOOM_MAX, SpeedZoom.laneZoom(0.0, 2000.0, 48.0, 3.0), 1e-9)
    }

    @Test fun neverZoomsInWhenFaster() {
        var last = Double.MAX_VALUE
        for (kmh in 0..200) { val v = z(kmh.toDouble()); assertTrue("at $kmh", v <= last + 1e-12); last = v }
    }
}
