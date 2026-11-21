package org.opentrafficmap.receiver.nav

import kotlin.math.roundToInt

/** Localised strings for the few things Valhalla doesn't give us. German or English. */
class NavText(val german: Boolean) {
    fun distance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} km"
        m >= 1_000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
            .let { if (german) it.replace('.', ',') else it }
        m >= 100 -> "${((m / 50).roundToInt() * 50)} m"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
    }

    fun spokenDistance(m: Double): String = when {
        m >= 1_000 -> {
            val km = (m / 100).roundToInt() / 10.0
            val s = if (km == km.toInt().toDouble()) km.toInt().toString() else km.toString().let { if (german) it.replace('.', ',') else it }
            if (german) "$s Kilometern" else "$s kilometers"
        }
        else -> {
            val r = if (m >= 100) (m / 50).roundToInt() * 50 else ((m / 10).roundToInt() * 10).coerceAtLeast(10)
            if (german) "$r Metern" else "$r meters"
        }
    }

    fun inDistance(m: Double, what: String) =
        if (german) "In ${spokenDistance(m)}: $what"
        else "In ${spokenDistance(m)}, ${what.replaceFirstChar { it.lowercase() }}"

    fun hazardAhead(label: String, m: Double) =
        if (german) "Achtung, $label in ${spokenDistance(m)}" else "Caution, $label in ${spokenDistance(m)}"

    val emergencyVehicle get() = if (german) "Achtung, Einsatzfahrzeug in der Nähe. Rettungsgasse bilden." else "Caution, emergency vehicle nearby. Make way."
    val rerouting get() = if (german) "Route wird neu berechnet" else "Rerouting"
    val arrived get() = if (german) "Sie haben Ihr Ziel erreicht" else "You have arrived"
    val signalRed get() = if (german) "Ampel rot" else "Red light"
    val signalYellow get() = if (german) "Ampel gelb" else "Amber light"
    val signalGreen get() = if (german) "Ampel grün" else "Green light"
    val signalUnknown get() = if (german) "Ampel" else "Traffic light"

    /** Labels for DENM cause codes (ETSI EN 302 637-3). */
    fun cause(code: Int?): String = (if (german) CAUSE_DE else CAUSE_EN)[code]
        ?: if (german) "Gefahrenstelle" else "hazard"

    companion object {
        private val CAUSE_EN = mapOf(
            1 to "traffic jam", 2 to "accident", 3 to "roadworks", 6 to "slippery road",
            9 to "obstacle on road", 10 to "obstacle on road", 11 to "animal on road",
            12 to "person on road", 14 to "wrong-way driver", 15 to "rescue operation",
            17 to "extreme weather", 18 to "poor visibility", 19 to "heavy precipitation",
            26 to "slow vehicle", 27 to "end of queue", 91 to "broken-down vehicle",
            92 to "post-crash", 93 to "human problem", 94 to "stationary vehicle",
            95 to "emergency vehicle approaching", 96 to "dangerous curve",
            97 to "collision risk", 98 to "signal violation", 99 to "dangerous situation",
        )
        private val CAUSE_DE = mapOf(
            1 to "Stau", 2 to "Unfall", 3 to "Baustelle", 6 to "glatte Fahrbahn",
            9 to "Hindernis", 10 to "Hindernis", 11 to "Tier auf der Fahrbahn",
            12 to "Person auf der Fahrbahn", 14 to "Geisterfahrer", 15 to "Rettungseinsatz",
            17 to "Extremwetter", 18 to "Sichtbehinderung", 19 to "Starkregen oder Schnee",
            26 to "langsames Fahrzeug", 27 to "Stauende", 91 to "Pannenfahrzeug",
            92 to "Unfallstelle", 93 to "Personenproblem", 94 to "stehendes Fahrzeug",
            95 to "Einsatzfahrzeug", 96 to "gefährliche Kurve",
            97 to "Kollisionsgefahr", 98 to "Rotlichtverstoß", 99 to "Gefahrensituation",
        )
    }
}

/**
 * Decides when to speak. Two prompts per maneuver: an early one ("In 500 m, turn right…")
 * and a final one a few seconds before the turn. Each V2X hazard is announced once,
 * and again if we get much closer.
 */
class GuidanceScheduler(private val text: NavText) {
    private val early = HashSet<Int>()
    private val final = HashSet<Int>()
    private val hazardAnnounced = HashMap<String, Double>()
    private var emergencyAnnouncedMs = 0L

    fun reset() { early.clear(); final.clear(); hazardAnnounced.clear() }

    fun onProgress(route: Route, st: NavState, speedMps: Double): String? {
        val idx = st.nextManeuver
        val m = route.maneuvers.getOrNull(idx) ?: return null
        val d = st.distToNextM
        val v = speedMps.coerceAtLeast(8.0)
        val earlyDist = when { v > 25 -> 1_500.0; v > 16 -> 800.0; else -> 400.0 }
        val finalDist = (v * 7).coerceIn(40.0, 250.0)
        if (idx !in final && d <= finalDist) {
            final += idx; early += idx
            return m.verbalPre ?: m.instruction
        }
        if (idx !in early && d <= earlyDist && d > finalDist * 1.6 && idx > 0) {
            early += idx
            val what = m.verbalAlert ?: m.instruction
            return text.inDistance(d, what.trimEnd('.'))
        }
        return null
    }

    fun onHazards(ahead: List<HazardAhead>): String? {
        for (h in ahead) {
            val prev = hazardAnnounced[h.hazard.key]
            if (prev == null || (prev > 1_200 && h.distM < 500)) {
                hazardAnnounced[h.hazard.key] = h.distM
                return text.hazardAhead(h.hazard.label, h.distM)
            }
        }
        return null
    }

    fun onEmergency(v: V2xVehicle?, nowMs: Long): String? {
        if (v == null || nowMs - emergencyAnnouncedMs < 90_000) return null
        emergencyAnnouncedMs = nowMs
        return text.emergencyVehicle
    }
}
