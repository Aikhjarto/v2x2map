package org.opentrafficmap.receiver.car

import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.opentrafficmap.receiver.nav.*
import kotlin.math.*

/** Everything the renderer needs for one frame. Built on the main thread, read on the render thread. */
data class RenderState(
    val pos: LatLon?,
    val headingDeg: Double?,
    val speedMps: Double,
    val route: Route?,
    val distAlong: Double,
    val v2x: V2xSnapshot,
    val ahead: V2xAhead?,
    val navigating: Boolean,
    val text: NavText,
    val speedLimitKph: Int? = null,
    val speeding: Boolean = false,
    /** Status of the ESP32 link for the V2X chip while no V2X data arrives. */
    val linkHint: String? = null,
)

/**
 * Draws the map onto the Android Auto surface: raster tiles, route, own car,
 * and the V2X layer (vehicles from CAM, hazards from DENM, signals from SPATEM),
 * plus a traffic-light HUD for the next signal on the route.
 */
class MapRenderer(private val carContext: CarContext) : SurfaceCallback {

    @Volatile var state: RenderState? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var dpi = 160
    @Volatile private var visible = Rect()
    @Volatile private var stable = Rect()

    // Camera
    @Volatile var following = true; private set
    @Volatile private var userZoomDelta = 0.0
    @Volatile private var panCenter: LatLon? = null
    private var camBearing = 0.0
    private var camZoom = 16.0

    // Smoothed own position (interpolates between 1 Hz GPS fixes)
    private var fromPos: LatLon? = null
    private var toPos: LatLon? = null
    private var fixTime = 0L
    private var smoothHeading = 0.0

    private val thread = HandlerThread("car-map").apply { start() }
    private val handler = Handler(thread.looper)
    private val tiles = TileCache(carContext) { requestFrame() }
    private var running = false

    var onFollowChanged: ((Boolean) -> Unit)? = null

