package org.opentrafficmap.receiver.car

import android.graphics.*
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import org.opentrafficmap.receiver.R
import org.opentrafficmap.receiver.nav.ManeuverKind
import org.opentrafficmap.receiver.nav.RouteManeuver
import org.opentrafficmap.receiver.nav.SignalPhase

/** Programmatic icons (turn arrows, hazard, signal) + Valhalla → Car App maneuver mapping. */
object CarIcons {

    private val cache = HashMap<String, CarIcon>()

    fun res(ctx: CarContext, id: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, id)).build()

    fun search(ctx: CarContext) = res(ctx, R.drawable.ic_car_search)
    fun zoomIn(ctx: CarContext) = res(ctx, R.drawable.ic_car_zoom_in)
    fun zoomOut(ctx: CarContext) = res(ctx, R.drawable.ic_car_zoom_out)
    fun recenter(ctx: CarContext) = res(ctx, R.drawable.ic_car_my_location)
    fun close(ctx: CarContext) = res(ctx, R.drawable.ic_car_close)
    fun warning(ctx: CarContext) = res(ctx, R.drawable.ic_car_warning)
    fun volumeOn(ctx: CarContext) = res(ctx, R.drawable.ic_car_volume_on)
    fun volumeOff(ctx: CarContext) = res(ctx, R.drawable.ic_car_volume_off)
    fun northUp(ctx: CarContext) = res(ctx, R.drawable.ic_car_north_up)
    fun headingUp(ctx: CarContext) = res(ctx, R.drawable.ic_car_heading_up)
    fun settings(ctx: CarContext) = res(ctx, R.drawable.ic_car_settings)

    fun maneuverIcon(m: RouteManeuver): CarIcon {
        val key = "m${m.type}/${m.roundaboutExit}"
        return cache.getOrPut(key) { bitmapIcon(drawManeuver(m)) }
    }

    fun hazardIcon(): CarIcon = cache.getOrPut("hazard") {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6D00.toInt() }
        val tri = Path().apply { moveTo(64f, 8f); lineTo(122f, 116f); lineTo(6f, 116f); close() }
        c.drawPath(tri, p)
        p.color = Color.BLACK; p.textSize = 80f; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("!", 64f, 106f, p)
        bitmapIcon(b)
    }

    fun signalIcon(phase: SignalPhase): CarIcon = cache.getOrPut("sig$phase") {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF212121.toInt() }
        c.drawRoundRect(RectF(40f, 4f, 88f, 124f), 22f, 22f, p)
        listOf(SignalPhase.RED to 0xFFFF1744.toInt(), SignalPhase.YELLOW to 0xFFFFC400.toInt(), SignalPhase.GREEN to 0xFF00E676.toInt())
            .forEachIndexed { i, (ph, col) ->
                p.color = if (ph == phase) col else (col and 0x00FFFFFF) or 0x40000000
                c.drawCircle(64f, 26f + i * 38f, 15f, p)
            }
        bitmapIcon(b)
    }

    private fun bitmapIcon(b: Bitmap) = CarIcon.Builder(IconCompat.createWithBitmap(b)).build()

    /** White turn arrow on transparent background. */
    private fun drawManeuver(m: RouteManeuver): Bitmap {
        val size = 128
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (m.type) {
            ManeuverKind.DESTINATION, ManeuverKind.DESTINATION_LEFT, ManeuverKind.DESTINATION_RIGHT -> {
                // map pin
                c.drawCircle(64f, 50f, 30f, f)
                c.drawPath(Path().apply { moveTo(38f, 64f); lineTo(64f, 118f); lineTo(90f, 64f); close() }, f)
                f.color = Color.BLACK; c.drawCircle(64f, 50f, 11f, f)
            }
            ManeuverKind.ROUNDABOUT_ENTER, ManeuverKind.ROUNDABOUT_EXIT -> {
                c.drawCircle(64f, 54f, 26f, p)
                c.drawLine(64f, 124f, 64f, 82f, p)
                // exit spoke toward upper right
                c.drawLine(84f, 36f, 108f, 14f, p)
                if (m.roundaboutExit > 0) {
                    f.textSize = 30f; f.textAlign = Paint.Align.CENTER; f.typeface = Typeface.DEFAULT_BOLD
                    c.drawText("${m.roundaboutExit}", 64f, 65f, f)
                }
            }
            else -> {
                val angle = ManeuverKind.arrowAngle(m.type)
                if (kotlin.math.abs(angle) == 180) {
                    val dir = if (angle > 0) 1 else -1
                    // simple U: two stems joined at the top, arrow head pointing down
                    val u = Path().apply {
                        val l = if (dir > 0) 40f else 88f; val r = if (dir > 0) 88f else 40f
                        moveTo(l, 120f); lineTo(l, 52f)
                        quadTo(l, 22f, 64f, 22f); quadTo(r, 22f, r, 52f); lineTo(r, 78f)
                    }
                    c.drawPath(u, p)
                    val r = if (dir > 0) 88f else 40f
                    c.drawPath(Path().apply { moveTo(r - 18f, 74f); lineTo(r + 18f, 74f); lineTo(r, 100f); close() }, f)
                } else {
                    // stem from bottom to centre, then a segment in the turn direction with a head
                    val cx = 64f; val cy = 66f
                    c.drawLine(cx, 124f, cx, cy, p)
                    val rad = Math.toRadians(angle.toDouble())
                    val len = 44f
                    val ex = cx + (len * kotlin.math.sin(rad)).toFloat()
                    val ey = cy - (len * kotlin.math.cos(rad)).toFloat()
                    c.drawLine(cx, cy, ex, ey, p)
                    c.save(); c.translate(ex, ey); c.rotate(angle.toFloat())
                    c.drawPath(Path().apply { moveTo(0f, -18f); lineTo(18f, 8f); lineTo(-18f, 8f); close() }, f)
                    c.restore()
                }
            }
        }
        return b
    }

    /** Valhalla maneuver → androidx.car.app Maneuver (assumes right-hand traffic for roundabouts). */
    fun toCarManeuver(m: RouteManeuver): Maneuver {
        val t = when (m.type) {
            ManeuverKind.START, ManeuverKind.START_LEFT, ManeuverKind.START_RIGHT -> Maneuver.TYPE_DEPART
            ManeuverKind.DESTINATION -> Maneuver.TYPE_DESTINATION
            ManeuverKind.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
            ManeuverKind.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
            ManeuverKind.BECOMES -> Maneuver.TYPE_NAME_CHANGE
            ManeuverKind.CONTINUE, ManeuverKind.STAY_STRAIGHT, ManeuverKind.RAMP_STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverKind.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverKind.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverKind.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverKind.UTURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
            ManeuverKind.UTURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverKind.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverKind.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverKind.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverKind.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            ManeuverKind.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            ManeuverKind.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            ManeuverKind.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            ManeuverKind.STAY_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            ManeuverKind.STAY_LEFT -> Maneuver.TYPE_KEEP_LEFT
            ManeuverKind.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverKind.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
            ManeuverKind.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
            ManeuverKind.ROUNDABOUT_ENTER ->
                if (m.roundaboutExit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverKind.ROUNDABOUT_EXIT -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            ManeuverKind.FERRY_ENTER -> Maneuver.TYPE_FERRY_BOAT
            else -> Maneuver.TYPE_UNKNOWN
        }
        val b = Maneuver.Builder(t).setIcon(maneuverIcon(m))
        if (t == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) b.setRoundaboutExitNumber(m.roundaboutExit)
        return b.build()
    }
}
