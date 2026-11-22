package org.opentrafficmap.receiver

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import org.opentrafficmap.receiver.nav.IntersectionView
import org.opentrafficmap.receiver.nav.LaneKind
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.MapLane
import org.opentrafficmap.receiver.nav.SignalPhase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot
import kotlin.math.max

/**
 * MAPEM lane assignments on the phone map (from zoom 16): ingress lanes in the colour of their
 * signal group's current phase with the group number at the stop line, egress lanes grey,
 * crosswalks dashed, and thin curves for the connections (ingress → egress lane) in the colour of
 * their signal group. A tap on a lane calls [onLaneTap].
 */
class IntersectionOverlay(
    private val density: Float,
    private val onLaneTap: (IntersectionView, MapLane) -> Unit,
) : Overlay() {

    @Volatile var views: List<IntersectionView> = emptyList()

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val connPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dash = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 11f * density }
    private val pt = Point()
    private val path = Path()

    companion object {
        const val MIN_ZOOM = 16.0
        fun phaseColor(p: SignalPhase): Int = when (p) {
            SignalPhase.RED -> 0xFFE53935.toInt()
            SignalPhase.YELLOW -> 0xFFFFB300.toInt()
            SignalPhase.GREEN -> 0xFF43A047.toInt()
            SignalPhase.UNKNOWN -> 0xFF78909C.toInt()
        }
        private const val EGRESS = 0xFFB0BEC5.toInt()
        private const val BIKE = 0xFF7CB342.toInt()
        private const val WALK = 0xFFFFFFFF.toInt()
    }

    private fun MapView.px(p: LatLon): Point = projection.toPixels(GeoPoint(p.lat, p.lon), Point())

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || map.zoomLevelDouble < MIN_ZOOM) return
        val laneW = max(3f * density, map.projection.metersToPixels(2.6f))
        for (v in views) {
            // lanes: dark edge first, then the colour
            for (lane in v.map.lanes) {
                if (lane.points.size < 2) continue
                linePath(map, lane.points)
                val colour = colourOf(v, lane)
                val w = if (lane.kind == LaneKind.CROSSWALK || lane.kind == LaneKind.SIDEWALK || lane.kind == LaneKind.BIKE) laneW * 0.6f else laneW
                edgePaint.strokeWidth = w + 2f * density
                edgePaint.pathEffect = null
                c.drawPath(path, edgePaint)
                lanePaint.color = colour
                lanePaint.strokeWidth = w
                lanePaint.alpha = if (lane.ingress && lane.kind == LaneKind.VEHICLE) 0xE6 else 0xB3
                lanePaint.pathEffect = if (lane.kind == LaneKind.CROSSWALK) dash else null
                c.drawPath(path, lanePaint)
            }
            // connections through the intersection
            val centre = map.px(v.map.ref)
            for (lane in v.map.lanes) {
                if (!lane.ingress || lane.points.isEmpty()) continue
                val from = map.px(lane.points[0])
                for (conn in lane.connections) {
                    val to = v.map.lane(conn.toLane)?.points?.firstOrNull() ?: continue
                    val end = map.px(to)
                    path.reset(); path.moveTo(from.x.toFloat(), from.y.toFloat())
                    path.quadTo(centre.x.toFloat(), centre.y.toFloat(), end.x.toFloat(), end.y.toFloat())
                    connPaint.color = conn.signalGroup?.let { v.groups[it]?.phase }?.let(::phaseColor) ?: EGRESS
                    connPaint.strokeWidth = 2f * density
                    connPaint.pathEffect = if (conn.signalGroup == null) dash else null
                    c.drawPath(path, connPaint)
                }
            }
            // signal-group numbers at the stop line, slightly back along the lane; lanes of the same
            // approach are only ~3 m apart, so their labels are staggered along the lane
            val order = v.map.lanes.filter { it.ingress }.groupBy { it.approach ?: -it.laneId }
                .flatMap { (_, ls) -> ls.sortedBy { it.laneId }.mapIndexed { i, l -> l.laneId to i } }.toMap()
            for (lane in v.map.lanes) {
                if (!lane.ingress || lane.points.size < 2) continue
                val groups = lane.connections.mapNotNull { it.signalGroup }.distinct()
                if (groups.isEmpty()) continue
                val a = map.px(lane.points[0]); val b = map.px(lane.points[1])
                val len = hypot((b.x - a.x).toFloat(), (b.y - a.y).toFloat()).coerceAtLeast(1f)
                val back = (14f + 22f * (order[lane.laneId] ?: 0)) * density
                val x = a.x + (b.x - a.x) / len * back; val y = a.y + (b.y - a.y) / len * back
                val label = groups.joinToString("/")
                val r = max(8f * density, text.measureText(label) / 2 + 4f * density)
                fill.color = 0xFF000000.toInt(); c.drawCircle(x, y, r + 1.5f * density, fill)
                fill.color = phaseColor(lane.primaryGroup?.let { v.groups[it]?.phase } ?: SignalPhase.UNKNOWN)
                c.drawCircle(x, y, r, fill)
                c.drawText(label, x, y + text.textSize * 0.36f, text)
            }
        }
    }

    private fun colourOf(v: IntersectionView, lane: MapLane): Int = when {
        lane.kind == LaneKind.CROSSWALK || lane.kind == LaneKind.SIDEWALK -> WALK
        lane.kind == LaneKind.BIKE -> BIKE
        lane.ingress && lane.primaryGroup != null -> phaseColor(v.phaseOf(lane))
        else -> EGRESS
    }

    private fun linePath(map: MapView, pts: List<LatLon>) {
        path.reset()
        pts.forEachIndexed { i, p ->
            map.projection.toPixels(GeoPoint(p.lat, p.lon), pt)
            if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        if (map.zoomLevelDouble < MIN_ZOOM) return false
        val maxPx = 18f * density
        var best: Pair<IntersectionView, MapLane>? = null
        var bestD = Float.MAX_VALUE
        for (v in views) for (lane in v.map.lanes) {
            val px = lane.points.map { map.px(it) }
            for (i in 0 until px.size - 1) {
                val d = segDist(e.x, e.y, px[i], px[i + 1])
                if (d < bestD) { bestD = d; best = v to lane }
            }
        }
        val hit = best?.takeIf { bestD <= maxPx } ?: return false
        onLaneTap(hit.first, hit.second)
        return true
    }

    private fun segDist(x: Float, y: Float, a: Point, b: Point): Float {
        val dx = (b.x - a.x).toFloat(); val dy = (b.y - a.y).toFloat()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }
}