    // ------------------------------------------------------------------ SurfaceCallback

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        surface = c.surface; width = c.width; height = c.height; dpi = c.dpi
        if (visible.isEmpty) visible = Rect(0, 0, width, height)
        if (stable.isEmpty) stable = Rect(0, 0, width, height)
        startLoop()
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        handler.post { surface = null }
        stopLoop()
    }

    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); requestFrame() }
    override fun onStableAreaChanged(r: Rect) { stable = Rect(r); requestFrame() }

    override fun onScroll(dx: Float, dy: Float) {
        handler.post {
            val center = panCenter ?: currentCenter() ?: return@post
            val z = camZoom
            val scale = tileScale() * 2.0.pow(z - floor(z))
            // undo camera rotation
            val b = Math.toRadians(camBearing)
            val wx = (dx * cos(b) - dy * sin(b)) / scale
            val wy = (dx * sin(b) + dy * cos(b)) / scale
            val z0 = floor(z).toInt()
            val (cx, cy) = world(center, z0)
            panCenter = unworld(cx + wx, cy + wy, z0)
            if (following) { following = false; onFollowChanged?.invoke(false) }
            drawFrame()
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        userZoomDelta = (userZoomDelta + log2(scaleFactor.toDouble())).coerceIn(-6.0, 3.0)
        requestFrame()
    }

    fun zoomBy(d: Double) { userZoomDelta = (userZoomDelta + d).coerceIn(-6.0, 3.0); requestFrame() }

    fun recenter() {
        panCenter = null; userZoomDelta = 0.0
        if (!following) { following = true; onFollowChanged?.invoke(true) }
        requestFrame()
    }

    fun onNewFix(p: LatLon) {
        handler.post {
            val now = SystemClock.uptimeMillis()
            fromPos = interpolatedPos(now) ?: p
            toPos = p; fixTime = now
        }
    }

    fun requestFrame() { handler.post { if (!running) drawFrame() } }

    fun release() { stopLoop(); handler.post { tiles.shutdown(); thread.quitSafely() } }

    // ------------------------------------------------------------------ loop

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            drawFrame()
            handler.postDelayed(this, 66) // ~15 fps
        }
    }

    private fun startLoop() = handler.post { if (!running) { running = true; handler.post(tick) } }
    private fun stopLoop() = handler.post { running = false; handler.removeCallbacks(tick) }

    // ------------------------------------------------------------------ projection helpers

    private fun tileScale(): Double = (dpi / 160.0).coerceIn(1.0, 3.0) * 0.9

    /** Web-Mercator world pixel at zoom z (256-px base tiles). */
    private fun world(p: LatLon, z: Int): Pair<Double, Double> {
        val n = 256.0 * (1 shl z)
        val x = (p.lon + 180.0) / 360.0 * n
        val lat = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * n
        return x to y
    }

    private fun unworld(x: Double, y: Double, z: Int): LatLon {
        val n = 256.0 * (1 shl z)
        val lon = x / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        return LatLon(lat, lon)
    }

    private fun interpolatedPos(now: Long): LatLon? {
        val a = fromPos ?: return toPos
        val b = toPos ?: return a
        val t = ((now - fixTime) / 1000.0).coerceIn(0.0, 1.0)
        return Geo.interpolate(a, b, t)
    }

    private fun currentCenter(): LatLon? = panCenter ?: interpolatedPos(SystemClock.uptimeMillis()) ?: state?.pos

    // ------------------------------------------------------------------ drawing

    private val paintTile = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paintRouteCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF0D47A1.toInt()
    }
    private val paintRoute = Paint(paintRouteCasing).apply { color = 0xFF2F80ED.toInt() }
    private val paintRouteDone = Paint(paintRouteCasing).apply { color = 0x88808080.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    private fun drawFrame() {
        val surf = surface ?: return
        if (!surf.isValid) return
        val canvas = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) surf.lockHardwareCanvas() else surf.lockCanvas(null)
        } catch (_: Throwable) { try { surf.lockCanvas(null) } catch (_: Throwable) { return } }
        try { render(canvas) } catch (t: Throwable) { android.util.Log.w("V2xCarMap", "render failed", t) } finally {
            try { surf.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
        }
    }

    private fun render(c: Canvas) {
        val st = state
        val dark = carContext.isDarkMode
        c.drawColor(if (dark) 0xFF1B1D21.toInt() else 0xFFE8E6E1.toInt())
        val now = SystemClock.uptimeMillis()
        val me = interpolatedPos(now) ?: st?.pos
        val center = panCenter ?: me ?: LatLon(48.2082, 16.3738) // Vienna as a neutral default
        val dens = (dpi / 160f).coerceIn(1f, 3f)

        // --- camera
        val speed = st?.speedMps ?: 0.0
        val autoZoom = when {
            st?.navigating != true -> 16.0
            speed < 9 -> 17.2
            speed < 17 -> 16.4
            speed < 25 -> 15.6
            else -> 14.8
        }
        camZoom += ((autoZoom + userZoomDelta).coerceIn(4.0, 19.0) - camZoom) * 0.15
        st?.headingDeg?.let { h -> if (speed > 1.5 || smoothHeading == 0.0) smoothHeading = Geo.normDeg(smoothHeading + Geo.angleDiff(h, smoothHeading) * 0.25) }
        val targetBearing = if (following && st?.navigating == true) smoothHeading else if (following) smoothHeading else camBearing
        camBearing = Geo.normDeg(camBearing + Geo.angleDiff(targetBearing, camBearing) * 0.25)

        val vis = if (visible.isEmpty) Rect(0, 0, width, height) else visible
        val fx = vis.exactCenterX()
        val fy = if (following && st?.navigating == true) vis.top + vis.height() * 0.72f else vis.exactCenterY()

        val z0 = floor(camZoom).toInt().coerceIn(3, 19)
        val s = tileScale() * 2.0.pow(camZoom - z0)       // screen px per base-world px
        val (cwx, cwy) = world(center, z0)

        c.save()
        c.translate(fx, fy)
        c.rotate(-camBearing.toFloat())

        // --- tiles
        val style = if (dark) TileCache.NIGHT else TileCache.DAY
        val radius = hypot(width.toDouble(), height.toDouble()) / s
        val tMin = floor((cwx - radius) / 256).toInt(); val tMax = floor((cwx + radius) / 256).toInt()
        val uMin = floor((cwy - radius) / 256).toInt(); val uMax = floor((cwy + radius) / 256).toInt()
        val maxT = 1 shl z0
        val dst = RectF()
        for (ty in uMin..uMax) {
            if (ty < 0 || ty >= maxT) continue
            for (txRaw in tMin..tMax) {
                val tx = ((txRaw % maxT) + maxT) % maxT
                dst.set(((txRaw * 256 - cwx) * s).toFloat(), ((ty * 256 - cwy) * s).toFloat(),
                        (((txRaw + 1) * 256 - cwx) * s).toFloat() + 0.5f, (((ty + 1) * 256 - cwy) * s).toFloat() + 0.5f)
                val bmp = tiles.get(style, z0, tx, ty)
                if (bmp != null) { c.drawBitmap(bmp, null, dst, paintTile); continue }
                // fall back to a scaled-up parent tile while loading
                for (up in 1..3) {
                    val pz = z0 - up; if (pz < 0) break
                    val parent = tiles.peek(style, pz, tx shr up, ty shr up) ?: continue
                    val sub = parent.width / (1 shl up)
                    val sx = (tx and ((1 shl up) - 1)) * sub; val sy = (ty and ((1 shl up) - 1)) * sub
                    c.drawBitmap(parent, Rect(sx, sy, sx + sub, sy + sub), dst, paintTile)
                    break
                }
            }
        }

        // --- route
        st?.route?.let { r ->
            val path = Path(); val done = Path()
            var started = false; var doneStarted = false
            for (i in r.points.indices) {
                val (wx, wy) = world(r.points[i], z0)
                val x = ((wx - cwx) * s).toFloat(); val y = ((wy - cwy) * s).toFloat()
                if (r.cumDist[i] <= st.distAlong) {
                    if (!doneStarted) { done.moveTo(x, y); doneStarted = true } else done.lineTo(x, y)
                }
                if (r.cumDist[i] >= st.distAlong || (i + 1 < r.points.size && r.cumDist[i + 1] > st.distAlong)) {
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
            }
            paintRouteCasing.strokeWidth = 11f * dens; paintRoute.strokeWidth = 7f * dens; paintRouteDone.strokeWidth = 7f * dens
            c.drawPath(done, paintRouteDone)
            c.drawPath(path, paintRouteCasing)
            c.drawPath(path, paintRoute)
            // destination pin
            val (dx, dy) = world(r.points.last(), z0)
            fill.color = 0xFFD32F2F.toInt()
            c.drawCircle(((dx - cwx) * s).toFloat(), ((dy - cwy) * s).toFloat(), 9f * dens, fill)
        }
        c.restore()

        // Upright overlays: positions transformed manually so icons/text don't rotate with the map.
        val br = Math.toRadians(-camBearing)
        fun toScreen(p: LatLon): PointF {
            val (wx, wy) = world(p, z0)
            val dx = (wx - cwx) * s; val dy = (wy - cwy) * s
            return PointF((fx + dx * cos(br) - dy * sin(br)).toFloat(), (fy + dx * sin(br) + dy * cos(br)).toFloat())
        }
        fun onScreen(p: PointF) = p.x > -50 && p.y > -50 && p.x < width + 50 && p.y < height + 50

        st?.let { drawV2x(c, it, ::toScreen, ::onScreen, dens) }

        // --- own car
        if (me != null) {
            val p = toScreen(me)
            val rot = ((st?.headingDeg ?: smoothHeading) - camBearing).toFloat()
            drawOwnArrow(c, p.x, p.y, rot, dens)
        }

        // --- HUD
        st?.let { drawHud(c, it, dens, dark) }

        // attribution (required by the OSM tile usage policy)
        textPaint.textSize = 9f * dens; textPaint.color = if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        textPaint.textAlign = Paint.Align.RIGHT
        c.drawText("© OpenStreetMap contributors",vis.right - 6f * dens, vis.bottom - 6f * dens, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawOwnArrow(c: Canvas, x: Float, y: Float, rotDeg: Float, d: Float) {
        c.save(); c.translate(x, y); c.rotate(rotDeg)
        fill.color = 0x332F80ED; c.drawCircle(0f, 0f, 22f * d, fill)
        val path = Path().apply {
            moveTo(0f, -15f * d); lineTo(11f * d, 12f * d); lineTo(0f, 6f * d); lineTo(-11f * d, 12f * d); close()
        }
        fill.color = 0xFF2F80ED.toInt(); c.drawPath(path, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
        c.restore()
    }

    private fun drawV2x(c: Canvas, st: RenderState, toScreen: (LatLon) -> PointF, onScreen: (PointF) -> Boolean, d: Float) {
        // vehicles (CAM)
        for (v in st.v2x.vehicles) {
            val p = toScreen(v.pos); if (!onScreen(p)) continue
            c.save(); c.translate(p.x, p.y)
            v.headingDeg?.let { c.rotate((it - camBearing).toFloat()) }
            fill.color = when {
                v.isEmergency -> if ((SystemClock.uptimeMillis() / 400) % 2 == 0L) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
                v.stationType == 6 || v.stationType == 11 -> 0xFF8E24AA.toInt()   // bus / tram
                v.stationType in 7..8 -> 0xFF6D4C41.toInt()                         // trucks
                else -> 0xFF00897B.toInt()
            }
            val r = if (v.isEmergency) 10f * d else 7f * d
            c.drawRoundRect(RectF(-r * 0.7f, -r, r * 0.7f, r), r * 0.35f, r * 0.35f, fill)
            stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f * d
            c.drawRoundRect(RectF(-r * 0.7f, -r, r * 0.7f, r), r * 0.35f, r * 0.35f, stroke)
            c.restore()
        }
        // signals (SPATEM)
        for (sg in st.v2x.signals) {
            val p = toScreen(sg.pos); if (!onScreen(p)) continue
            drawSignalPin(c, p.x, p.y, sg.phase, d)
        }
        // hazards (DENM)
        val aheadKeys = st.ahead?.hazards?.associate { it.hazard.key to it.distM } ?: emptyMap()
        for (h in st.v2x.hazards) {
            val p = toScreen(h.pos); if (!onScreen(p)) continue
            drawHazard(c, p.x, p.y, d, h.key in aheadKeys)
        }
    }

    private fun drawHazard(c: Canvas, x: Float, y: Float, d: Float, onRoute: Boolean) {
        val sz = (if (onRoute) 17f else 13f) * d
        val tri = Path().apply { moveTo(x, y - sz); lineTo(x + sz, y + sz * 0.8f); lineTo(x - sz, y + sz * 0.8f); close() }
        fill.color = if (onRoute) 0xFFFF6D00.toInt() else 0xFFFFA726.toInt(); c.drawPath(tri, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 2f * d; c.drawPath(tri, stroke)
        textPaint.color = Color.BLACK; textPaint.textSize = sz * 1.1f
        c.drawText("!", x, y + sz * 0.6f, textPaint)
    }

    private fun drawSignalPin(c: Canvas, x: Float, y: Float, phase: SignalPhase, d: Float) {
        val w = 9f * d; val h = 22f * d
        fill.color = 0xFF212121.toInt()
        c.drawRoundRect(RectF(x - w, y - h, x + w, y + h), w, w, fill)
        val lamps = listOf(SignalPhase.RED to 0xFFFF1744.toInt(), SignalPhase.YELLOW to 0xFFFFC400.toInt(), SignalPhase.GREEN to 0xFF00E676.toInt())
        lamps.forEachIndexed { i, (ph, col) ->
            fill.color = if (phase == ph) col else (col and 0x00FFFFFF) or 0x33000000
            c.drawCircle(x, y - h + w + i * (2 * h - 2 * w) / 2f, w * 0.62f, fill)
        }
    }

    private fun drawHud(c: Canvas, st: RenderState, d: Float, dark: Boolean) {
        val area = if (visible.isEmpty) Rect(0, 0, width, height) else visible
        val margin = 10f * d
        var top = area.top + margin
        val right = area.right - margin

        // Next traffic light on route (big widget, Google-Maps-style card on the right)
        st.ahead?.signal?.let { sa ->
            val w = 64f * d; val h = 150f * d
            val box = RectF(right - w, top, right, top + h)
            fill.color = 0xEE202124.toInt(); c.drawRoundRect(box, 14f * d, 14f * d, fill)
            drawSignalPin(c, box.centerX(), box.top + 58f * d, sa.signal.phase, d * 2.0f)
            textPaint.color = Color.WHITE; textPaint.textSize = 15f * d
            c.drawText(st.text.distance(sa.distM), box.centerX(), box.bottom - 22f * d, textPaint)
            if (sa.signal.simulated) { textPaint.textSize = 9f * d; c.drawText("SIM", box.centerX(), box.bottom - 8f * d, textPaint) }
            top = box.bottom + margin
        }

        // Hazard banner for the closest hazard on route
        st.ahead?.hazards?.firstOrNull()?.let { ha ->
            textPaint.textSize = 15f * d
            val label = "⚠ ${ha.hazard.label.replaceFirstChar { it.uppercase() }} · ${st.text.distance(ha.distM)}"
            val tw = textPaint.measureText(label) + 24f * d
            val box = RectF(right - tw, top, right, top + 34f * d)
            fill.color = 0xEEFF6D00.toInt(); c.drawRoundRect(box, 10f * d, 10f * d, fill)
            textPaint.color = Color.BLACK
            c.drawText(label, box.centerX(), box.centerY() + 5f * d, textPaint)
            top = box.bottom + margin
        }

        if (st.ahead?.emergencyNearby != null) {
            textPaint.textSize = 15f * d
            val label = if (st.text.german) "🚨 Einsatzfahrzeug" else "🚨 Emergency vehicle"
            val tw = textPaint.measureText(label) + 24f * d
            val box = RectF(right - tw, top, right, top + 34f * d)
            fill.color = 0xEED50000.toInt(); c.drawRoundRect(box, 10f * d, 10f * d, fill)
            textPaint.color = Color.WHITE
            c.drawText(label, box.centerX(), box.centerY() + 5f * d, textPaint)
        }

        // V2X status chip, bottom-left of the visible area
        val live = st.v2x.lastFrameMs > 0 && System.currentTimeMillis() - st.v2x.lastFrameMs < 15_000
        val chip = if (live) "V2X ●  ${st.v2x.vehicles.size}🚗  ${st.v2x.signals.size}🚦  ${st.v2x.hazards.size}⚠"
                   else "V2X ○  " + (st.linkHint ?: "")
        textPaint.textSize = 12f * d
        val cw = textPaint.measureText(chip) + 20f * d
        val chipBox = RectF(area.left + margin, area.bottom - margin - 28f * d, area.left + margin + cw, area.bottom - margin)
        fill.color = if (dark) 0xDD303134.toInt() else 0xEEFFFFFF.toInt()
        c.drawRoundRect(chipBox, 14f * d, 14f * d, fill)
        textPaint.color = if (live) 0xFF00C853.toInt() else if (dark) 0xFFBDBDBD.toInt() else 0xFF616161.toInt()
        c.drawText(chip, chipBox.centerX(), chipBox.centerY() + 4f * d, textPaint)

        st.speedLimitKph?.let { drawSpeedLimit(c, it, st, area.left + margin, chipBox.top - margin, d) }
    }

    /**
     * Round speed-limit sign above the V2X chip ([left], [bottom] = its lower-left corner).
     * While speeding, the sign flashes red and the current speed is shown next to it.
     */
    private fun drawSpeedLimit(c: Canvas, limit: Int, st: RenderState, left: Float, bottom: Float, d: Float) {
        val r = 26f * d
        val cx = left + r; val cy = bottom - r
        val flashOn = st.speeding && (SystemClock.uptimeMillis() / 400) % 2 == 0L
        fill.color = if (flashOn) 0xFFD50000.toInt() else Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        stroke.color = 0xFFD50000.toInt(); stroke.strokeWidth = 0.2f * r
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2, stroke)
        textPaint.color = if (flashOn) Color.WHITE else Color.BLACK
        textPaint.textSize = if (limit >= 100) 0.72f * r else 0.9f * r
        c.drawText(limit.toString(), cx, cy + textPaint.textSize * 0.36f, textPaint)

        if (st.speeding) {
            val kmh = (st.speedMps * 3.6).toInt().toString()
            textPaint.textSize = 18f * d
            val w = textPaint.measureText(kmh) + 16f * d
            val box = RectF(cx + r + 8f * d, cy - 15f * d, cx + r + 8f * d + w, cy + 15f * d)
            fill.color = 0xEED50000.toInt(); c.drawRoundRect(box, 10f * d, 10f * d, fill)
            textPaint.color = Color.WHITE
            c.drawText(kmh, box.centerX(), box.centerY() + 6f * d, textPaint)
        }
    }
}
