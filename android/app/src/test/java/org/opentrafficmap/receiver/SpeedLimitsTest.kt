package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.MiniJson
import org.opentrafficmap.receiver.nav.Route
import org.opentrafficmap.receiver.nav.RouteManeuver
import org.opentrafficmap.receiver.nav.SpeedLimits
import org.opentrafficmap.receiver.nav.SpeedWarning

class SpeedLimitsTest {

    // 11 points ~111 m apart going north → shape index i is at ~i*111 m
    private val route = Route(
        points = (0..10).map { LatLon(48.0 + it * 0.001, 16.0) },
        maneuvers = listOf(RouteManeuver(1, "Go", null, null, emptyList(), 1110.0, 100.0, 0)),
        lengthM = 1110.0, timeS = 100.0,
    )

    private val response = """{"edges":[
        {"speed_limit":50,"begin_shape_index":0,"end_shape_index":3},
        {"speed_limit":30,"begin_shape_index":3,"end_shape_index":6},
        {"begin_shape_index":6,"end_shape_index":8},
        {"speed_limit":255,"begin_shape_index":8,"end_shape_index":10}]}"""

    @Test fun lookupByDistanceAlongRoute() {
        val sl = SpeedLimits.parse(response, route)
        val m = route.cumDist
        assertEquals(50, sl.at(0.0))
        assertEquals(50, sl.at(m[2]))
        assertEquals(30, sl.at(m[3] + 1))       // boundary belongs to the next edge
        assertEquals(30, sl.at(m[5]))
        assertNull(sl.at(m[7]))                  // no speed_limit tagged
        assertNull(sl.at(m[9]))                  // 255 = unlimited → no sign
        assertNull(sl.at(-5.0))
        assertFalse(sl.isEmpty)
    }

    @Test fun requestBodyCarriesRouteShape() {
        val body = MiniJson.parse(SpeedLimits.requestBody(route)) as Map<*, *>
        assertEquals("edge_walk", body["shape_match"])
        assertTrue((body["encoded_polyline"] as String).isNotEmpty())
    }

    @Test fun speedingHysteresis() {
        val w = SpeedWarning()
        assertFalse(w.update(54.0, 50))          // within tolerance
        assertTrue(w.update(56.0, 50))           // over 55
        assertTrue(w.update(54.0, 50))           // stays on until below 53
        assertFalse(w.update(52.5, 50))
        assertFalse(w.update(135.0, 130))        // 5 % above 100: threshold 136.5
        assertTrue(w.update(137.0, 130))
        assertFalse(w.update(200.0, null))       // unknown limit → never warn
    }
}
