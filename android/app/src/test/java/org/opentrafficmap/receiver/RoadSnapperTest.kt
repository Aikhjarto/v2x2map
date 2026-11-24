package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.DirectedRoad
import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.PolylineCodec
import org.opentrafficmap.receiver.nav.RoadSnapper

class RoadSnapperTest {
    private val o = LatLon(48.3, 14.3)
    private fun at(e: Double, n: Double) = Geo.offset(o, e, n)
    /** Straight road from (e0, n0) to (e1, n1) in metres from [o], in that driving direction. */
    private fun road(key: String, e0: Double, n0: Double, e1: Double, n1: Double) = DirectedRoad(key, listOf(at(e0, n0), at(e1, n1)))

    // motorway along the east axis: eastbound carriageway at n = 0, westbound at n = 20 m
    private val motorway = listOf(road("east", -500.0, 0.0, 500.0, 0.0), road("west", 500.0, 20.0, -500.0, 20.0))

    @Test fun snapsOntoTheCarriagewayOfTheDrivingDirection() {
        val s = RoadSnapper().apply { addRoads(motorway, listOf(o)) }
        // 12 m north of the eastbound lane, i.e. nearer to the westbound one, but heading east
        val snap = s.snap(at(0.0, 12.0), 90.0, 30.0, 10.0)!!
        assertEquals("east", snap.roadKey)
        assertEquals(0.0, Geo.distance(snap.pos, at(0.0, 0.0)), 0.3)
        assertEquals(90.0, snap.bearingDeg, 0.5)
        assertEquals("west", s.snap(at(0.0, 12.0), 270.0, 30.0, 10.0)!!.roadKey)
    }

    @Test fun rawFixWhenFarSlowOrCrossing() {
        val s = RoadSnapper().apply { addRoads(motorway, listOf(o)) }
        assertNull(s.snap(at(0.0, -40.0), 90.0, 30.0, 10.0))           // 40 m off any road
        assertNull(s.snap(at(0.0, 2.0), 90.0, 1.0, 10.0))              // standing / walking pace
        assertNull(s.snap(at(0.0, 2.0), null, 30.0, 10.0))             // no heading
        assertNull(s.snap(at(0.0, 2.0), 0.0, 30.0, 10.0))              // crossing the road (bridge above)
    }

    @Test fun staysOnTheCurrentRoadNextToAParallelSlipRoad() {
        val slip = road("slip", -500.0, -7.0, 500.0, -7.0)              // slip road 7 m south, same direction
        val s = RoadSnapper().apply { addRoads(motorway + slip, listOf(o)) }
        assertEquals("east", s.snap(at(0.0, -1.0), 90.0, 30.0, 10.0)!!.roadKey)
        // GPS wanders slightly nearer to the slip road: the arrow does not jump over
        assertEquals("east", s.snap(at(10.0, -4.5), 90.0, 30.0, 10.0)!!.roadKey)
        // clearly on the slip road: follows it
        assertEquals("slip", s.snap(at(20.0, -8.0), 90.0, 30.0, 10.0)!!.roadKey)
    }

    @Test fun asksForRoadsAheadOnlyWhenNotLoaded() {
        val s = RoadSnapper()
        val first = s.needsRoadsAround(o, 90.0)
        assertEquals(3, first.size)
        assertEquals(120.0, Geo.distance(o, first.last()), 0.5)
        s.addRoads(motorway, first)
        assertTrue(s.needsRoadsAround(at(50.0, 0.0), 90.0).isEmpty())    // 50 m on: still covered
        assertEquals(3, s.needsRoadsAround(at(130.0, 0.0), 90.0).size)  // 130 m on: next request
    }

    @Test fun parsesLocateAnswerIntoDrivingDirection() {
        val shape = PolylineCodec.encode(listOf(at(0.0, 0.0), at(100.0, 0.0)), 6)
        fun edge(forward: Boolean, car: Boolean) = """{"edge":{"forward":$forward,"access":{"car":$car}},"edge_info":{"way_id":7,"shape":"$shape"}}"""
        val json = """[{"input_lat":48.3,"input_lon":14.3,"edges":[${edge(true, true)},${edge(false, true)},${edge(true, false)}],"nodes":[]}]"""
        val roads = RoadSnapper.parseLocate(json)
        assertEquals(2, roads.size)                                     // the footway (car=false) is left out
        assertEquals(90.0, Geo.bearing(roads[0].points.first(), roads[0].points.last()), 0.5)
        assertEquals(270.0, Geo.bearing(roads[1].points.first(), roads[1].points.last()), 0.5)
        assertTrue(RoadSnapper.locateRequest(listOf(o)).contains("\"radius\":35"))
    }
}
