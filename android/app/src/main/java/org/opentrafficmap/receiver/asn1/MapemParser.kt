package org.opentrafficmap.receiver.asn1

import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.IntersectionKeys
import org.opentrafficmap.receiver.nav.IntersectionMap
import org.opentrafficmap.receiver.nav.LaneConnection
import org.opentrafficmap.receiver.nav.LaneKind
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.Maneuver
import org.opentrafficmap.receiver.nav.MapLane

/**
 * Intersection topology from a decoded MAPEM (ISO TS 19091 MapData): lanes with their geometry,
 * direction, type and connections (target lane, manoeuvre, signal group).
 *
 * Node offsets are in cm (x east, y north): the first node relative to the intersection's
 * reference point, every further node relative to the previous one (J2735 NodeSetXY). Computed
 * lanes (a copy of a reference lane, shifted) are resolved after all explicit lanes.
 */
object MapemParser {

    fun parse(mapem: AsnValue, nowMs: Long): List<IntersectionMap> {
        val list = (mapem.at("map", "intersections") as? AsnValue.SeqOf)?.items ?: return emptyList()
        return list.mapNotNull { parseIntersection(it, nowMs) }
    }

    private fun parseIntersection(g: AsnValue, nowMs: Long): IntersectionMap? {
        val id = g.at("id", "id")?.asLong()?.toInt() ?: return null
        val region = g.at("id", "region")?.asLong()?.toInt()
        val lat = g.at("refPoint", "lat")?.asLong() ?: return null
        val lon = g.at("refPoint", "long")?.asLong() ?: return null
        val ref = LatLon(lat / 1e7, lon / 1e7)
        val laneSet = (g.at("laneSet") as? AsnValue.SeqOf)?.items ?: return null

        val explicit = HashMap<Int, List<LatLon>>()
        val pending = ArrayList<Pair<AsnValue, AsnValue>>()           // (lane, computed)
        val lanes = ArrayList<MapLane>()
        for (l in laneSet) {
            val laneId = l.at("laneID")?.asLong()?.toInt() ?: continue
            val nodeList = l.at("nodeList") as? AsnValue.Choice ?: continue
            if (nodeList.alt == "computed") { pending += l to nodeList.value; continue }
            val pts = nodes(ref, (nodeList.value as? AsnValue.SeqOf)?.items ?: continue)
            explicit[laneId] = pts
            lanes += lane(l, laneId, pts)
        }
        for ((l, c) in pending) {
            val laneId = l.at("laneID")?.asLong()?.toInt() ?: continue
            val base = explicit[c.at("referenceLaneId")?.asLong()?.toInt() ?: continue] ?: continue
            val dx = offset(c.at("offsetXaxis")); val dy = offset(c.at("offsetYaxis"))
            lanes += lane(l, laneId, base.map { shift(it, dx / 100.0, dy / 100.0) })
        }
        val name = (g.at("name") as? AsnValue.Str)?.s
        return IntersectionMap(IntersectionKeys.key(region, id), name, ref, lanes, nowMs)
    }

    private fun lane(l: AsnValue, laneId: Int, pts: List<LatLon>): MapLane {
        val dirs = (l.at("laneAttributes", "directionalUse") as? AsnValue.Bits)?.set.orEmpty()
        val ingress = "ingressPath" in dirs
        val type = (l.at("laneAttributes", "laneType") as? AsnValue.Choice)?.alt
        val kind = when (type) {
            "vehicle" -> LaneKind.VEHICLE
            "trackedVehicle" -> LaneKind.TRAM
            "bikeLane" -> LaneKind.BIKE
            "crosswalk" -> LaneKind.CROSSWALK
            "sidewalk" -> LaneKind.SIDEWALK
            else -> LaneKind.OTHER
        }
        val approach = (if (ingress) l.at("ingressApproach") else l.at("egressApproach"))?.asLong()?.toInt()
        val conns = (l.at("connectsTo") as? AsnValue.SeqOf)?.items.orEmpty().mapNotNull { c ->
            val to = c.at("connectingLane", "lane")?.asLong()?.toInt() ?: return@mapNotNull null
            LaneConnection(to, maneuver((c.at("connectingLane", "maneuver") as? AsnValue.Bits)?.set.orEmpty()),
                c.at("signalGroup")?.asLong()?.toInt())
        }
        return MapLane(laneId, kind, ingress, "egressPath" in dirs, approach, pts, conns)
    }

    private fun maneuver(bits: List<String>): Maneuver? = when {
        "maneuverStraightAllowed" in bits -> Maneuver.STRAIGHT
        "maneuverLeftAllowed" in bits || "maneuverLeftTurnOnRedAllowed" in bits -> Maneuver.LEFT
        "maneuverRightAllowed" in bits || "maneuverRightTurnOnRedAllowed" in bits -> Maneuver.RIGHT
        "maneuverUTurnAllowed" in bits -> Maneuver.UTURN
        else -> null
    }

    private fun nodes(ref: LatLon, items: List<AsnValue>): List<LatLon> {
        val out = ArrayList<LatLon>()
        var cur = ref
        for (n in items) {
            val d = n.at("delta") as? AsnValue.Choice ?: continue
            cur = if (d.alt == "node-LatLon") {
                val lo = d.value.at("lon")?.asLong() ?: continue
                val la = d.value.at("lat")?.asLong() ?: continue
                LatLon(la / 1e7, lo / 1e7)
            } else {
                val x = d.value.at("x")?.asLong() ?: continue
                val y = d.value.at("y")?.asLong() ?: continue
                shift(cur, x / 100.0, y / 100.0)
            }
            out += cur
        }
        return out
    }

    /** ComputedLane offsetXaxis / offsetYaxis: CHOICE { small, large } in cm. */
    private fun offset(v: AsnValue?): Double = when (v) {
        is AsnValue.Choice -> v.value.asLong()?.toDouble() ?: 0.0
        else -> v?.asLong()?.toDouble() ?: 0.0
    }

    private fun shift(p: LatLon, eastM: Double, northM: Double): LatLon = Geo.offset(p, eastM, northM)
}
