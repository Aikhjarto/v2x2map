package org.opentrafficmap.receiver.nav

import kotlin.math.abs
import kotlin.math.cos

/**
 * Intersection topology from MAPEM (ISO TS 19091 / SAE J2735 MapData) and the signal-group states
 * from SPATEM, and how both become the traffic lights the driver sees.
 *
 * Lane geometry follows J2735: the first node of a lane lies at the stop line (intersection side),
 * further nodes lead away from the intersection. Ingress lanes are driven towards the first node.
 */
enum class Maneuver { STRAIGHT, LEFT, RIGHT, UTURN }

enum class LaneKind { VEHICLE, BUS, TRAM, BIKE, CROSSWALK, SIDEWALK, OTHER }

data class LaneConnection(val toLane: Int, val maneuver: Maneuver?, val signalGroup: Int?)

data class MapLane(
    val laneId: Int,
    val kind: LaneKind,
    val ingress: Boolean,
    val egress: Boolean,
    /** ingressApproach for ingress lanes, egressApproach for egress lanes (if sent). */
    val approach: Int?,
    /** First point = stop line / intersection side. */
    val points: List<LatLon>,
    val connections: List<LaneConnection>,
) {
    /** Signal group that governs this lane: the one for going straight, else the first one. */
    val primaryGroup: Int? get() =
        (connections.firstOrNull { it.maneuver == Maneuver.STRAIGHT && it.signalGroup != null }
            ?: connections.firstOrNull { it.signalGroup != null })?.signalGroup

    /** Direction of travel towards the stop line (ingress), or away from it (egress). */
    val headingDeg: Double? get() {
        if (points.size < 2) return null
        return if (ingress) Geo.bearing(points[1], points[0]) else Geo.bearing(points[0], points[1])
    }
}

data class IntersectionMap(
    /** (region << 16) | id — the same key SPATEM uses for the intersection. */
    val key: Long,
    val name: String?,
    val ref: LatLon,
    val lanes: List<MapLane>,
    val lastSeenMs: Long,
    val simulated: Boolean = false,
) {
    fun lane(id: Int): MapLane? = lanes.firstOrNull { it.laneId == id }
}

data class GroupState(val phase: SignalPhase, val endsAtMs: Long?)

/** Signal-group states of one intersection from SPATEM. */
data class IntersectionSpat(
    val key: Long,
    val groups: Map<Int, GroupState>,
    /** Signal group of the first movement — used when no MAPEM is known. */
    val firstGroup: Int?,
    val lastSeenMs: Long,
    val simulated: Boolean = false,
)

/** An intersection as drawn on the maps: topology plus the current state of its groups. */
data class IntersectionView(val map: IntersectionMap, val groups: Map<Int, GroupState>) {
    fun phaseOf(lane: MapLane): SignalPhase =
        lane.primaryGroup?.let { groups[it]?.phase } ?: SignalPhase.UNKNOWN
}

object IntersectionKeys {
    fun key(region: Int?, id: Int): Long = ((region ?: 0).toLong() shl 16) or (id.toLong() and 0xFFFF)
    fun label(key: Long): String = if (key shr 16 == 0L) "${key and 0xFFFF}" else "${key shr 16}/${key and 0xFFFF}"
}

object IntersectionSignals {

    /**
     * One traffic light per approach and signal group: at the mean stop-line position of its
     * ingress vehicle lanes, facing their direction of travel. Lanes without a signal group are
     * not signalised and get no light.
     */
    fun derive(map: IntersectionMap, spat: IntersectionSpat?, nowMs: Long): List<V2xSignal> {
        val heads = map.lanes
            .filter { it.ingress && it.kind.isRoad && it.points.isNotEmpty() && it.primaryGroup != null }
            .groupBy { (it.approach ?: (1000 + it.laneId)) to it.primaryGroup!! }
        return heads.map { (k, lanes) ->
            val (approach, group) = k
            val stop = LatLon(lanes.map { it.points[0].lat }.average(), lanes.map { it.points[0].lon }.average())
            val state = spat?.groups?.get(group)
            val heading = lanes.mapNotNull { it.headingDeg }.let { h -> if (h.isEmpty()) null else meanDeg(h) }
            V2xSignal(
                id = (map.key shl 20) or ((approach.toLong() and 0xFFF) shl 8) or (group.toLong() and 0xFF),
                pos = stop, phase = state?.phase ?: SignalPhase.UNKNOWN,
                lastSeenMs = spat?.lastSeenMs ?: map.lastSeenMs,
                simulated = map.simulated, endsAtMs = state?.endsAtMs,
                headingDeg = heading, signalGroup = group, intersectionKey = map.key,
                lanes = lanes.map { it.points },
                straight = lanes.any { l -> l.connections.any { it.maneuver == Maneuver.STRAIGHT && it.signalGroup == group } },
            )
        }
    }

    private val LaneKind.isRoad get() = this == LaneKind.VEHICLE || this == LaneKind.BUS || this == LaneKind.TRAM

    /**
     * Sideways distance of [p] to the lane, the lane extended upstream (beyond its last node) by
     * [extendM] along its last segment — MAPEM lanes often end 30-100 m before the stop line,
     * while the light is relevant from further away. Null if [p] is past the stop line.
     */
    fun lateralToLane(points: List<LatLon>, p: LatLon, extendM: Double = 300.0): Double? {
        if (points.size < 2) return null
        val last = points.last(); val prev = points[points.size - 2]
        val segLen = Geo.distance(prev, last).coerceAtLeast(0.1)
        val far = Geo.interpolate(prev, last, 1 + extendM / segLen)
        val line = points + far
        if (isBeyond(line[0], line[1], p) && Geo.distance(p, line[0]) > 3.0) return null   // past the stop line
        return line.zipWithNext().minOf { (a, b) -> Geo.projectOnSegment(p, a, b).second }
    }

    /** True if [p] lies on the far side of [stop] as seen from [upstream]. */
    private fun isBeyond(stop: LatLon, upstream: LatLon, p: LatLon): Boolean {
        val k = cos(Math.toRadians(stop.lat))
        val ux = (upstream.lon - stop.lon) * k; val uy = upstream.lat - stop.lat
        val px = (p.lon - stop.lon) * k; val py = p.lat - stop.lat
        return ux * px + uy * py < 0
    }

    private fun meanDeg(h: List<Double>): Double {
        val x = h.sumOf { cos(Math.toRadians(it)) }; val y = h.sumOf { kotlin.math.sin(Math.toRadians(it)) }
        return Geo.normDeg(Math.toDegrees(kotlin.math.atan2(y, x)))
    }

    /** Heading tolerance between the driver and a light's direction of travel. */
    const val HEADING_TOLERANCE_DEG = 50.0

    fun facesDriver(s: V2xSignal, bearing: Double?): Boolean =
        s.headingDeg == null || bearing == null || abs(Geo.angleDiff(bearing, s.headingDeg)) <= HEADING_TOLERANCE_DEG
}
