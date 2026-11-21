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
 *  - SPATEM → signal heads at the RSU position (30 s TTL)
 */
object V2xHub {

    fun interface Listener { fun onV2xChanged() }

    private const val VEHICLE_TTL_MS = 30_000L
    private const val SIGNAL_TTL_MS = 30_000L
    private const val MAX_HAZARD_TTL_MS = 30 * 60_000L

    private val lock = Any()
    private val vehicles = HashMap<Long, V2xVehicle>()
    private val hazards = HashMap<String, V2xHazard>()
    private val signals = HashMap<Long, V2xSignal>()
    private val frameTimes = ArrayDeque<Long>()
    @Volatile private var lastFrameMs = 0L
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Set by the car screen so hazard labels match the car's language. */
    @Volatile var text: NavText = NavText(german = false)

    var clock: () -> Long = System::currentTimeMillis

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun ingest(f: Frame) {
        val now = clock()
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
                ItsG5Decoder.MsgType.SPATEM -> if (ll != null) {
                    signals[f.stationId ?: 0L] = V2xSignal(f.stationId ?: 0L, ll, f.spatPhase.toSignal(), now)
                }
                else -> {}
            }
        }
        listeners.forEach { it.onV2xChanged() }
    }

    fun snapshot(): V2xSnapshot {
        val now = clock()
        synchronized(lock) {
            vehicles.values.removeAll { now - it.lastSeenMs > VEHICLE_TTL_MS }
            signals.values.removeAll { !it.simulated && now - it.lastSeenMs > SIGNAL_TTL_MS }
            hazards.values.removeAll { now > it.expiresMs }
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 60_000) frameTimes.removeFirst()
            return V2xSnapshot(vehicles.values.toList(), hazards.values.toList(), signals.values.toList(),
                lastFrameMs, frameTimes.size)
        }
    }

    // ---- simulation (used by the car screen's demo drive so you can test at your desk) ----

    fun putSimulatedHazard(h: V2xHazard) { synchronized(lock) { hazards[h.key] = h }; listeners.forEach { it.onV2xChanged() } }
    fun putSimulatedSignal(s: V2xSignal) { synchronized(lock) { signals[s.id] = s }; listeners.forEach { it.onV2xChanged() } }
    fun clearSimulated() {
        synchronized(lock) {
            hazards.values.removeAll { it.simulated }
            signals.values.removeAll { it.simulated }
        }
        listeners.forEach { it.onV2xChanged() }
    }

    fun clear() { synchronized(lock) { vehicles.clear(); hazards.clear(); signals.clear(); frameTimes.clear() } }

    private fun SpatTemParser.Phase?.toSignal() = when (this) {
        SpatTemParser.Phase.RED -> SignalPhase.RED
        SpatTemParser.Phase.YELLOW -> SignalPhase.YELLOW
        SpatTemParser.Phase.GREEN -> SignalPhase.GREEN
        else -> SignalPhase.UNKNOWN
    }
}
