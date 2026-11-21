package org.opentrafficmap.receiver.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.opentrafficmap.receiver.V2xHub
import org.opentrafficmap.receiver.nav.*
import java.util.Locale
import java.util.concurrent.Executors

/**
 * State machine behind the car screen: location → route progress → V2X analysis →
 * voice prompts → Trip updates. All public methods run on the main thread.
 */
class NavController(
    private val ctx: CarContext,
    val renderer: MapRenderer,
    private val onChanged: () -> Unit,
    private val onV2xAlert: (HazardAhead) -> Unit,
) {
    val text = NavText(german = Locale.getDefault().language == "de")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val routing = RoutingClient(
        valhallaBase = CarPrefs.valhallaUrl(ctx),
        language = if (text.german) "de-DE" else "en-US",
    )
    private val guidance = GuidanceScheduler(text)
    private val navManager: NavigationManager = ctx.getCarService(NavigationManager::class.java)

    // ---- public state (read by NavScreen) --------------------------------------
    var pos: LatLon? = null; private set
    var heading: Double? = null; private set
    var speedMps = 0.0; private set
    var accuracy = 20.0; private set
    var destination: Place? = null; private set
    var route: Route? = null; private set
    var engine: NavigationEngine? = null; private set
    var navState: NavState? = null; private set
    var loading = false; private set
    var error: String? = null; private set
    var ahead: V2xAhead? = null; private set
    var muted = CarPrefs.muted(ctx); private set
    var simulating = false; private set
    val navigating get() = route != null
    /** Posted limit at the current position (OSM via Valhalla), null if unknown. */
    var speedLimitKph: Int? = null; private set
    var speedLimitFromV2x = false; private set
    var speeding = false; private set
    private var osmLimit: Int? = null
    private var speedLimits: SpeedLimits? = null
    private val speedWarning = SpeedWarning()
    private val avoid = mutableListOf<LatLon>()
    private val alerted = HashSet<String>()
    private var lastReroute = 0L
    /** Destination shared from the phone, waiting for the first GPS fix. */
    private var sharedDest: Place? = PendingDestination.take(ctx)
    private val shareListener: (Place) -> Unit = { p -> acceptShared(p) }

    init {
        PendingDestination.listener = shareListener
        V2xHub.text = text
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { main.post { stopNavigation() } }
            override fun onAutoDriveEnabled() { main.post { startSimulation() } }
        })
    }

    // ---- lifecycle -----------------------------------------------------------------

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(ctx) }
    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            if (simulating) return
            val l = r.lastLocation ?: return
            onFix(LatLon(l.latitude, l.longitude),
                if (l.hasBearing() && l.speed > 1.0f) l.bearing.toDouble() else heading,
                if (l.hasSpeed()) l.speed.toDouble() else 0.0,
                if (l.hasAccuracy()) l.accuracy.toDouble() else 20.0)
        }
    }
    private val v2xListener = V2xHub.Listener { main.post(v2xRefresh) }
    private val v2xRefresh = Runnable { refreshV2x(); pushRenderState() }

    fun hasLocationPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun start() {
        V2xHub.addListener(v2xListener)
        main.post(periodic)
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locCb, Looper.getMainLooper()) } catch (_: SecurityException) {}
        try { fused.lastLocation.addOnSuccessListener { l -> if (l != null && pos == null) onFix(LatLon(l.latitude, l.longitude), null, 0.0, l.accuracy.toDouble()) } } catch (_: SecurityException) {}
    }

    fun stop() {
        V2xHub.removeListener(v2xListener)
        main.removeCallbacks(periodic)
        try { fused.removeLocationUpdates(locCb) } catch (_: Exception) {}
    }

    fun destroy() {
        stop(); stopNavigation(); tts?.shutdown(); io.shutdownNow(); renderer.release()
        navManager.clearNavigationManagerCallback()
        if (PendingDestination.listener === shareListener) PendingDestination.listener = null
    }

    /** 1 Hz housekeeping: expire V2X objects, keep HUD fresh while standing still. */
    private val periodic = object : Runnable {
        override fun run() { refreshV2x(); pushRenderState(); main.postDelayed(this, 1000) }
    }

    // ---- navigation ------------------------------------------------------------------

    fun navigateTo(place: Place) {
        val from = pos ?: run { toast(if (text.german) "Warte auf GPS…" else "Waiting for GPS…"); return }
        destination = place; avoid.clear(); alerted.clear()
        CarPrefs.addRecent(ctx, place)
        requestRoute(from, place.pos, heading, isReroute = false)
    }

    private fun requestRoute(from: LatLon, to: LatLon, hdg: Double?, isReroute: Boolean) {
        loading = true; error = null; onChanged()
        lastReroute = System.currentTimeMillis()
        val avoidCopy = avoid.toList()
        io.execute {
            val result = runCatching { routing.route(from, to, hdg, avoidCopy) }
            main.post {
                loading = false
                result.onSuccess { r ->
                    val wasNavigating = route != null
                    route = r; engine = NavigationEngine(r); guidance.reset()
                    fetchSpeedLimits(r)
                    if (!wasNavigating) beginNavigationSession()
                    if (isReroute) say(text.rerouting)
                    pos?.let { onFix(it, heading, speedMps, accuracy) }
                }.onFailure { e ->
                    error = e.message ?: e.javaClass.simpleName
                    toast((if (text.german) "Routing fehlgeschlagen: " else "Routing failed: ") + error)
                }
                onChanged()
            }
        }
    }

    /** Loads speed limits for [r] in the background; the sign stays hidden if this fails. */
    private fun fetchSpeedLimits(r: Route) {
        speedLimits = null; speedLimitKph = null
        io.execute {
            val sl = runCatching { routing.speedLimits(r) }.getOrNull()
            main.post { if (route === r) speedLimits = sl }
        }
    }

    private fun beginNavigationSession() {
        try { navManager.navigationStarted() } catch (_: Exception) {}
        NavForegroundService.start(ctx)
    }

    fun stopNavigation() {
        val was = route != null
        route = null; engine = null; navState = null; destination = null; loading = false
        speedLimits = null; osmLimit = null; speedLimitKph = null; speeding = false; speedWarning.reset()
        stopSimulation()
        if (was) {
            try { navManager.navigationEnded() } catch (_: Exception) {}
            NavForegroundService.stop(ctx)
        }
        pushRenderState(); onChanged()
    }

    /** Re-route around a hazard announced via DENM (Valhalla exclude_locations). */
    fun avoidHazard(h: V2xHazard) {
        val dest = destination ?: return
        val from = pos ?: return
        avoid += h.pos
        requestRoute(from, dest.pos, heading, isReroute = true)
    }

    fun toggleMute() { muted = !muted; CarPrefs.setMuted(ctx, muted); if (muted) tts?.stop(); onChanged() }

    // ---- per-fix pipeline --------------------------------------------------------------

    private fun onFix(p: LatLon, hdg: Double?, speed: Double, acc: Double) {
        pos = p; heading = hdg ?: heading; speedMps = speed; accuracy = acc
        renderer.onNewFix(p)
        sharedDest?.let { d -> sharedDest = null; startShared(d) }
        val eng = engine
        val r = route
        if (eng != null && r != null) {
            val st = eng.update(p, acc, heading, speed)
            navState = st
            osmLimit = if (st.offRoute) null else speedLimits?.at(st.distAlong)
            if (st.arrived) {
                say(text.arrived); toast(text.arrived)
                stopNavigation(); return
            }
            if (st.offRoute && !loading && System.currentTimeMillis() - lastReroute > 10_000) {
                destination?.let { requestRoute(p, it.pos, heading, isReroute = true) }
            }
            guidance.onProgress(r, st, speed)?.let { say(it) }
            updateTrip(r, st)
        }
        updateSpeedLimit(speed)
        refreshV2x()
        pushRenderState()
        onChanged()
    }

    /**
     * Road-sign limit from IVIM (also without a route) takes precedence over OpenStreetMap
     * (route only). If the lanes carry different limits the lowest is shown, without warning.
     */
    private fun updateSpeedLimit(speed: Double) {
        val p = pos
        val ivim = if (p == null) null else IvimSpeed.limitAt(V2xHub.speedZones(), p, heading)
        speedLimitKph = ivim?.limitKph ?: if (navigating) osmLimit else null
        speedLimitFromV2x = ivim != null
        speeding = speedWarning.update(speed * 3.6, if (ivim?.lanesDiffer == true) null else speedLimitKph)
    }

    private fun refreshV2x() {
        val p = pos ?: return
        val snap = V2xHub.snapshot()
        val a = V2xAnalyzer.analyze(snap, p, heading, engine)
        ahead = a
        if (navigating || speedMps > 3) {
            guidance.onHazards(a.hazards)?.let { say(it) }
            guidance.onEmergency(a.emergencyNearby, System.currentTimeMillis())?.let { say(it) }
        }
        a.hazards.firstOrNull { it.hazard.key !in alerted }?.let { alerted += it.hazard.key; onV2xAlert(it) }
    }

    private fun pushRenderState() {
        renderer.state = RenderState(
            pos = pos, headingDeg = heading, speedMps = speedMps,
            route = route, distAlong = navState?.distAlong ?: 0.0,
            v2x = V2xHub.snapshot(), ahead = ahead, navigating = navigating, text = text,
            speedLimitKph = speedLimitKph, speeding = speeding, speedLimitV2x = speedLimitFromV2x, linkHint = linkHint(),
        )
        renderer.requestFrame()
    }

    private fun linkHint(): String {
        val de = text.german
        return when (org.opentrafficmap.receiver.BtSession.state) {
            org.opentrafficmap.receiver.BluetoothController.State.SCANNING -> if (de) "ESP32 wird gesucht…" else "Searching ESP32…"
            org.opentrafficmap.receiver.BluetoothController.State.CONNECTING -> if (de) "Verbinde ESP32…" else "Connecting ESP32…"
            org.opentrafficmap.receiver.BluetoothController.State.CONNECTED -> if (de) "ESP32 verbunden, warte auf Daten" else "ESP32 connected, waiting for data"
            org.opentrafficmap.receiver.BluetoothController.State.ERROR -> if (de) "ESP32-Fehler – V2X verbinden antippen" else "ESP32 error – tap Connect V2X"
            org.opentrafficmap.receiver.BluetoothController.State.IDLE -> if (de) "„V2X verbinden“ antippen" else "Tap Connect V2X"
        }
    }

    private fun updateTrip(r: Route, st: NavState) {
        try { navManager.updateTrip(TripBuilder.trip(ctx, this, r, st)) } catch (_: Exception) {}
    }

    // ---- voice ----------------------------------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26)
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build() else null

    private val pendingSpeech = mutableListOf<String>()

    private fun say(s: String) {
        if (muted) return
        if (ttsReady) { speakNow(s); return }
        pendingSpeech += s
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) { pendingSpeech.clear(); return@post }
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { abandonFocus() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { abandonFocus() }
                })
                pendingSpeech.forEach { speakNow(it) }; pendingSpeech.clear()
            }
        }
    }

    private fun speakNow(s: String) {
        if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.requestAudioFocus(focus)
        tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "nav${System.nanoTime()}")
    }

    private fun abandonFocus() { if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.abandonAudioFocusRequest(focus) }

    fun toast(s: String) = CarToast.makeText(ctx, s, CarToast.LENGTH_LONG).show()

    // ---- demo drive (DHU "auto drive" or the Demo button in debug builds) ---------------

    val isDebugBuild get() = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var simDist = 0.0
    private val simStep = object : Runnable {
        override fun run() {
            val r = route ?: run { stopSimulation(); return }
            val st = navState
            val nearTurn = st != null && st.distToNextM < 60
            val v = if (nearTurn) 7.0 else 14.0
            simDist += v * 0.5
            val p = r.pointAt(simDist)
            onFix(p, r.bearingAt(simDist), v, 5.0)
            // cycle the simulated traffic light: 8 s green, 3 s yellow, 8 s red
            simSignal?.let { s ->
                val t = (System.currentTimeMillis() / 1000) % 19
                val ph = when { t < 8 -> SignalPhase.GREEN; t < 11 -> SignalPhase.YELLOW; else -> SignalPhase.RED }
                V2xHub.putSimulatedSignal(s.copy(phase = ph, lastSeenMs = System.currentTimeMillis()))
            }
            if (simulating) main.postDelayed(this, 500)
        }
    }
    private var simSignal: V2xSignal? = null

    fun startSimulation() {
        val r = route ?: run { toast(if (text.german) "Erst ein Ziel wählen" else "Pick a destination first"); return }
        if (simulating) { stopSimulation(); return }
        simulating = true
        simDist = navState?.distAlong ?: 0.0
        // Place demo V2X objects on the route so the whole pipeline can be tested at a desk.
        val now = System.currentTimeMillis()
        val sigAt = (simDist + 400).coerceAtMost(r.shapeLengthM * 0.5)
        simSignal = V2xSignal(-1L, r.pointAt(sigAt), SignalPhase.GREEN, now, simulated = true)
        V2xHub.putSimulatedSignal(simSignal!!)
        val hzAt = (simDist + 1500).coerceAtMost(r.shapeLengthM * 0.8)
        V2xHub.putSimulatedHazard(V2xHazard("sim:1", r.pointAt(hzAt), 3, 0,
            text.cause(3) + " (SIM)", now, now + 15 * 60_000, simulated = true))
        toast(if (text.german) "Demo-Fahrt gestartet (simulierte V2X-Daten)" else "Demo drive started (simulated V2X data)")
        main.post(simStep); onChanged()
    }

    private fun stopSimulation() {
        if (!simulating && simSignal == null) return
        simulating = false; simSignal = null
        main.removeCallbacks(simStep)
        V2xHub.clearSimulated()
    }

    // ---- intents (voice "navigate to …" / geo: links) ------------------------------------

    fun handleIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        // geo:lat,lon  or  geo:0,0?q=address. geo: URIs are opaque, so Uri.getQueryParameter()
        // throws — parse the string instead.
        val uri = intent.dataString ?: return
        when (val r = ShareParser.parseGeo(uri, null)) {
            is ShareParser.Result.Coords ->
                navigateWhenReady(Place(r.name ?: "%.5f, %.5f".format(Locale.ROOT, r.pos.lat, r.pos.lon), "", r.pos))
            is ShareParser.Result.Query -> {
                val q = r.candidates.first()
                search(q) { results -> results.firstOrNull()?.let { navigateWhenReady(it) } ?: toast("“$q”?") }
            }
            else -> {}
        }
    }

    /** Starts guidance now, or after the first GPS fix if there is none yet (e.g. right after launch). */
    private fun navigateWhenReady(p: Place) { if (pos != null) navigateTo(p) else sharedDest = p }

    // ---- destination shared on the phone ("Navigate with V2X2MAP") ------------------------

    private fun acceptShared(p: Place) {
        PendingDestination.clear(ctx)
        if (pos != null) startShared(p) else sharedDest = p   // otherwise wait for the first fix
    }

    private fun startShared(p: Place) {
        if (simulating) stopSimulation()
        toast((if (text.german) "Navigation zu " else "Navigating to ") + p.name)
        navigateTo(p)
    }

    fun search(q: String, cb: (List<Place>) -> Unit) {
        val near = pos
        io.execute {
            val res = runCatching { routing.search(q, near) }.getOrElse { emptyList() }
            main.post { cb(res) }
        }
    }
}
