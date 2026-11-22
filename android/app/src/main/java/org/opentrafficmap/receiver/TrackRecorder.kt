package org.opentrafficmap.receiver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Writes the phone's own GPS track next to a PCAP recording (`<name>-track.csv`), one line per
 * fix: `time_ms,lat,lon,speed_mps,bearing_deg,accuracy_m` (empty fields if unknown). A replay
 * ([RecordingReplay]) plays it back as the own position. Needs the location permission; without
 * it nothing is written.
 */
class TrackRecorder(private val context: Context) {
    private val tag = "TrackRecorder"
    private var out: BufferedWriter? = null
    private var thread: HandlerThread? = null
    var file: File? = null; private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.locations.forEach(::write) }
    }

    @Synchronized
    fun start(pcap: File) {
        if (out != null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val f = trackFileFor(pcap)
        try {
            out = f.bufferedWriter().also { it.write(HEADER); it.newLine() }
            file = f
            val t = HandlerThread("track-recorder").apply { start() }
            thread = t
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
            LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(req, callback, t.looper)
        } catch (e: Exception) {
            Log.w(tag, "start failed", e); stop()
        }
    }

    @Synchronized
    private fun write(l: Location) {
        val w = out ?: return
        w.write(String.format(Locale.ROOT, "%d,%.7f,%.7f,%s,%s,%s", l.time, l.latitude, l.longitude,
            if (l.hasSpeed()) "%.2f".format(Locale.ROOT, l.speed) else "",
            if (l.hasBearing()) "%.1f".format(Locale.ROOT, l.bearing) else "",
            if (l.hasAccuracy()) "%.1f".format(Locale.ROOT, l.accuracy) else ""))
        w.newLine()
    }

    @Synchronized
    fun stop() {
        runCatching { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback) }
        thread?.quitSafely(); thread = null
        runCatching { out?.flush(); out?.close() }
        out = null
    }

    companion object {
        const val HEADER = "time_ms,lat,lon,speed_mps,bearing_deg,accuracy_m"
        fun trackFileFor(pcap: File): File = File(pcap.parentFile, pcap.name.removeSuffix(".pcap") + "-track.csv")
    }
}
