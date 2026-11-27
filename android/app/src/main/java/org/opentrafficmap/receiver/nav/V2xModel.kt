package org.opentrafficmap.receiver.nav

import kotlin.math.abs

enum class SignalPhase { RED, YELLOW, GREEN, UNKNOWN }

data class V2xVehicle(
    val id: Long, val pos: LatLon, val headingDeg: Double?, val speedMps: Double?,
    val stationType: Int?, val lastSeenMs: Long,
) {
    /** ETSI StationType 10 = specialVehicles (police, ambulance, fire). */
    val isEmergency: Boolean get() = stationType == 10
}

data class V2xHazard(
    val key: String,                // originStation:sequence (ActionID)
    val pos: LatLon,
    val causeCode: Int?, val subCauseCode: Int?,
    val label: String,
    val lastSeenMs: Long,
    val expiresMs: Long,
    val simulated: Boolean = false,
    /**
     * A pedestrian reported on the crosswalk of a signalised intersection (see
     * [V2xAnalyzer.atSignalisedCrossing]): the traffic light handles it, so it is drawn on the map but
     * raises no warning.
     */
    val atSignalisedCrossing: Boolean = false,
) {
    val isEmergencyApproach: Boolean get() = causeCode == 95
}

data class V2xSignal(
    val id: Long, val pos: LatLon, val phase: SignalPhase, val lastSeenMs: Long,
    val simulated: Boolean = false,
    /** Absolute time of the next phase change (SPATEM timing), null if not sent. */
    val endsAtMs: Long? = null,
    /** Direction of travel the light applies to (from MAPEM lanes); null = unknown (RSU position only). */
    val headingDeg: Double? = null,
    val signalGroup: Int? = null,
    val intersectionKey: Long? = null,
    /** Geometry of the ingress lanes this light governs (first point = stop line). */
    val lanes: List<List<LatLon>> = emptyList(),
    /** True if the light governs going straight (preferred when lanes cannot be told apart). */
    val straight: Boolean = true,
) {
    /** Whole seconds until the next change, or null. */
    fun remainingS(nowMs: Long = System.currentTimeMillis()): Int? =
        endsAtMs?.let { (((it - nowMs).coerceAtLeast(0) + 999) / 1000).toInt() }
}

data class V2xSnapshot(
    val vehicles: List<V2xVehicle>,
    val hazards: List<V2xHazard>,
    val signals: List<V2xSignal>,
    val lastFrameMs: Long,
    val framesLastMinute: Int,
    /** Intersections with MAPEM topology, for drawing lanes. */
    val intersections: List<IntersectionView> = emptyList(),
) {
    companion object { val EMPTY = V2xSnapshot(emptyList(), emptyList(), emptyList(), 0, 0) }
}

data class HazardAhead(val hazard: V2xHazard, val distM: Double)
data class SignalAhead(val signal: V2xSignal, val distM: Double)

data class V2xAhead(
    val signal: SignalAhead?,
    val hazards: List<HazardAhead>,
    val emergencyNearby: V2xVehicle?,
)

/**
 * Decides which V2X objects matter to the driver right now. When a route is active
 * the objects are matched against the route ahead; otherwise a cone in front of the
 * car (driving direction) is used.
 */
object V2xAnalyzer {
    const val HAZARD_LOOKAHEAD_M = 5_000.0
    const val HAZARD_LATERAL_M = 60.0
    const val SIGNAL_LOOKAHEAD_M = 450.0
    const val SIGNAL_LATERAL_M = 60.0
    const val EMERGENCY_RADIUS_M = 600.0

    fun analyze(
        snap: V2xSnapshot,
        pos: LatLon,
        bearing: Double?,
        engine: NavigationEngine?,
    ): V2xAhead {
        val hazards = snap.hazards.filter { !it.atSignalisedCrossing }.mapNotNull { h ->
            val d = if (engine != null) engine.aheadOnRoute(h.pos, HAZARD_LATERAL_M, HAZARD_LOOKAHEAD_M)?.first
                    else inCone(pos, bearing, h.pos, 2_000.0, 40.0)
            d?.let { HazardAhead(h, it) }
        }.sortedBy { it.distM }

        val signal = pickSignal(snap.signals.filter { IntersectionSignals.facesDriver(it, bearing) }.mapNotNull { s ->
            val d = if (engine != null) engine.aheadOnRoute(s.pos, SIGNAL_LATERAL_M, SIGNAL_LOOKAHEAD_M)?.first
                    else inCone(pos, bearing, s.pos, 400.0, 70.0) ?: run {
                        // no heading yet (standing still): nearest within 250 m
                        if (bearing == null) Geo.distance(pos, s.pos).takeIf { it < 250 } else null
                    }
            d?.let { SignalAhead(s, it) }
        }, pos)

        val emergency = snap.vehicles.filter { it.isEmergency }
            .map { it to Geo.distance(pos, it.pos) }
            .filter { it.second < EMERGENCY_RADIUS_M }
            .minByOrNull { it.second }?.first

        return V2xAhead(signal, hazards, emergency)
    }

