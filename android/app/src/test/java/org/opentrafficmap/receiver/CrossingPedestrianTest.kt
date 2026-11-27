package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.*

/** Pedestrian DENMs on the crosswalk of a signalised intersection are shown, not warned. */
class CrossingPedestrianTest {
    private val ref = LatLon(47.0, 15.0)
    private val crosswalk = MapLane(90, LaneKind.CROSSWALK, ingress = false, egress = false, approach = null,
        points = listOf(Geo.offset(ref, -6.0, 15.0), Geo.offset(ref, 6.0, 15.0)), connections = emptyList())
    private val map = IntersectionMap(1L, null, ref, listOf(crosswalk), 0L)

    private fun hazard(pos: LatLon, cause: Int = V2xAnalyzer.CAUSE_HUMAN_PRESENCE) =
        V2xHazard("k", pos, cause, 0, "pedestrian", 0L, Long.MAX_VALUE)

    @Test fun pedestrianOnCrosswalkIsNoWarning() {
        assertTrue(V2xAnalyzer.atSignalisedCrossing(hazard(Geo.offset(ref, 1.0, 17.0)), listOf(map), emptyList()))
    }

    @Test fun pedestrianNearATrafficLightWithoutLaneMapIsNoWarning() {
        val light = V2xSignal(7L, ref, SignalPhase.RED, 0L)
        assertTrue(V2xAnalyzer.atSignalisedCrossing(hazard(Geo.offset(ref, 10.0, 20.0)), emptyList(), listOf(light)))
    }

    @Test fun pedestrianElsewhereAndOtherCausesStillWarn() {
        val light = V2xSignal(7L, ref, SignalPhase.RED, 0L)
        assertFalse(V2xAnalyzer.atSignalisedCrossing(hazard(Geo.offset(ref, 0.0, 80.0)), listOf(map), listOf(light)))
        assertFalse(V2xAnalyzer.atSignalisedCrossing(hazard(Geo.offset(ref, 1.0, 17.0), cause = 3), listOf(map), listOf(light)))
    }

    @Test fun crossingPedestrianIsNotAHazardAhead() {
        val pos = Geo.offset(ref, 0.0, -100.0)                       // driving north towards the crossing
        val h = hazard(Geo.offset(ref, 1.0, 17.0))
        val snap = V2xSnapshot(emptyList(), listOf(h), emptyList(), 0L, 0)
        assertEquals(1, V2xAnalyzer.analyze(snap, pos, 0.0, null).hazards.size)
        val marked = snap.copy(hazards = listOf(h.copy(atSignalisedCrossing = true)))
        assertEquals(0, V2xAnalyzer.analyze(marked, pos, 0.0, null).hazards.size)
    }
}
