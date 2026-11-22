package org.opentrafficmap.receiver.nav

/** Plain-text description of a MAPEM lane and its signal assignments (tap info on the maps). */
object IntersectionText {

    fun phase(p: SignalPhase?, german: Boolean): String = when (p) {
        SignalPhase.RED -> if (german) "rot" else "red"
        SignalPhase.YELLOW -> if (german) "gelb" else "amber"
        SignalPhase.GREEN -> if (german) "grün" else "green"
        else -> if (german) "unbekannt" else "unknown"
    }

    private fun maneuver(m: Maneuver?, german: Boolean): String = when (m) {
        Maneuver.STRAIGHT -> if (german) "geradeaus" else "straight"
        Maneuver.LEFT -> if (german) "links" else "left"
        Maneuver.RIGHT -> if (german) "rechts" else "right"
        Maneuver.UTURN -> if (german) "wenden" else "U-turn"
        null -> "?"
    }

    private fun kind(k: LaneKind, german: Boolean): String = when (k) {
        LaneKind.VEHICLE -> if (german) "Fahrstreifen" else "vehicle lane"
        LaneKind.BUS -> if (german) "Busspur" else "bus lane"
        LaneKind.TRAM -> if (german) "Gleis" else "tram track"
        LaneKind.BIKE -> if (german) "Radweg" else "bike lane"
        LaneKind.CROSSWALK -> if (german) "Schutzweg" else "crosswalk"
        LaneKind.SIDEWALK -> if (german) "Gehsteig" else "sidewalk"
        LaneKind.OTHER -> if (german) "sonstige" else "other"
    }

    fun title(view: IntersectionView, german: Boolean): String =
        (if (german) "Kreuzung " else "Intersection ") + IntersectionKeys.label(view.map.key) +
            (view.map.name?.let { " · $it" } ?: "")

    fun describe(view: IntersectionView, lane: MapLane, nowMs: Long, german: Boolean): String {
        val dir = when {
            lane.ingress && lane.egress -> if (german) "beide Richtungen" else "both directions"
            lane.ingress -> if (german) "Zufahrt" else "ingress"
            lane.egress -> if (german) "Ausfahrt" else "egress"
            else -> "-"
        }
        val head = buildString {
            append(if (german) "Spur " else "Lane "); append(lane.laneId)
            append(" · "); append(kind(lane.kind, german)); append(" · "); append(dir)
            lane.approach?.let { append(if (german) " (Arm $it)" else " (approach $it)") }
        }
        val lines = lane.connections.map { c ->
            val g = c.signalGroup?.let { view.groups[it] }
            buildString {
                append("→ "); append(if (german) "Spur " else "lane "); append(c.toLane)
                append(" · "); append(maneuver(c.maneuver, german))
                if (c.signalGroup != null) {
                    append(" · "); append(if (german) "Signalgruppe " else "signal group "); append(c.signalGroup)
                    append(": "); append(phase(g?.phase, german))
                    g?.endsAtMs?.let { append(", ${((it - nowMs).coerceAtLeast(0) + 999) / 1000} s") }
                } else append(if (german) " · ohne Signal" else " · no signal")
            }
        }
        return (listOf(head) + lines.ifEmpty { listOf(if (german) "keine Verbindungen" else "no connections") }).joinToString("\n")
    }
}