    /**
     * The nearest light ahead. Lights of the same intersection that face the driver (e.g. the
     * left-turn and the straight-on lane of one approach) are told apart by the driver's sideways
     * distance to their MAPEM lanes; straight-on wins when that is not clearly different
     * (GPS is about as accurate as a lane is wide).
     */
    internal fun pickSignal(cands: List<SignalAhead>, pos: LatLon): SignalAhead? {
        val nearest = cands.minByOrNull { it.distM } ?: return null
        val key = nearest.signal.intersectionKey ?: return nearest
        val same = cands.filter { it.signal.intersectionKey == key && it.signal.lanes.isNotEmpty() }
        if (same.size < 2) return nearest
        return same.minByOrNull { c ->
            val lat = c.signal.lanes.mapNotNull { IntersectionSignals.lateralToLane(it, pos) }.minOrNull() ?: 1e9
            lat + if (c.signal.straight) 0.0 else LANE_PREFERENCE_M
        } ?: nearest
    }

    /**
     * Distance up to which a hazard ahead raises the pop-up and the first voice warning: about
     * 90 s of driving, 800 m to 2.5 km. (The route lookahead is 5 km; warning that far ahead
     * felt like a false alarm, e.g. roadworks 4.7 km down the motorway right at the on-ramp.)
     * The banner on the map still shows hazards on the route up to [HAZARD_LOOKAHEAD_M].
     */
    fun alertDistanceM(speedMps: Double): Double = (speedMps * 90.0).coerceIn(800.0, 2_500.0)

    /** DENM causeCode humanPresenceOnTheRoad (pedestrians, cyclists on the road). */
    const val CAUSE_HUMAN_PRESENCE = 12
    /** A pedestrian DENM this close to a MAPEM crosswalk lane is on that crosswalk. */
    const val CROSSWALK_MATCH_M = 8.0
    /** Without crosswalk geometry: this close to a traffic light counts as its crossing. */
    const val SIGNAL_MATCH_M = 30.0

    /**
     * True for a pedestrian DENM (causeCode 12) on the crosswalk of a signalised intersection: on a MAPEM
     * crosswalk lane, or within [SIGNAL_MATCH_M] of a traffic light. Roadside units with pedestrian
     * detection report every person on such a crossing (seen on the first test drives: the same crossing,
     * every time someone walked over it). Cars are stopped by the light there, so it is no warning.
     * A pedestrian anywhere else on the road still is.
     */
    fun atSignalisedCrossing(h: V2xHazard, maps: Collection<IntersectionMap>, signals: Collection<V2xSignal>): Boolean {
        if (h.causeCode != CAUSE_HUMAN_PRESENCE) return false
        val onCrosswalk = maps.any { m -> m.lanes.any { l ->
            l.kind == LaneKind.CROSSWALK && distanceToLine(h.pos, l.points) <= CROSSWALK_MATCH_M } }
        return onCrosswalk || signals.any { Geo.distance(it.pos, h.pos) <= SIGNAL_MATCH_M }
    }

    private fun distanceToLine(p: LatLon, points: List<LatLon>): Double = when (points.size) {
        0 -> Double.MAX_VALUE
        1 -> Geo.distance(p, points[0])
        else -> points.zipWithNext().minOf { (a, b) -> Geo.projectOnSegment(p, a, b).second }
    }

    /** Extra sideways distance a turning lane needs to lose against the straight-on lane. */
    const val LANE_PREFERENCE_M = 1.5

    /** Distance to [p] if it lies within ±[halfAngle]° of [bearing] and [maxDist]; null otherwise. */
    private fun inCone(pos: LatLon, bearing: Double?, p: LatLon, maxDist: Double, halfAngle: Double): Double? {
        val d = Geo.distance(pos, p)
        if (d > maxDist) return null
        if (bearing == null) return null
        if (d < 15) return d
        return if (abs(Geo.angleDiff(Geo.bearing(pos, p), bearing)) <= halfAngle) d else null
    }
}
