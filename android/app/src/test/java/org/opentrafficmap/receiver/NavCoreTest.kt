package org.opentrafficmap.receiver

import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.*

private fun check(c: Boolean, msg: String) = assertTrue(msg, c)

class NavCoreTest {
@Test

fun routeProgressGuidanceAndV2x() {
    // Straight east along lat 48.2 (Vienna) then north: an L-shaped route
    val pts = mutableListOf<LatLon>()
    for (i in 0..100) pts += LatLon(48.2, 16.30 + i * 0.0001)    // ~7.4 m steps, ~743 m
    for (i in 1..100) pts += LatLon(48.2 + i * 0.0001, 16.31)    // ~11.1 m steps, ~1112 m
    val enc = PolylineCodec.encode(pts)
    val dec = PolylineCodec.decode(enc)
    check(dec.size == pts.size && Geo.distance(dec[57], pts[57]) < 0.2, "polyline6 round-trip")

    val json = """{"trip":{"legs":[{"shape":"${enc.replace("\\","\\\\")}","maneuvers":[
      {"type":1,"instruction":"Drive east.","street_names":["Teststraße"],"length":0.743,"time":60,"begin_shape_index":0},
      {"type":15,"instruction":"Turn left onto Nordgasse.","verbal_transition_alert_instruction":"Turn left onto Nordgasse.","verbal_pre_transition_instruction":"Turn left onto Nordgasse.","street_names":["Nordgasse"],"length":1.112,"time":90,"begin_shape_index":100},
      {"type":4,"instruction":"You have arrived.","length":0,"time":0,"begin_shape_index":200}]}],
      "summary":{"length":1.855,"time":150}}}"""
    val r = RoutingClient.parseValhalla(json)
    check(r.maneuvers.size == 3 && r.maneuvers[1].streetNames == listOf("Nordgasse"), "valhalla parse")
    check(kotlin.math.abs(r.shapeLengthM - 1855) < 15, "route length ${r.shapeLengthM}")

    val eng = NavigationEngine(r)
    var st = eng.update(LatLon(48.20005, 16.3020), 5.0, 90.0, 12.0)   // 5 m north of route, 148 m in
    check(st.nextManeuver == 1 && kotlin.math.abs(st.distToNextM - 594) < 10, "next = left in ${st.distToNextM}")
    check(st.lateralM in 3.0..8.0 && !st.offRoute, "lateral ${st.lateralM}")
    check(st.remainingS in 120.0..145.0, "remaining time ${st.remainingS}")

    val g = GuidanceScheduler(NavText(false))
    st = eng.update(LatLon(48.2, 16.3051), 5.0, 90.0, 12.0)  // ~370 m to turn
    val say1 = g.onProgress(r, st, 12.0)
    check(say1 != null && say1.startsWith("In "), "early prompt: $say1")
    check(g.onProgress(r, st, 12.0) == null, "no repeat")
    st = eng.update(LatLon(48.2, 16.3093), 5.0, 90.0, 12.0)  // ~52 m to turn
    check(g.onProgress(r, st, 12.0) == "Turn left onto Nordgasse.", "final prompt")

    // V2X: hazard 300 m up the northern leg, signal near the corner, one hazard off-route
    val now = 1000L
    val snap = V2xSnapshot(
        vehicles = listOf(V2xVehicle(1, LatLon(48.2005, 16.3095), 0.0, 10.0, 10, now)),
        hazards = listOf(
            V2xHazard("a", LatLon(48.2027, 16.3101), 2, 0, "accident", now, now + 600000),
            V2xHazard("b", LatLon(48.19, 16.305), 3, 0, "roadworks", now, now + 600000)),
        signals = listOf(V2xSignal(7, LatLon(48.2002, 16.3102), SignalPhase.RED, now)),
        lastFrameMs = now, framesLastMinute = 10)
    val a = V2xAnalyzer.analyze(snap, LatLon(48.2, 16.3093), 90.0, eng)
    check(a.hazards.size == 1 && a.hazards[0].hazard.key == "a" && a.hazards[0].distM in 330.0..400.0, "hazard on route at ${a.hazards.map{it.distM}}")
    check(a.signal != null && a.signal!!.distM < 120, "signal ahead ${a.signal?.distM}")
    check(a.emergencyNearby?.id == 1L, "emergency vehicle")
    check(g.onHazards(a.hazards)?.contains("accident") == true, "hazard prompt")

    // No route: cone logic
    val a2 = V2xAnalyzer.analyze(snap, LatLon(48.2, 16.3093), 90.0, null)
    check(a2.signal != null, "signal in heading cone without route")

    // Off-route detection: 3 fixes 200 m south
    repeat(3) { st = eng.update(LatLon(48.198, 16.3093), 5.0, 90.0, 12.0) }
    check(st.offRoute, "off-route detected")
    // Arrival
    st = eng.update(LatLon(48.2099, 16.31), 5.0, 0.0, 5.0)
    st = eng.update(LatLon(48.20999, 16.31), 5.0, 0.0, 5.0)
    check(st.arrived, "arrived (remaining ${st.remainingM})")
    println(NavText(true).distance(1530.0) + " | " + NavText(true).spokenDistance(800.0))
    println("ALL PASSED")
}
}
