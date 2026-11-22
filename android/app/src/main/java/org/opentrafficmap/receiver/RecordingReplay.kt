package org.opentrafficmap.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationServices
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Plays a recording back as if it were received now: frames go into the live picture ([V2xHub]:
 * car screen, OsmAnd, phone map and lanes) and the phone log — not to MQTT or the recorder. Absolute
 * times in the messages are shifted to the replay time ([V2xHub.ingest] recordedAtMs).
 *
 * The recorded own track becomes the phone's location (GPS test provider and fused mock mode), so
 * OsmAnd, the widgets and lane matching follow the recorded drive. That needs V2X2MAP as the mock
 * location app (Developer options → Select mock location app; debug builds only); without it the
 * replay runs with the real position.
 */
object RecordingReplay {
    fun interface Listener { fun onReplayChanged() }

    private const val TAG = "RecordingReplay"
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var mock: MockLocation? = null

    @Volatile var label: String? = null; private set
    @Volatile var mockActive = false; private set
    val isRunning: Boolean get() = label != null

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun start(ctx: Context, events: List<ReplayTimeline.Event>, speed: Double, name: String) {
        stop()
        if (events.isEmpty()) return
        val t = HandlerThread("replay").apply { start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        label = name
        mockActive = events.any { it.fix != null } && MockLocation(ctx.applicationContext).let { m ->
            m.start().also { ok -> if (ok) mock = m }
        }
        val t0 = events.first().atMs
        val startReal = SystemClock.elapsedRealtime()
        var next = 0
        val step = object : Runnable {
            override fun run() {
                val elapsedRec = ((SystemClock.elapsedRealtime() - startReal) * speed).toLong()
                val batch = ArrayList<Pair<Frame, Long?>>()
                while (next < events.size && events[next].atMs - t0 <= elapsedRec) {
                    val e = events[next++]
                    e.frame?.let { batch += it to e.recordedAtMs }
                    e.fix?.let { f -> mock?.push(f) }
                }
                if (batch.isNotEmpty()) FramePipeline.onReplayFrames(batch)
                if (next >= events.size) { h.post { stop() }; return }
                val wait = ((events[next].atMs - t0 - elapsedRec) / speed).toLong().coerceIn(5L, 1000L)
                h.postDelayed(this, wait)
            }
        }
        h.post(step)
        notifyListeners()
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        mock?.stop(); mock = null; mockActive = false
        thread?.quitSafely(); thread = null; handler = null
        val was = label != null
        label = null
        if (was) notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it.onReplayChanged() }

    /** GPS test provider + fused mock mode; fails (false) unless this app is the mock location app. */
    private class MockLocation(private val ctx: Context) {
        private val lm = ctx.getSystemService(LocationManager::class.java)
        private val fused = LocationServices.getFusedLocationProviderClient(ctx)

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        fun start(): Boolean = try {
            runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }   // left over from an interrupted replay
            if (Build.VERSION.SDK_INT >= 31) {
                lm.addTestProvider(LocationManager.GPS_PROVIDER, ProviderProperties.Builder()
                    .setHasSpeedSupport(true).setHasBearingSupport(true).setHasAltitudeSupport(false)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build())
            } else {
                lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, true, true, 1, 1)
            }
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            runCatching { fused.setMockMode(true) }
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "not the mock location app — replay without own position")
            false
        } catch (e: Exception) {
            Log.w(TAG, "mock location failed", e); false
        }

        @SuppressLint("MissingPermission")
        fun push(p: TrackPoint) {
            fun loc(provider: String) = Location(provider).apply {
                latitude = p.pos.lat; longitude = p.pos.lon
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                accuracy = (p.accuracyM ?: 5.0).toFloat()
                p.speedMps?.let { speed = it.toFloat() }
                p.bearingDeg?.let { bearing = it.toFloat() }
            }
            runCatching { lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc(LocationManager.GPS_PROVIDER)) }
            runCatching { fused.setMockLocation(loc("fused")) }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            runCatching { fused.setMockMode(false) }
            runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
        }
    }
}
