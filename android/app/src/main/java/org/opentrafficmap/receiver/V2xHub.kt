package org.opentrafficmap.receiver

import org.opentrafficmap.receiver.nav.*
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide store of the live V2X picture, fed from [MainActivity.handleFrames]
 * and read by the Android Auto navigation screen. Thread-safe; no Android deps.
 *
 *  - CAM    → vehicles (one per station, 30 s TTL)
 *  - DENM   → hazards at the DENM *event* position (falls back to sender position),
 *             removed on cancellation, TTL = validityDuration (capped at 30 min)
 *  - SPATEM → signal-group states per intersection (30 s TTL). With the intersection's MAPEM
 *             (topology, 2 min TTL) one light per approach and signal group at its stop line,
 *             facing the direction of travel; without MAPEM one light (first group) at the RSU.
 *  - IVIM   → speed-limit zones of road signs (until validTo, at most 10 min after the last
 *             reception; removed on cancellation/negation). Needs [messageDecoder].
 */
object V2xHub {

    fun interface Listener { fun onV2xChanged() }

    private const val VEHICLE_TTL_MS = 30_000L
    private const val SIGNAL_TTL_MS = 30_000L
    private const val MAX_HAZARD_TTL_MS = 30 * 60_000L
    private const val SPEED_ZONE_TTL_MS = 10 * 60_000L
    private const val MAP_TTL_MS = 2 * 60_000L

    private val lock = Any()
    private val vehicles = HashMap<Long, V2xVehicle>()
    private val hazards = HashMap<String, V2xHazard>()
    private val signals = HashMap<Long, V2xSignal>()
    private val speedZones = HashMap<String, IvimSpeedZone>()
    private val maps = HashMap<Long, IntersectionMap>()
    private class SpatEntry(val spat: IntersectionSpat, val rsuPos: LatLon?)
    private val spats = HashMap<Long, SpatEntry>()

    /** Decodes a raw frame to its ITS message (set by [App] with the generated ASN.1 tables). */
    @Volatile var messageDecoder: ((ByteArray) -> org.opentrafficmap.receiver.asn1.AsnValue?)? = null
    private val frameTimes = ArrayDeque<Long>()
    @Volatile private var lastFrameMs = 0L
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Set by the car screen so hazard labels match the car's language. */
    @Volatile var text: NavText = NavText(german = false)

