package org.opentrafficmap.receiver.asn1

import org.opentrafficmap.receiver.nav.SignalPhase

/**
 * Phase and time to the next change of a decoded SPATEM (ISO TS 19091). Like the rest of the
 * app it uses the first movement (signal group) of the first intersection — not necessarily the
 * driver's lane (that needs MAPEM lane matching).
 */
object SpatemParser {
    class Result(val phase: SignalPhase, val endsAtMs: Long?)

    /** TimeMark: tenths of a second within the current UTC hour; 36001 = unknown. */
    private const val TIMEMARK_UNKNOWN = 36001L
    private const val HOUR_TENTHS = 36000L

    fun parse(spatem: AsnValue, nowMs: Long): Result? {
        val inter = (spatem.at("spat", "intersections") as? AsnValue.SeqOf)?.items?.firstOrNull() ?: return null
        val movement = (inter.at("states") as? AsnValue.SeqOf)?.items?.firstOrNull() ?: return null
        val event = (movement.at("state-time-speed") as? AsnValue.SeqOf)?.items?.firstOrNull() ?: return null
        val phase = phaseOf((event.at("eventState") as? AsnValue.Enum)?.name)
        val timing = event.at("timing")
        val mark = timing?.at("likelyTime")?.asLong() ?: timing?.at("minEndTime")?.asLong()
        return Result(phase, mark?.let { endTime(it, nowMs) })
    }

    fun phaseOf(eventState: String?): SignalPhase = when (eventState) {
        "stop-And-Remain", "stop-Then-Proceed" -> SignalPhase.RED
        "pre-Movement", "permissive-clearance", "protected-clearance", "caution-Conflicting-Traffic" -> SignalPhase.YELLOW
        "permissive-Movement-Allowed", "protected-Movement-Allowed" -> SignalPhase.GREEN
        else -> SignalPhase.UNKNOWN
    }

    /**
     * Absolute time of [timeMark] (tenths of a second in the current or next hour). Marks that
     * already passed are taken as next hour, except when they lie only a few seconds back
     * (clock skew between the traffic controller and the phone) — then "now".
     */
    fun endTime(timeMark: Long, nowMs: Long): Long? {
        if (timeMark < 0 || timeMark >= TIMEMARK_UNKNOWN || timeMark > HOUR_TENTHS) return null
        val nowTenths = (nowMs % 3_600_000L) / 100
        var delta = timeMark - nowTenths
        if (delta < -50) delta += HOUR_TENTHS          // wrapped into the next hour
        if (delta < 0) delta = 0
        return nowMs + delta * 100
    }
}
