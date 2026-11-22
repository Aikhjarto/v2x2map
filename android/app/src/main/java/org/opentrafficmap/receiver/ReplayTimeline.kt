package org.opentrafficmap.receiver

import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.LatLon
import java.time.Instant

/** One own-position fix of a recording. */
data class TrackPoint(val timeMs: Long, val pos: LatLon, val speedMps: Double?, val bearingDeg: Double?, val accuracyM: Double?)

/**
 * Builds the time line of a recording replay: when each frame was received (wall clock) and the
 * own track. Sources, best first:
 *  - frame receive times stored in the PCAP (recordings of this app),
 *  - the Play Store app's CSV (one row per frame: `time_iso`, …, `own_lat`, `own_lon`),
 *  - the PCAP timestamps as relative times only (old recordings with the ESP32 uptime).
 * The own track comes from `<name>-track.csv` ([TrackRecorder]) or the Play Store CSV.
 */
object ReplayTimeline {

    class Event(val atMs: Long, val frame: Frame?, val recordedAtMs: Long?, val fix: TrackPoint?)

    /** `time_ms,lat,lon,speed_mps,bearing_deg,accuracy_m` from [TrackRecorder]. */
    fun parseTrack(lines: Sequence<String>): List<TrackPoint> = lines.drop(1).mapNotNull { l ->
        val f = l.split(',')
        if (f.size < 3) return@mapNotNull null
        val t = f[0].toLongOrNull() ?: return@mapNotNull null
        val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
        TrackPoint(t, LatLon(lat, lon), f.getOrNull(3)?.toDoubleOrNull(), f.getOrNull(4)?.toDoubleOrNull(), f.getOrNull(5)?.toDoubleOrNull())
    }.toList()

    /** Play Store CSV: per frame row its receive time and the own position; consecutive duplicates removed. */
    class PlayStoreCsv(val frameTimes: List<Long?>, val track: List<TrackPoint>)

    fun parsePlayStoreCsv(lines: Sequence<String>): PlayStoreCsv? {
        val it = lines.iterator()
        if (!it.hasNext()) return null
        val head = it.next().split(',')
        val iTime = head.indexOf("time_iso"); val iLat = head.indexOf("own_lat"); val iLon = head.indexOf("own_lon")
        if (iTime < 0) return null
        val times = ArrayList<Long?>()
        val raw = ArrayList<Pair<Long, LatLon>>()
        while (it.hasNext()) {
            val f = it.next().split(',')
            val t = f.getOrNull(iTime)?.let { s -> runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() }
            times += t
            val lat = f.getOrNull(iLat)?.toDoubleOrNull(); val lon = f.getOrNull(iLon)?.toDoubleOrNull()
            if (t != null && lat != null && lon != null && (raw.isEmpty() || raw.last().second != LatLon(lat, lon))) raw += t to LatLon(lat, lon)
        }
        val track = raw.mapIndexed { i, (t, p) ->
            val next = raw.getOrNull(i + 1); val prev = raw.getOrNull(i - 1)
            val (a, b) = when { next != null -> (t to p) to next; prev != null -> prev to (t to p); else -> null to null }
            val speed = if (a != null && b != null && b.first > a.first) Geo.distance(a.second, b.second) / ((b.first - a.first) / 1000.0) else null
            val bearing = if (a != null && b != null && Geo.distance(a.second, b.second) > 1.0) Geo.bearing(a.second, b.second) else null
            TrackPoint(t, p, speed, bearing, null)
        }
        return PlayStoreCsv(times, track)
    }

    /**
     * Events sorted by time. Frames get their recorded wall time where known (then absolute times
     * in their messages are shifted to the replay time); otherwise PCAP-relative times and no shift.
     */
    fun build(frames: List<Frame>, csvFrameTimes: List<Long?>?, track: List<TrackPoint>): List<Event> {
        val raw = frames.mapIndexed { i, f -> if (f.receivedMs > 0) f.receivedMs else csvFrameTimes?.getOrNull(i) }
        // e.g. the firmware's boot test frame carries the fake time 1234567890 (2009): drop times
        // more than 12 h away from the recording's median
        val median = raw.filterNotNull().sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val wall = raw.map { t -> t?.takeIf { kotlin.math.abs(it - median) <= 12 * 3_600_000L } }
        val events = ArrayList<Event>()
        if (wall.any { it != null }) {
            var last = wall.firstNotNullOf { it }
            frames.forEachIndexed { i, f ->
                val t = wall[i] ?: last                  // e.g. the firmware's boot test frame
                last = t
                events += Event(t, f, wall[i], null)
            }
            track.forEach { events += Event(it.timeMs, null, null, it) }
        } else {
            // only the ESP32 uptime: relative timing, the own track cannot be aligned
            frames.forEach { f -> events += Event(f.sec * 1000 + f.usec / 1000, f, null, null) }
        }
        events.sortBy { it.atMs }
        return compressPauses(events)
    }

    /** Pauses longer than [maxGapMs] (parked, no V2X in range) are shortened to [toMs] in the replay. */
    fun compressPauses(events: List<Event>, maxGapMs: Long = 20_000, toMs: Long = 2_000): List<Event> {
        var removed = 0L
        return events.mapIndexed { i, e ->
            if (i > 0) { val gap = e.atMs - events[i - 1].atMs; if (gap > maxGapMs) removed += gap - toMs }
            if (removed == 0L) e else Event(e.atMs - removed, e.frame, e.recordedAtMs, e.fix)
        }
    }
}
