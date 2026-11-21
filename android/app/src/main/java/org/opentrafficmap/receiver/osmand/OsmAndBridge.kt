package org.opentrafficmap.receiver.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.content.FileProvider
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.AddMapPointParams
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.maplayer.point.UpdateMapPointParams
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.opentrafficmap.receiver.MainActivity
import org.opentrafficmap.receiver.R
import org.opentrafficmap.receiver.V2xHub
import org.opentrafficmap.receiver.nav.IvimSpeed
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.V2xAnalyzer
import org.opentrafficmap.receiver.nav.SignalPhase
import org.opentrafficmap.receiver.nav.V2xHazard
import org.opentrafficmap.receiver.nav.V2xSignal
import org.opentrafficmap.receiver.nav.V2xVehicle
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * OsmAnd add-on: mirrors the live V2X picture ([V2xHub]) into an installed OsmAnd through its AIDL
 * API — a map layer "V2X2MAP" with vehicles (CAM), traffic lights (SPATEM) and hazards (DENM), plus
 * a status widget. Only changes are sent, about once per second.
 *
 * OsmAnd accepts calls only from apps the user enabled in OsmAnd (Plugins → connected apps; newer
 * versions also show a permission dialog). Until then calls are rejected and [status] is
 * [Status.NOT_ENABLED]; the bridge keeps retrying.
 */
object OsmAndBridge {
    enum class Status { OFF, NO_OSMAND, CONNECTING, NOT_ENABLED, ACTIVE }
    fun interface Listener { fun onStatus(status: Status) }

    private const val TAG = "OsmAndBridge"
    private const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
    const val ACTION_REQUEST_PERMISSIONS = "net.osmand.aidl.REQUEST_PERMISSIONS"
    private val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    private const val LAYER_ID = "v2x2map"
    private const val WIDGET_ID = "v2x2map_status"
    private const val WIDGET_LIGHT = "v2x2map_light"
    private const val WIDGET_SPEED = "v2x2map_speed"
    private val WIDGETS = listOf(WIDGET_ID, WIDGET_LIGHT, WIDGET_SPEED)
    private const val SYNC_MS = 1000L
    private const val RETRY_MS = 10_000L

    @Volatile var status = Status.OFF; private set
    @Volatile var osmandPackage: String? = null; private set
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var app: Context? = null
    @Volatile private var api: IOsmAndAidlInterface? = null
    private var bound = false
    private var layerReady = false
    private val sent = HashMap<String, String>()          // point id -> signature of the last sent state
    private var icons: Map<String, Uri> = emptyMap()
    private val handler: Handler by lazy { Handler(HandlerThread("osmand-bridge").apply { start() }.looper) }

    fun addListener(l: Listener) { listeners += l; l.onStatus(status) }
    fun removeListener(l: Listener) { listeners -= l }

    private fun setStatus(s: Status) { if (status != s) { status = s; listeners.forEach { it.onStatus(s) } } }

    /** First installed OsmAnd variant that offers the AIDL V2 service, or null. */
    fun installedOsmAnd(ctx: Context): String? = PACKAGES.firstOrNull { pkg ->
        ctx.packageManager.queryIntentServices(Intent(ACTION).setPackage(pkg), 0).isNotEmpty()
    }

    /** Whether the installed OsmAnd has the newer permission dialog for connected apps. */
    fun permissionRequestIntent(ctx: Context): Intent? {
        val pkg = osmandPackage ?: installedOsmAnd(ctx) ?: return null
        val i = Intent(ACTION_REQUEST_PERMISSIONS).setPackage(pkg).putExtra("groups", arrayOf("map"))
        return if (ctx.packageManager.queryIntentActivities(i, 0).isNotEmpty()) i else null
    }

    fun start(ctx: Context) = handler.post {
        if (bound) return@post
        val c = ctx.applicationContext
        app = c
        val pkg = installedOsmAnd(c) ?: run { setStatus(Status.NO_OSMAND); return@post }
        osmandPackage = pkg
        setStatus(Status.CONNECTING)
        bound = try { c.bindService(Intent(ACTION).setPackage(pkg), connection, Context.BIND_AUTO_CREATE) }
                catch (e: Exception) { Log.w(TAG, "bind failed", e); false }
        if (!bound) setStatus(Status.NO_OSMAND)
    }

