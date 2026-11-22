package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.asn1.AsnSchema
import org.opentrafficmap.receiver.asn1.MapemParser
import org.opentrafficmap.receiver.asn1.SpatemParser
import org.opentrafficmap.receiver.asn1.UperDecoder
import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.GroupState
import org.opentrafficmap.receiver.nav.IntersectionKeys
import org.opentrafficmap.receiver.nav.IntersectionSignals
import org.opentrafficmap.receiver.nav.IntersectionSpat
import org.opentrafficmap.receiver.nav.LaneKind
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.Maneuver
import org.opentrafficmap.receiver.nav.SignalPhase
import org.opentrafficmap.receiver.nav.V2xAnalyzer
import org.opentrafficmap.receiver.nav.V2xSnapshot
import java.io.File
import kotlin.math.abs

/** Synthetic MAPEM/SPATEM from tools/asn1gen/make_mapem_vector.py and make_spatem_vector.py (intersection 4711). */
class MapemTest {
    private val schema by lazy { AsnSchema.load("iso", File("src/main/assets/its_asn1/iso.json").inputStream()) }
    private fun vector(root: String) = run {
        val hex = javaClass.classLoader!!.getResource("its_vectors.tsv")!!.readText().lines().first { it.startsWith("$root\t") }.split('\t')[1]
        UperDecoder(schema).decode(root, ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
    }
    private val ref = LatLon(48.2, 14.3)
    private val map by lazy { MapemParser.parse(vector("MAPEM"), 1000L).single() }
    private val spat = IntersectionSpat(IntersectionKeys.key(0, 4711),
        mapOf(2 to GroupState(SignalPhase.RED, 5000L), 3 to GroupState(SignalPhase.GREEN, null), 4 to GroupState(SignalPhase.GREEN, null)),
        2, 1000L)

    private fun near(a: LatLon, b: LatLon, m: Double = 0.05) = assertTrue("$a vs $b: ${Geo.distance(a, b)} m", Geo.distance(a, b) < m)

    @Test fun topology() {
        assertEquals(IntersectionKeys.key(0, 4711), map.key)
        near(ref, map.ref)
        assertEquals(8, map.lanes.size)
        val l1 = map.lane(1)!!
        assertTrue(l1.ingress); assertEquals(LaneKind.VEHICLE, l1.kind); assertEquals(1, l1.approach)
        near(Geo.offset(ref, 4.5, -12.0), l1.points[0])                         // stop line, relative to the reference point
        near(Geo.offset(ref, 4.5, -42.0), l1.points[1])                         // relative to the previous node
        assertEquals(listOf(5 to Maneuver.STRAIGHT, 7 to Maneuver.RIGHT), l1.connections.map { it.toLane to it.maneuver })
        assertEquals(2, l1.primaryGroup)
        assertEquals(0.0, abs(Geo.angleDiff(l1.headingDeg!!, 0.0)), 0.5)            // driven northwards
        assertEquals(3, map.lane(2)!!.primaryGroup)                               // left-turn lane
        assertTrue(map.lane(5)!!.egress); assertEquals(0.0, abs(Geo.angleDiff(map.lane(5)!!.headingDeg!!, 0.0)), 0.5)
        assertEquals(LaneKind.CROSSWALK, map.lane(8)!!.kind)
    }

    @Test fun allSignalGroupsOfSpatem() {
        val s = SpatemParser.parseAll(vector("SPATEM"), 1_795_300_000_000L).single()
        assertEquals(IntersectionKeys.key(0, 4711), s.key)
        assertEquals(listOf(2, 5), s.groups.keys.toList())
        assertEquals(SignalPhase.RED, s.groups[2]!!.phase); assertEquals(SignalPhase.GREEN, s.groups[5]!!.phase)
    }

    @Test fun oneLightPerApproachAndGroupAtTheStopLine() {
        val lights = IntersectionSignals.derive(map, spat, 1000L)
        assertEquals(setOf(2, 3, 4), lights.map { it.signalGroup }.toSet())
        assertEquals(4, lights.size)                                              // group 4 on two approaches; no crosswalk light
        val south = lights.single { it.signalGroup == 2 }
        near(Geo.offset(ref, 4.5, -12.0), south.pos)
        assertEquals(SignalPhase.RED, south.phase); assertEquals(5000L, south.endsAtMs)
        assertEquals(0.0, abs(Geo.angleDiff(south.headingDeg!!, 0.0)), 0.5)
        assertTrue(south.straight)
        assertTrue(!lights.single { it.signalGroup == 3 }.straight)
        assertEquals(setOf(90.0, 270.0), lights.filter { it.signalGroup == 4 }.map { Math.round(it.headingDeg!!).toDouble() }.toSet())
        assertEquals("ids are unique", 4, lights.map { it.id }.toSet().size)
    }

    private fun lightFor(pos: LatLon, bearing: Double) =
        V2xAnalyzer.analyze(V2xSnapshot(emptyList(), emptyList(), IntersectionSignals.derive(map, spat, 1000L), 0, 0), pos, bearing, null).signal

    @Test fun driverGetsTheLightOfHisLane() {
        assertEquals(2, lightFor(Geo.offset(ref, 4.5, -80.0), 0.0)!!.signal.signalGroup)        // straight lane
        assertEquals(3, lightFor(Geo.offset(ref, 1.2, -80.0), 0.0)!!.signal.signalGroup)        // clearly in the left-turn lane
        assertEquals(2, lightFor(Geo.offset(ref, 3.0, -80.0), 0.0)!!.signal.signalGroup)        // in between → straight
        assertEquals(2, lightFor(Geo.offset(ref, 4.5, -300.0), 0.0)!!.signal.signalGroup)       // beyond the mapped lane
        val fromWest = lightFor(Geo.offset(ref, -60.0, -3.0), 90.0)!!.signal
        assertEquals(4, fromWest.signalGroup); assertEquals(90.0, fromWest.headingDeg!!, 1.0)
        // the cross street's light is ahead and near, but does not face a northbound driver
        assertNull(lightFor(Geo.offset(ref, -20.0, -30.0), 0.0)?.signal?.takeIf { it.signalGroup == 4 })
    }

    @Test fun lateralDistanceIsNullPastTheStopLine() {
        val l1 = map.lane(1)!!.points
        assertNotNull(IntersectionSignals.lateralToLane(l1, Geo.offset(ref, 4.5, -20.0)))
        assertNull(IntersectionSignals.lateralToLane(l1, Geo.offset(ref, 4.5, 5.0)))
    }

    @Test fun hubDerivesLightsFromMapAndSpat() {
        V2xHub.clear()
        val now = System.currentTimeMillis()
        V2xHub.putSimulatedIntersection(DemoV2x.intersection(ref, now), IntersectionSpat(DemoV2x.INTERSECTION_KEY, DemoV2x.groupsAt(now), 2, now, simulated = true))
        val snap = V2xHub.snapshot()
        assertEquals(4, snap.signals.size)
        assertEquals(1, snap.intersections.size)
        assertEquals(snap.intersections[0].groups[2]!!.phase, snap.signals.single { it.signalGroup == 2 }.phase)
        V2xHub.clearSimulated()
        assertTrue(V2xHub.snapshot().signals.isEmpty())
    }
}
