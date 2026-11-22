package org.opentrafficmap.receiver

import android.os.Handler
import android.os.Looper
import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.GroupState
import org.opentrafficmap.receiver.nav.IntersectionMap
import org.opentrafficmap.receiver.nav.IntersectionSpat
import org.opentrafficmap.receiver.nav.LaneConnection
import org.opentrafficmap.receiver.nav.LaneKind
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.Maneuver
import org.opentrafficmap.receiver.nav.MapLane
import org.opentrafficmap.receiver.nav.SignalPhase
import org.opentrafficmap.receiver.nav.V2xHazard

/**
 * Debug-build desk test: a simulated signalised intersection ~55 m north of the given position
 * (MAPEM-like topology, right-hand traffic) whose signal groups cycle like real ones (red 8 s,
 * red+amber 2 s, green 8 s, amber 3 s, with time to the next change), plus a simulated roadworks
 * hazard — all in [V2xHub] for 10 minutes, visible on the car screen, the phone map and in OsmAnd.
 *
 *  - from the south: straight/right lane (group 2) and left-turn lane (group 3, 3 s later)
 *  - from the west and the east: one lane each (group 4, opposite to group 2)
 *  - egress lanes to the north, west and east; a crosswalk on the south arm
 */
object DemoV2x {
    const val INTERSECTION_KEY = 64_711L                // region 0, id 64711: outside real ids we saw
    private const val DURATION_MS = 10 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }   // lazy: phaseAt is unit-tested on the JVM
    private var map: IntersectionMap? = null
    private var until = 0L

    // (phase, duration in s): red, red+amber (shown as YELLOW), green, amber
    private val CYCLE = listOf(SignalPhase.RED to 8, SignalPhase.YELLOW to 2, SignalPhase.GREEN to 8, SignalPhase.YELLOW to 3)
    private val CYCLE_S = CYCLE.sumOf { it.second }

    /** Phase at [tMs] and the absolute time of its end (like SPATEM timing). */
    fun phaseAt(tMs: Long): Pair<SignalPhase, Long> {
        var t = (tMs / 1000) % CYCLE_S
        val cycleStartMs = tMs - t * 1000 - tMs % 1000
        var end = 0L
        for ((phase, dur) in CYCLE) {
            end += dur
            if (t < dur) return phase to cycleStartMs + end * 1000
            t -= dur
        }
        return SignalPhase.UNKNOWN to tMs
    }

    /** Group states at [nowMs]: group 3 runs 3 s behind group 2, group 4 half a cycle (opposite). */
    fun groupsAt(nowMs: Long): Map<Int, GroupState> = mapOf(2 to 0L, 3 to -3_000L, 4 to CYCLE_S * 500L).mapValues { (_, shift) ->
        val (phase, endsAt) = phaseAt(nowMs + shift)
        GroupState(phase, endsAt - shift)
    }

    /** The demo intersection with its centre at [centre] (lane offsets in metres, x east, y north). */
    fun intersection(centre: LatLon, nowMs: Long): IntersectionMap {
        fun pts(vararg xy: Pair<Double, Double>) = xy.map { (x, y) -> Geo.offset(centre, x, y) }
        fun lane(id: Int, ingress: Boolean, approach: Int, points: List<LatLon>, vararg c: LaneConnection) =
            MapLane(id, LaneKind.VEHICLE, ingress, !ingress, approach, points, c.toList())
        val lanes = listOf(
            lane(1, true, 1, pts(4.5 to -12.0, 4.5 to -42.0, 4.5 to -120.0),
                LaneConnection(5, Maneuver.STRAIGHT, 2), LaneConnection(7, Maneuver.RIGHT, 2)),
            lane(2, true, 1, pts(1.5 to -12.0, 1.5 to -42.0), LaneConnection(6, Maneuver.LEFT, 3)),
            lane(3, true, 2, pts(-12.0 to -3.0, -42.0 to -3.0, -120.0 to -3.0), LaneConnection(7, Maneuver.STRAIGHT, 4)),
            lane(4, true, 3, pts(12.0 to 3.0, 42.0 to 3.0, 120.0 to 3.0), LaneConnection(6, Maneuver.STRAIGHT, 4)),
            lane(5, false, 4, pts(3.0 to 12.0, 3.0 to 42.0)),
            lane(6, false, 2, pts(-12.0 to 3.0, -42.0 to 3.0)),
            lane(7, false, 3, pts(12.0 to -3.0, 42.0 to -3.0)),
            MapLane(8, LaneKind.CROSSWALK, true, true, null, pts(-6.0 to -9.0, 6.0 to -9.0), emptyList()),
        )
        return IntersectionMap(INTERSECTION_KEY, "V2X2MAP demo", centre, lanes, nowMs, simulated = true)
    }

    /** Intersection centre ~55 m north and hazard ~60 m east of [pos]. */
    fun start(pos: LatLon) {
        val now = System.currentTimeMillis()
        map = intersection(LatLon(pos.lat + 0.0005, pos.lon), now)
        until = now + DURATION_MS
        V2xHub.putSimulatedHazard(V2xHazard("sim:osmand", LatLon(pos.lat, pos.lon + 0.0008), 3, 0,
            "roadworks (SIM)", now, until, simulated = true))
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val m = map ?: return
            if (now > until) { V2xHub.clearSimulated(); map = null; return }
            V2xHub.putSimulatedIntersection(m.copy(lastSeenMs = now),
                IntersectionSpat(INTERSECTION_KEY, groupsAt(now), 2, now, simulated = true))
            main.postDelayed(this, 1000)
        }
    }
}
