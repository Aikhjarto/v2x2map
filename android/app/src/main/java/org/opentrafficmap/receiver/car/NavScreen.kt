package org.opentrafficmap.receiver.car

import android.Manifest
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.opentrafficmap.receiver.nav.*
// Explicit: androidx.car.app.model.* also has a `Place`, which would make the star imports ambiguous.
import org.opentrafficmap.receiver.nav.Place

/** The main map screen: idle map + V2X, or turn-by-turn navigation. */
class NavScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = MapRenderer(carContext)
    val nav = NavController(carContext, renderer, onChanged = { invalidateThrottled() }, onV2xAlert = ::showHazardAlert)
    private var lastInvalidate = 0L
    private var inPanMode = false

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        renderer.onFollowChanged = { main.post { invalidate() } }   // called from the render thread
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { nav.start() }
            override fun onStop(owner: LifecycleOwner) { if (!nav.navigating) nav.stop() }
            override fun onDestroy(owner: LifecycleOwner) { nav.destroy() }
        })
    }

    /** NavigationTemplate refreshes are cheap but the host still rate-limits; ~1/s is plenty. */
    private fun invalidateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidate > 900) { lastInvalidate = now; invalidate() }
    }

    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(actionStrip())
            .setMapActionStrip(mapActionStrip())
            .setPanModeListener { pan -> inPanMode = pan }

        val r = nav.route; val st = nav.navState
        when {
            !nav.hasLocationPermission() -> b.setNavigationInfo(
                MessageInfo.Builder(if (nav.text.german) "Standortfreigabe nötig" else "Location permission needed")
                    .setText(if (nav.text.german) "Bitte auf dem Telefon erlauben (Suche antippen)" else "Tap Search to grant it on your phone").build())
            nav.loading -> b.setNavigationInfo(androidx.car.app.navigation.model.RoutingInfo.Builder().setLoading(true).build())
            r != null && st != null -> {
                b.setNavigationInfo(TripBuilder.routingInfo(r, st))
                b.setDestinationTravelEstimate(TripBuilder.destinationEstimate(st))
            }
            else -> b.setNavigationInfo(idleInfo())
        }
        return b.build()
    }

    /** Idle card: summary of the live V2X picture. */
    private fun idleInfo(): MessageInfo {
        val snap = org.opentrafficmap.receiver.V2xHub.snapshot()
        val live = snap.lastFrameMs > 0 && System.currentTimeMillis() - snap.lastFrameMs < 15_000
        val de = nav.text.german
        val title = if (live) "V2X live · ${snap.framesLastMinute}/min" else if (de) "Kein V2X-Empfang" else "No V2X data"
        val body = buildString {
            if (live) {
                append(if (de) "${snap.vehicles.size} Fahrzeuge · ${snap.signals.size} Ampeln · ${snap.hazards.size} Warnungen"
                       else "${snap.vehicles.size} vehicles · ${snap.signals.size} signals · ${snap.hazards.size} hazards")
                nav.ahead?.signal?.let { append("\n").append(signalText(it)) }
            } else append(if (de) "ESP32-C5 in der V2X2MAP-App am Telefon verbinden (Bluetooth)"
                          else "Connect the ESP32-C5 in the V2X2MAP phone app (Bluetooth)")
        }
        val img = nav.ahead?.signal?.let { CarIcons.signalIcon(it.signal.phase) }
        return MessageInfo.Builder(title).setText(body).apply { img?.let { setImage(it) } }.build()
    }

    private fun signalText(s: SignalAhead): String = when (s.signal.phase) {
        SignalPhase.RED -> nav.text.signalRed; SignalPhase.YELLOW -> nav.text.signalYellow
        SignalPhase.GREEN -> nav.text.signalGreen; SignalPhase.UNKNOWN -> nav.text.signalUnknown
    } + " · " + nav.text.distance(s.distM)

    private fun actionStrip(): ActionStrip {
        val de = nav.text.german
        val s = ActionStrip.Builder()
        if (nav.navigating) {
            s.addAction(Action.Builder().setTitle(if (de) "Beenden" else "Exit")
                .setOnClickListener { nav.stopNavigation() }.build())
            nav.ahead?.hazards?.firstOrNull()?.let { h ->
                s.addAction(Action.Builder().setIcon(CarIcons.warning(carContext))
                    .setTitle(if (de) "Umfahren" else "Avoid")
                    .setOnClickListener { nav.avoidHazard(h.hazard) }.build())
            }
            if (nav.isDebugBuild || nav.simulating) s.addAction(Action.Builder().setTitle(if (nav.simulating) "Sim ■" else "Demo")
                .setOnClickListener { nav.startSimulation() }.build())
        } else {
            s.addAction(Action.Builder().setIcon(CarIcons.search(carContext))
                .setTitle(if (de) "Ziel" else "Search")
                .setOnClickListener { openSearch() }.build())
        }
        s.addAction(Action.Builder().setIcon(if (nav.muted) CarIcons.volumeOff(carContext) else CarIcons.volumeOn(carContext))
            .setOnClickListener { nav.toggleMute() }.build())
        return s.build()
    }

    private fun mapActionStrip(): ActionStrip {
        val s = ActionStrip.Builder()
        if (!renderer.following) s.addAction(Action.Builder().setIcon(CarIcons.recenter(carContext))
            .setOnClickListener { renderer.recenter(); invalidate() }.build())
        s.addAction(Action.Builder().setIcon(CarIcons.zoomIn(carContext)).setOnClickListener { renderer.zoomBy(1.0) }.build())
        s.addAction(Action.Builder().setIcon(CarIcons.zoomOut(carContext)).setOnClickListener { renderer.zoomBy(-1.0) }.build())
        s.addAction(Action.PAN)
        return s.build()
    }

    private fun openSearch() {
        if (!nav.hasLocationPermission()) {
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                if (granted.isNotEmpty()) { nav.stop(); nav.start(); invalidate() }
            }
            return
        }
        screenManager.pushForResult(SearchScreen(carContext, nav)) { result ->
            (result as? Place)?.let { nav.navigateTo(it) }
        }
    }

    private fun showHazardAlert(h: HazardAhead) {
        val de = nav.text.german
        val title = "${h.hazard.label.replaceFirstChar { it.uppercase() }} · ${nav.text.distance(h.distM)}"
        if (carContext.carAppApiLevel >= 5) {
            val ab = Alert.Builder(h.hazard.key.hashCode(), CarText.create(title), 10_000)
                .setSubtitle(CarText.create(if (de) "V2X-Warnung (DENM)" else "V2X warning (DENM)"))
                .setIcon(CarIcons.hazardIcon())
            if (nav.navigating) ab.addAction(Action.Builder().setTitle(if (de) "Umfahren" else "Avoid")
                .setOnClickListener { nav.avoidHazard(h.hazard) }.build())
            ab.addAction(Action.Builder().setTitle("OK").setOnClickListener { }.build())
            try { carContext.getCarService(AppManager::class.java).showAlert(ab.build()); return } catch (_: Exception) {}
        }
        nav.toast("⚠ $title")
    }
}