    var clock: () -> Long = System::currentTimeMillis

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /**
     * @param recordedAtMs when the frame was originally received (replay of a recording): absolute
     *   times in the message (SPATEM TimeMarks, IVIM validity) are read relative to it and shifted to now.
     */
    fun ingest(f: Frame, recordedAtMs: Long? = null) {
        val now = clock()
        val msgNow = recordedAtMs ?: now
        val shift = now - msgNow
        synchronized(lock) {
            lastFrameMs = now
            frameTimes.addLast(now)
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 60_000) frameTimes.removeFirst()

            val ll = f.latLon?.let { LatLon(it.first, it.second) }
            when (f.msgType) {
                ItsG5Decoder.MsgType.CAM -> if (ll != null && f.stationId != null) {
                    vehicles[f.stationId] = V2xVehicle(f.stationId, ll, f.headingDeg, f.speedMps, f.stationType, now)
                }
                ItsG5Decoder.MsgType.DENM -> {
                    val ev = f.denmEvent
                    val key = if (ev != null) "${ev.originStationId}:${ev.sequenceNumber}" else "gn:${f.stationId}"
                    if (ev?.terminated == true) { hazards.remove(key); return@synchronized }
                    val pos = if (ev?.lat != null && ev.lon != null) LatLon(ev.lat, ev.lon) else ll
                    if (pos != null) {
                        val ttl = ((ev?.validitySec ?: 600) * 1000L).coerceIn(60_000L, MAX_HAZARD_TTL_MS)
                        val cause = ev?.cause ?: f.denmCause
                        hazards[key] = V2xHazard(key, pos, cause?.causeCode, cause?.subCauseCode,
                            text.cause(cause?.causeCode), now, now + ttl)
                    }
                }
                ItsG5Decoder.MsgType.IVIM -> ingestIvim(f.payload, now, shift)
                ItsG5Decoder.MsgType.MAPEM -> {
                    val v = decode(f.payload)?.takeIf { it.at("map") != null }
                    v?.let { org.opentrafficmap.receiver.asn1.MapemParser.parse(it, now) }?.forEach { maps[it.key] = it }
                }
                ItsG5Decoder.MsgType.SPATEM -> {
                    // full decode: all signal groups with timing; quick parser (first group) as fallback
                    val all = decode(f.payload)?.takeIf { it.at("spat") != null }
                        ?.let { org.opentrafficmap.receiver.asn1.SpatemParser.parseAll(it, msgNow) }.orEmpty()
                        .map { sp -> if (shift == 0L) sp else sp.copy(lastSeenMs = now,
                            groups = sp.groups.mapValues { (_, g) -> g.copy(endsAtMs = g.endsAtMs?.plus(shift)) }) }
                    if (all.isNotEmpty()) {
                        signals.remove(f.stationId ?: 0L)
                        all.forEach { spats[it.key] = SpatEntry(it, ll) }
                    } else if (ll != null) {
                        signals[f.stationId ?: 0L] = V2xSignal(f.stationId ?: 0L, ll, f.spatPhase.toSignal(), now)
                    }
                }
                else -> {}
            }
        }
        listeners.forEach { it.onV2xChanged() }
    }

    private fun decode(payload: ByteArray) = try { messageDecoder?.invoke(payload) } catch (_: Exception) { null }

    /** Called under [lock]. */
    private fun ingestIvim(payload: ByteArray, now: Long, shift: Long = 0) {
        val v = decode(payload)?.takeIf { it.at("ivi") != null } ?: return
        val r = org.opentrafficmap.receiver.asn1.IvimParser.parse(v, now) ?: return
        speedZones.keys.removeAll { it.startsWith(r.iviKey + ":") }
        if (!r.withdrawn) r.zones.forEach { speedZones[it.key] = if (shift == 0L) it else it.copy(validToMs = it.validToMs?.plus(shift)) }
    }

    /** Active speed-limit zones from IVIM road signs. */
    fun speedZones(): List<IvimSpeedZone> {
        val now = clock()
        synchronized(lock) {
            speedZones.values.removeAll { now - it.lastSeenMs > SPEED_ZONE_TTL_MS || (it.validToMs != null && now > it.validToMs) }
            return speedZones.values.toList()
        }
    }

    fun snapshot(): V2xSnapshot {
        val now = clock()
        synchronized(lock) {
            vehicles.values.removeAll { now - it.lastSeenMs > VEHICLE_TTL_MS }
            signals.values.removeAll { !it.simulated && now - it.lastSeenMs > SIGNAL_TTL_MS }
            hazards.values.removeAll { now > it.expiresMs }
            maps.values.removeAll { !it.simulated && now - it.lastSeenMs > MAP_TTL_MS }
            spats.values.removeAll { !it.spat.simulated && now - it.spat.lastSeenMs > SIGNAL_TTL_MS }
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 60_000) frameTimes.removeFirst()
            val derived = spats.values.flatMap { e ->
                val map = maps[e.spat.key]
                if (map != null) IntersectionSignals.derive(map, e.spat, now)
                else listOfNotNull(e.rsuPos?.let { pos ->
                    val g = e.spat.firstGroup?.let { e.spat.groups[it] }
                    V2xSignal((e.spat.key shl 20) or 0xFFFFF, pos, g?.phase ?: SignalPhase.UNKNOWN, e.spat.lastSeenMs,
                        simulated = e.spat.simulated, endsAtMs = g?.endsAtMs, signalGroup = e.spat.firstGroup,
                        intersectionKey = e.spat.key)
                })
            }
            val views = maps.values.map { IntersectionView(it, spats[it.key]?.spat?.groups.orEmpty()) }
            return V2xSnapshot(vehicles.values.toList(), hazards.values.toList(), signals.values.toList() + derived,
                lastFrameMs, frameTimes.size, views)
        }
    }

    // ---- simulation (used by the car screen's demo drive so you can test at your desk) ----

    fun putSimulatedHazard(h: V2xHazard) { synchronized(lock) { hazards[h.key] = h }; listeners.forEach { it.onV2xChanged() } }
    fun putSimulatedSignal(s: V2xSignal) { synchronized(lock) { signals[s.id] = s }; listeners.forEach { it.onV2xChanged() } }
    fun putSimulatedIntersection(map: IntersectionMap, spat: IntersectionSpat) {
        synchronized(lock) { maps[map.key] = map; spats[spat.key] = SpatEntry(spat, map.ref) }
        listeners.forEach { it.onV2xChanged() }
    }
    fun clearSimulated() {
        synchronized(lock) {
            hazards.values.removeAll { it.simulated }
            signals.values.removeAll { it.simulated }
            maps.values.removeAll { it.simulated }
            spats.values.removeAll { it.spat.simulated }
        }
        listeners.forEach { it.onV2xChanged() }
    }

    fun clear() { synchronized(lock) { vehicles.clear(); hazards.clear(); signals.clear(); speedZones.clear(); maps.clear(); spats.clear(); frameTimes.clear() } }

    private fun SpatTemParser.Phase?.toSignal() = when (this) {
        SpatTemParser.Phase.RED -> SignalPhase.RED
        SpatTemParser.Phase.YELLOW -> SignalPhase.YELLOW
        SpatTemParser.Phase.GREEN -> SignalPhase.GREEN
        else -> SignalPhase.UNKNOWN
    }
}
