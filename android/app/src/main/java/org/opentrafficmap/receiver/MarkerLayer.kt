package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Map markers of received frames. Tapping a marker opens its bubble and reports the frame to
 * [onFrameTapped] (the frame log jumps to it); [focus] does the opposite for a frame picked in the log.
 */
class MarkerLayer(
    private val map: MapView,
    private val context: Context,
    private val onFrameTapped: (Frame) -> Unit = {},
) {

    private data class Entry(val marker: Marker, val createdMs: Long)

    private val entries = ArrayDeque<Entry>()

    // One marker per CAM station, updated in place instead of stacking
    private val camMarkers  = mutableMapOf<Long, Marker>()
    private val camLastSeen = mutableMapOf<Long, Long>()

    // Path tracking for CAM vehicles
    private val pathPoints   = mutableMapOf<Long, MutableList<GeoPoint>>()
    private val pathLines    = mutableMapOf<Long, Polyline>()
    private val pathLastSeen = mutableMapOf<Long, Long>()

    /**
     * Where a frame is drawn. A DENM's sender is often a roadside unit far from the event (e.g. at a
     * motorway ramp, warning of roadworks 5 km on): the hazard goes where the event is.
     */
    private fun positionOf(f: Frame): GeoPoint? {
        val ev = f.denmEvent
        val evLat = ev?.lat; val evLon = ev?.lon
        val ll = if (evLat != null && evLon != null) evLat to evLon else f.latLon ?: return null
        return GeoPoint(ll.first, ll.second)
    }

    private val tapListener = Marker.OnMarkerClickListener { m, _ ->
        m.showInfoWindow()
        (m.relatedObject as? Frame)?.let(onFrameTapped)
        true
    }

    /**
     * Shows [f] on the map (frame log → map): centres on its marker and opens the bubble. CAM stations
     * have one marker that moves, so for a vehicle that is where it was last seen.
     */
    fun focus(f: Frame): Boolean {
        val m = if (f.msgType == ItsG5Decoder.MsgType.CAM && f.stationId != null) camMarkers[f.stationId]
                else entries.lastOrNull { it.marker.relatedObject === f }?.marker
        val pt = m?.position ?: positionOf(f) ?: return false
        map.controller.animateTo(pt)
        m?.showInfoWindow()
        return true
    }

    fun add(f: Frame) {
        val pt = positionOf(f) ?: return

        if (f.msgType == ItsG5Decoder.MsgType.CAM && f.stationId != null) {
            updatePath(f.stationId, pt)
            updateCamMarker(f, pt)
            return
        }

        val m = Marker(map).apply {
            position = pt
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon    = makeDrawable(f.msgType)
            f.headingDeg?.let { rotation = it.toFloat() }
            title   = buildTitle(f)
            snippet = buildSnippet(f)
            relatedObject = f
            setOnMarkerClickListener(tapListener)
        }
        map.overlays.add(m)
        entries.addLast(Entry(m, System.currentTimeMillis()))
        prune()
    }

    private fun updateCamMarker(f: Frame, pt: GeoPoint) {
        val sid = f.stationId!!
        val m = camMarkers.getOrPut(sid) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener(tapListener)
            }.also { map.overlays.add(it) }
        }
        m.position = pt
        m.relatedObject = f                       // latest frame of the vehicle
        m.icon     = makeCarIcon(f.speedMps, f.headingDeg)
        m.title    = buildTitle(f)
        m.snippet  = buildSnippet(f)
        camLastSeen[sid] = System.currentTimeMillis()
        map.invalidate()
    }

    /** Bitmap with pre-rotated car icon + speed text below.
     *  Rotation is baked in so the label stays horizontal on the map. */
    private fun makeCarIcon(speedMps: Double?, headingDeg: Double?): Drawable {
        val dm      = context.resources.displayMetrics
        val iconPx  = (40 * dm.density).toInt()
        val hasSpd  = speedMps != null && speedMps > 0.5
        val textH   = if (hasSpd) (16 * dm.density).toInt() else 0
        val totalH  = iconPx + textH

        val bm = Bitmap.createBitmap(iconPx, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)

        val carD = ContextCompat.getDrawable(context, R.drawable.ic_marker_car)!!.mutate()
        DrawableCompat.setTint(carD, ItsG5Decoder.MsgType.CAM.color)
        canvas.save()
        canvas.rotate(headingDeg?.toFloat() ?: 0f, iconPx / 2f, iconPx / 2f)
        carD.setBounds(0, 0, iconPx, iconPx)
        carD.draw(canvas)
        canvas.restore()

        if (hasSpd) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize  = 10 * dm.density
                textAlign = Paint.Align.CENTER
                color     = 0xFFFFFFFF.toInt()
                setShadowLayer(1.5f * dm.density, 0f, 0.5f * dm.density, 0xFF000000.toInt())
            }
            canvas.drawText("%.0f km/h".format(speedMps!! * 3.6),
                             iconPx / 2f, iconPx + 11 * dm.density, paint)
        }

        return BitmapDrawable(context.resources, bm)
    }

    fun prune() {
        val now   = System.currentTimeMillis()
        val ttlMs = Prefs.markerTtlMinutes(context).toLong() * 60_000L
        var changed = false

        while (entries.isNotEmpty() && now - entries.first().createdMs > ttlMs) {
            map.overlays.remove(entries.removeFirst().marker)
            changed = true
        }
        while (entries.size > MAX_MARKERS) {
            map.overlays.remove(entries.removeFirst().marker)
            changed = true
        }

        val stalePaths = pathLastSeen.entries.filter { now - it.value > ttlMs }.map { it.key }
        for (sid in stalePaths) {
            pathLines.remove(sid)?.let { map.overlays.remove(it); changed = true }
            pathPoints.remove(sid)
            pathLastSeen.remove(sid)
        }

        val staleCams = camLastSeen.entries.filter { now - it.value > ttlMs }.map { it.key }
        for (sid in staleCams) {
            camMarkers.remove(sid)?.let { map.overlays.remove(it); changed = true }
            camLastSeen.remove(sid)
        }

        if (changed) map.invalidate()
    }

    fun clear() {
        for (e in entries) map.overlays.remove(e.marker)
        entries.clear()
        for (poly in pathLines.values) map.overlays.remove(poly)
        pathLines.clear(); pathPoints.clear(); pathLastSeen.clear()
        for (m in camMarkers.values) map.overlays.remove(m)
        camMarkers.clear(); camLastSeen.clear()
        map.invalidate()
    }

    private fun updatePath(stationId: Long, pt: GeoPoint) {
        val pts = pathPoints.getOrPut(stationId) { mutableListOf() }
        pts.add(pt)
        if (pts.size > PATH_MAX_POINTS) pts.removeAt(0)

        val line = pathLines.getOrPut(stationId) {
            Polyline().also { poly ->
                poly.outlinePaint.color       = ItsG5Decoder.MsgType.CAM.color
                poly.outlinePaint.strokeWidth = 5f
                poly.outlinePaint.alpha       = 160
                map.overlays.add(0, poly)
            }
        }
        line.setPoints(pts)
        pathLastSeen[stationId] = System.currentTimeMillis()
        map.invalidate()
    }

    private fun makeDrawable(t: ItsG5Decoder.MsgType): Drawable {
        val resId = when (t) {
            ItsG5Decoder.MsgType.CAM    -> R.drawable.ic_marker_car
            ItsG5Decoder.MsgType.SPATEM -> R.drawable.ic_marker_trafficlight
            ItsG5Decoder.MsgType.MAPEM  -> R.drawable.ic_marker_intersection
            ItsG5Decoder.MsgType.DENM   -> R.drawable.ic_marker_hazard
            else                        -> R.drawable.ic_marker_dot
        }
        val d = ContextCompat.getDrawable(context, resId)!!.mutate()
        DrawableCompat.setTint(d, t.color)
        return d
    }

    private fun buildTitle(f: Frame): String {
        val sid = f.stationId?.let { "#%08x".format(it.toInt()) } ?: ""
        val cause = f.denmEvent?.cause?.let { " · ${it.label()}" } ?: ""
        return if (sid.isEmpty()) "${f.msgType.short} frame #${f.seq}$cause"
               else "${f.msgType.short} $sid$cause"
    }

    private fun buildSnippet(f: Frame): String {
        val (lat, lon) = f.latLon ?: return "len=${f.len}"
        val sb = StringBuilder()
        val ev = f.denmEvent
        if (ev?.lat != null && ev.lon != null) sb.append("event lat=%.6f  lon=%.6f\nsender ".format(ev.lat, ev.lon))
        sb.append("lat=%.6f  lon=%.6f\n".format(lat, lon))
        f.speedMps?.takeIf { it > 0.5 }?.let { sb.append("speed=%.1f km/h\n".format(it * 3.6)) }
        f.headingDeg?.let { sb.append("hdg=%.0f°\n".format(it)) }
        sb.append("len=${f.len}")
        return sb.toString()
    }

    companion object {
        private const val MAX_MARKERS     = 500
        private const val PATH_MAX_POINTS = 50
    }
}