    // ---- own position (for the next-light and speed-limit widgets) ------------------------------

    @Volatile private var pos: LatLon? = null
    @Volatile private var heading: Double? = null
    private var fused: FusedLocationProviderClient? = null
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            pos = LatLon(l.latitude, l.longitude)
            if (l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) heading = l.bearing.toDouble()
        }
    }

    private fun startLocation(ctx: Context) {
        if (fused != null) return
        try {
            val f = LocationServices.getFusedLocationProviderClient(ctx)
            f.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(),
                locationCallback, handler.looper)
            fused = f
        } catch (e: SecurityException) { Log.w(TAG, "no location permission", e) }
    }

    private fun stopLocation() {
        fused?.removeLocationUpdates(locationCallback); fused = null; pos = null; heading = null
    }

    fun stop() = handler.post {
        handler.removeCallbacks(syncTask); handler.removeCallbacks(setupTask)
        stopLocation()
        api?.let { a ->
            WIDGETS.forEach { id -> runCatching { a.removeMapWidget(RemoveMapWidgetParams(id)) } }
            runCatching { a.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
        }
        if (bound) runCatching { app?.unbindService(connection) }
        bound = false; api = null; layerReady = false; sent.clear()
        setStatus(Status.OFF)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            api = IOsmAndAidlInterface.Stub.asInterface(binder)
            handler.post(setupTask)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            api = null; layerReady = false; sent.clear()
            handler.removeCallbacks(syncTask)
            setStatus(Status.CONNECTING)                     // Android reconnects by itself
        }
    }

    /** (Re)creates layer and widget. Rejected calls mean V2X2MAP is not enabled in OsmAnd yet. */
    private val setupTask: Runnable = object : Runnable {
        override fun run() {
            val a = api ?: return
            val ctx = app ?: return
            try {
                if (icons.isEmpty()) icons = OsmAndIcons.prepare(ctx)
                osmandPackage?.let { pkg -> icons.values.forEach { ctx.grantUriPermission(pkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                runCatching { a.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
                val layer = AMapLayer(LAYER_ID, "V2X2MAP", 5.5f, emptyList()).apply {
                    isImagePoints = true
                    setCirclePointZoomBounds(0, 10)
                    setSmallPointZoomBounds(11, 14)
                    setBigPointZoomBounds(15, 22)
                }
                if (!a.addMapLayer(AddMapLayerParams(layer))) {
                    setStatus(Status.NOT_ENABLED)
                    handler.postDelayed(this, RETRY_MS)
                    return
                }
                WIDGETS.forEach { id -> runCatching { a.removeMapWidget(RemoveMapWidgetParams(id)) } }
                lastWidget.clear()
                widgets(ctx).forEach { w -> runCatching { a.addMapWidget(AddMapWidgetParams(w)) }; lastWidget[w.id] = key(w) }
                startLocation(ctx)
                layerReady = true; sent.clear()
                setStatus(Status.ACTIVE)
                handler.removeCallbacks(syncTask); handler.post(syncTask)
            } catch (e: Exception) {
                Log.w(TAG, "setup failed", e)
                handler.postDelayed(this, RETRY_MS)
            }
        }
    }

    private val lastWidget = HashMap<String, String>()
    private fun key(w: AMapWidget) = "${w.text}|${w.description}|${w.lightIconUri}"

    private val syncTask: Runnable = object : Runnable {
        override fun run() {
            val a = api ?: return
            if (!layerReady) return
            try {
                sync(a)
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
                layerReady = false
                handler.postDelayed(setupTask, RETRY_MS)
                return
            }
            handler.postDelayed(this, SYNC_MS)
        }
    }

    private fun sync(a: IOsmAndAidlInterface) {
        val snap = V2xHub.snapshot()
        val wanted = LinkedHashMap<String, AMapPoint>()
        snap.vehicles.forEach { v -> point(v).let { wanted[it.id] = it } }
        snap.signals.forEach { s -> point(s).let { wanted[it.id] = it } }
        snap.hazards.forEach { h -> point(h).let { wanted[it.id] = it } }

        for ((id, p) in wanted) {
            val sig = signature(p)
            when (sent[id]) {
                null -> if (a.addMapPoint(AddMapPointParams(LAYER_ID, p))) sent[id] = sig
                        else { setStatus(Status.NOT_ENABLED); layerReady = false; handler.postDelayed(setupTask, RETRY_MS); return }
                sig -> {}
                else -> if (a.updateMapPoint(UpdateMapPointParams(LAYER_ID, p, true))) sent[id] = sig
            }
        }
        sent.keys.filter { it !in wanted }.forEach { id ->
            runCatching { a.removeMapPoint(RemoveMapPointParams(LAYER_ID, id)) }
            sent.remove(id)
        }

        val ctx = app ?: return
        for (w in widgets(ctx, snap)) {
            val k = key(w)
            if (lastWidget[w.id] != k) { runCatching { a.updateMapWidget(UpdateMapWidgetParams(w)) }; lastWidget[w.id] = k }
        }
    }

    /**
     * Three widgets: V2X status (vehicles/lights/hazards), next traffic light in the driving
     * direction (distance, icon in its colour) and the IVIM road-sign speed limit.
     */
    private fun widgets(ctx: Context, snap: org.opentrafficmap.receiver.nav.V2xSnapshot = V2xHub.snapshot()): List<AMapWidget> {
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(ctx, MainActivity::class.java)
        val live = snap.lastFrameMs > 0 && System.currentTimeMillis() - snap.lastFrameMs < 15_000
        val any = snap.vehicles.isNotEmpty() || snap.signals.isNotEmpty() || snap.hazards.isNotEmpty()
        val status = if (live || any) "${snap.vehicles.size}/${snap.signals.size}/${snap.hazards.size}" else "—"

        val p = pos
        val ahead = p?.let { V2xAnalyzer.analyze(snap, it, heading, null).signal }
        val (lightText, lightDesc, lightIcon) = when {
            p == null -> Triple("—", ctx.getString(R.string.osmand_w_no_position), "light_unknown")
            ahead == null -> Triple("—", ctx.getString(R.string.osmand_w_light), "light_unknown")
            else -> Triple("%d m".format(Locale.ROOT, ahead.distM.toInt()), when (ahead.signal.phase) {
                SignalPhase.RED -> ctx.getString(R.string.osmand_w_red); SignalPhase.YELLOW -> ctx.getString(R.string.osmand_w_amber)
                SignalPhase.GREEN -> ctx.getString(R.string.osmand_w_green); SignalPhase.UNKNOWN -> ctx.getString(R.string.osmand_w_light)
            }, when (ahead.signal.phase) {
                SignalPhase.RED -> "light_red"; SignalPhase.YELLOW -> "light_yellow"
                SignalPhase.GREEN -> "light_green"; SignalPhase.UNKNOWN -> "light_unknown"
            })
        }
        val limit = p?.let { IvimSpeed.limitAt(V2xHub.speedZones(), it, heading) }
        val speedText = limit?.let { it.limitKph.toString() + if (it.lanesDiffer) "*" else "" } ?: "—"

        fun w(id: String, title: String, text: String, desc: String, icon: String, order: Int) =
            AMapWidget(id, "ic_action_signal", title, "ic_action_signal", "ic_action_signal", text, desc, order, open).apply {
                icons[icon]?.toString()?.let { setIconUris(it, it, it) }
            }
        return listOf(
            w(WIDGET_ID, ctx.getString(R.string.osmand_w_status_title), status, "V2X", "v2x", 0),
            w(WIDGET_LIGHT, ctx.getString(R.string.osmand_w_light_title), lightText, lightDesc, lightIcon, 1),
            w(WIDGET_SPEED, ctx.getString(R.string.osmand_w_speed_title), speedText, "km/h V2X", "speed", 2))
    }

    // ---- points ---------------------------------------------------------------------------------

    private fun point(v: V2xVehicle): AMapPoint {
        val kind = when {
            v.isEmergency -> "emergency"
            v.stationType == 6 || v.stationType == 11 -> "bus"
            v.stationType in 7..9 -> "truck"
            v.stationType == 15 -> "rsu"
            else -> "car"
        }
        val details = listOfNotNull(
            v.speedMps?.let { "%.0f km/h".format(Locale.ROOT, it * 3.6) },
            v.headingDeg?.let { "%.0f°".format(Locale.ROOT, it) },
            "station %d (type %s)".format(Locale.ROOT, v.id, v.stationType ?: "?"))
        val params = HashMap<String, String>()
        icons[kind]?.let { params[AMapPoint.POINT_IMAGE_URI_PARAM] = it.toString() }
        v.speedMps?.let { params[AMapPoint.POINT_SPEED_PARAM] = "%.2f".format(Locale.ROOT, it) }
        v.headingDeg?.let { params[AMapPoint.POINT_BEARING_PARAM] = "%.1f".format(Locale.ROOT, it) }
        val name = when (kind) { "emergency" -> "Emergency vehicle"; "bus" -> "Bus/tram"; "truck" -> "Truck"; "rsu" -> "Roadside unit"; else -> "Vehicle" }
        return AMapPoint("veh_${v.id}", kind.first().uppercase(), "$name (V2X)", "V2X vehicle (CAM)", LAYER_ID,
            VEHICLE_COLORS.getValue(kind), ALatLon(v.pos.lat, v.pos.lon), details, params)
    }

    private fun point(s: V2xSignal): AMapPoint {
        val (kind, label, color) = when (s.phase) {
            SignalPhase.RED -> Triple("light_red", "red", 0xFFE53935.toInt())
            SignalPhase.YELLOW -> Triple("light_yellow", "amber", 0xFFFFB300.toInt())
            SignalPhase.GREEN -> Triple("light_green", "green", 0xFF43A047.toInt())
            SignalPhase.UNKNOWN -> Triple("light_unknown", "unknown", 0xFF757575.toInt())
        }
        val params = HashMap<String, String>()
        icons[kind]?.let { params[AMapPoint.POINT_IMAGE_URI_PARAM] = it.toString() }
        return AMapPoint("sig_${s.id}", "L", "Traffic light: $label (V2X)", "V2X traffic light (SPATEM)", LAYER_ID,
            color, ALatLon(s.pos.lat, s.pos.lon), listOf("intersection/station ${s.id}", "first signal group only"), params)
    }

    private fun point(h: V2xHazard): AMapPoint {
        val params = HashMap<String, String>()
        icons["hazard"]?.let { params[AMapPoint.POINT_IMAGE_URI_PARAM] = it.toString() }
        val details = listOfNotNull(h.causeCode?.let { "cause $it${h.subCauseCode?.let { s -> "/$s" } ?: ""}" })
        return AMapPoint("haz_${h.key}", "!", "${h.label.replaceFirstChar { it.uppercase() }} (V2X)", "V2X hazard (DENM)",
            LAYER_ID, 0xFFFF6D00.toInt(), ALatLon(h.pos.lat, h.pos.lon), details, params)
    }

    /** What makes a point "changed": position (~1 m), name, icon and details. */
    private fun signature(p: AMapPoint): String =
        "%.5f,%.5f|%s|%s|%s".format(Locale.ROOT, p.location.latitude, p.location.longitude, p.fullName,
            p.params[AMapPoint.POINT_IMAGE_URI_PARAM], p.details.joinToString(";"))

    private val VEHICLE_COLORS = mapOf("car" to 0xFF00897B.toInt(), "bus" to 0xFF8E24AA.toInt(),
        "truck" to 0xFF6D4C41.toInt(), "emergency" to 0xFFE53935.toInt(), "rsu" to 0xFF546E7A.toInt())
}

/** Point images, drawn once into the app's files and offered to OsmAnd through a FileProvider. */
internal object OsmAndIcons {
    private const val SIZE = 72

    fun prepare(ctx: Context): Map<String, Uri> {
        val dir = File(ctx.filesDir, "osmand_icons").apply { mkdirs() }
        val authority = ctx.packageName + ".osmandicons"
        val draw = mapOf<String, (Canvas) -> Unit>(
            "car" to { c -> vehicle(c, 0xFF00897B.toInt()) }, "bus" to { c -> vehicle(c, 0xFF8E24AA.toInt()) },
            "truck" to { c -> vehicle(c, 0xFF6D4C41.toInt()) }, "emergency" to { c -> vehicle(c, 0xFFE53935.toInt()) },
            "rsu" to { c -> rsu(c) },
            "light_red" to { c -> light(c, 0) }, "light_yellow" to { c -> light(c, 1) },
            "light_green" to { c -> light(c, 2) }, "light_unknown" to { c -> light(c, -1) },
            "hazard" to { c -> hazard(c) },
            "v2x" to { c -> v2x(c) }, "speed" to { c -> speedSign(c) })
        return draw.mapValues { (name, fn) ->
            val f = File(dir, "$name.png")
            if (!f.exists()) {
                val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                fn(Canvas(bmp))
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            FileProvider.getUriForFile(ctx, authority, f)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE }

    private fun vehicle(c: Canvas, color: Int) {
        val r = RectF(SIZE * 0.25f, SIZE * 0.1f, SIZE * 0.75f, SIZE * 0.9f)
        fill.color = color; c.drawRoundRect(r, 14f, 14f, fill); c.drawRoundRect(r, 14f, 14f, stroke)
        fill.color = 0xCCFFFFFF.toInt(); c.drawRoundRect(RectF(SIZE * 0.33f, SIZE * 0.22f, SIZE * 0.67f, SIZE * 0.38f), 5f, 5f, fill)
    }

    private fun rsu(c: Canvas) {
        fill.color = 0xFF546E7A.toInt(); c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.32f, fill); c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.32f, stroke)
        fill.color = Color.WHITE; c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.1f, fill)
    }

    private fun light(c: Canvas, lit: Int) {
        val box = RectF(SIZE * 0.3f, SIZE * 0.04f, SIZE * 0.7f, SIZE * 0.96f)
        fill.color = 0xFF212121.toInt(); c.drawRoundRect(box, 12f, 12f, fill); c.drawRoundRect(box, 12f, 12f, stroke)
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFFFFB300.toInt(), 0xFF43A047.toInt())
        for (i in 0..2) {
            fill.color = if (i == lit) colors[i] else 0xFF424242.toInt()
            c.drawCircle(SIZE / 2f, SIZE * (0.2f + 0.3f * i), SIZE * 0.11f, fill)
        }
    }

    /** Radio waves around a dot: the V2X status widget. */
    private fun v2x(c: Canvas) {
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF00897B.toInt(); strokeCap = Paint.Cap.ROUND }
        fill.color = 0xFF00897B.toInt(); c.drawCircle(SIZE / 2f, SIZE * 0.62f, SIZE * 0.09f, fill)
        for (r in listOf(0.22f, 0.36f)) {
            val o = RectF(SIZE / 2f - SIZE * r, SIZE * 0.62f - SIZE * r, SIZE / 2f + SIZE * r, SIZE * 0.62f + SIZE * r)
            c.drawArc(o, 220f, 100f, false, arc)
        }
    }

    /** Round speed-limit sign without a number (the number is the widget text). */
    private fun speedSign(c: Canvas) {
        fill.color = Color.WHITE; c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.44f, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = SIZE * 0.12f; color = 0xFFD50000.toInt() }
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE * 0.38f, ring)
    }

    private fun hazard(c: Canvas) {
        val p = Path().apply { moveTo(SIZE / 2f, SIZE * 0.08f); lineTo(SIZE * 0.94f, SIZE * 0.88f); lineTo(SIZE * 0.06f, SIZE * 0.88f); close() }
        fill.color = 0xFFFF6D00.toInt(); c.drawPath(p, fill); c.drawPath(p, stroke)
        fill.color = Color.BLACK
        c.drawRect(SIZE * 0.46f, SIZE * 0.35f, SIZE * 0.54f, SIZE * 0.65f, fill)
        c.drawCircle(SIZE / 2f, SIZE * 0.75f, SIZE * 0.05f, fill)
    }
}
