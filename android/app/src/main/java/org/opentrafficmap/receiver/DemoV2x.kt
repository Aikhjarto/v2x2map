package org.opentrafficmap.receiver

import android.os.Handler
import android.os.Looper
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.SignalPhase
import org.opentrafficmap.receiver.nav.V2xHazard
import org.opentrafficmap.receiver.nav.V2xSignal

/**
 * Debug-build desk test: a simulated traffic light that cycles like a real one
 * (red 8 s, red+amber 2 s, green 8 s, amber 3 s, with time to the next change) and a
 * simulated roadworks hazard, both
 * in [V2xHub] for 10 minutes — visible on the car screen, the phone widgets and in OsmAnd.
 */
object DemoV2x {
    private const val SIGNAL_ID = 999_001L
    private const val DURATION_MS = 10 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }   // lazy: phaseAt is unit-tested on the JVM
    private var signalPos: LatLon? = null
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

    /** Light ~55 m north and hazard ~60 m east of [pos]. */
    fun start(pos: LatLon) {
        val now = System.currentTimeMillis()
        signalPos = LatLon(pos.lat + 0.0005, pos.lon)
        until = now + DURATION_MS
        V2xHub.putSimulatedHazard(V2xHazard("sim:osmand", LatLon(pos.lat, pos.lon + 0.0008), 3, 0,
            "roadworks (SIM)", now, until, simulated = true))
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val p = signalPos ?: return
            if (now > until) { V2xHub.clearSimulated(); signalPos = null; return }
            val (phase, endsAt) = phaseAt(now)
            V2xHub.putSimulatedSignal(V2xSignal(SIGNAL_ID, p, phase, now, simulated = true, endsAtMs = endsAt))
            main.postDelayed(this, 1000)
        }
    }
}
