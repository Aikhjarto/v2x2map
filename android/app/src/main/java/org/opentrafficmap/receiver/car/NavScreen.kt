package org.opentrafficmap.receiver.car

import android.Manifest
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.opentrafficmap.receiver.BluetoothController
import org.opentrafficmap.receiver.BtSession
import org.opentrafficmap.receiver.nav.*
// Explicit: androidx.car.app.model.* also has a `Place`, which would make the star imports ambiguous.
import org.opentrafficmap.receiver.nav.Place

/** The main map screen: idle map + V2X, or turn-by-turn navigation. */
class NavScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = MapRenderer(carContext)
    val nav = NavController(carContext, renderer, onChanged = { invalidateThrottled() }, onV2xAlert = ::showHazardAlert)
    private var lastInvalidate = 0L
    private var inPanMode = false

    private val btListener = BtSession.StateListener { _, _ ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) invalidate()
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        renderer.onFollowChanged = { main.post { invalidate() } }   // called from the render thread
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { nav.start() }
            override fun onStop(owner: LifecycleOwner) { if (!nav.navigating) nav.stop() }
            override fun onDestroy(owner: LifecycleOwner) {
                BtSession.removeStateListener(btListener)
                BtSession.release(BtSession.HOLDER_CAR)   // keeps running if the phone app still uses it
                nav.destroy()
            }
        })
        BtSession.addStateListener(btListener)
        // Auto-connect to the ESP32 when the car screen opens (permission prompts only via the V2X button).
        if (BtSession.hasPermissions(carContext)) BtSession.acquire(carContext, BtSession.HOLDER_CAR)
    }

    /** V2X button: connect (asking for Bluetooth permission on the phone if needed) or disconnect. */
    private fun toggleV2x() {
        if (BtSession.isActive) { BtSession.stop(); return }
        if (BtSession.hasPermissions(carContext)) { BtSession.acquire(carContext, BtSession.HOLDER_CAR); return }
        carContext.requestPermissions(BluetoothController.runtimePermissions().toList()) { granted, _ ->
            if (granted.size == BluetoothController.runtimePermissions().size)
                BtSession.acquire(carContext, BtSession.HOLDER_CAR)
        }
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
            // No card while idle, with or without V2X data: the V2X status is the chip on the map, so the
            // map area and the controls stay the same whether the ESP32 is connected or not.
            else -> {}
        }
        return b.build()
    }

    private fun actionStrip(): ActionStrip {
        val de = nav.text.german
        // NavigationTemplate allows 4 actions: listed by priority, the rest is left out.
        val a = mutableListOf<Action>()
        if (nav.navigating) {
            a.add(Action.Builder().setTitle(if (de) "Beenden" else "Exit")
                .setOnClickListener { nav.stopNavigation() }.build())
            nav.ahead?.hazards?.firstOrNull()?.let { h ->
                a.add(Action.Builder().setIcon(CarIcons.warning(carContext))
                    .setTitle(if (de) "Umfahren" else "Avoid")
                    .setOnClickListener { nav.avoidHazard(h.hazard) }.build())
            }
        } else {
            a.add(Action.Builder().setIcon(CarIcons.search(carContext))
                .setTitle(if (de) "Ziel" else "Search")
                .setOnClickListener { openSearch() }.build())
        }
        a.add(snapAction())
        a.add(Action.Builder().setIcon(if (nav.muted) CarIcons.volumeOff(carContext) else CarIcons.volumeOn(carContext))
            .setOnClickListener { nav.toggleMute() }.build())
        if (nav.navigating && (nav.isDebugBuild || nav.simulating)) a.add(Action.Builder().setTitle(if (nav.simulating) "Sim ■" else "Demo")
            .setOnClickListener { nav.startSimulation() }.build())
        // V2X connect/disconnect lives in the options (it connects automatically when the car screen opens)
        a.add(Action.Builder().setIcon(CarIcons.settings(carContext))
            .setOnClickListener { screenManager.push(MapOptionsScreen(carContext, nav, ::toggleV2x)) }.build())
        return ActionStrip.Builder().apply { a.take(4).forEach { addAction(it) } }.build()
    }

    /** Snap to road on/off (like the compass button for the map orientation). */
    private fun snapAction(): Action {
        val on = CarPrefs.roadSnap(carContext)
        return Action.Builder().setIcon(if (on) CarIcons.snapOn(carContext) else CarIcons.snapOff(carContext))
            .setOnClickListener {
                CarPrefs.setRoadSnap(carContext, !on)
                nav.toast(when {
                    nav.text.german -> if (!on) "Auf Straße einrasten: an" else "Auf Straße einrasten: aus (GPS-Position)"
                    else -> if (!on) "Snap to road: on" else "Snap to road: off (raw GPS)"
                })
                nav.optionsChanged(); invalidate()
            }.build()
    }

    private fun mapActionStrip(): ActionStrip {
        val s = ActionStrip.Builder()
        // first slot: back to the own position while panned, otherwise north-up / driving-direction toggle
        if (!renderer.following || renderer.zoomFixed) s.addAction(Action.Builder().setIcon(CarIcons.recenter(carContext))
            .setOnClickListener { renderer.recenter(); invalidate() }.build())
        else {
            val north = CarPrefs.northUp(carContext)
            s.addAction(Action.Builder().setIcon(if (north) CarIcons.northUp(carContext) else CarIcons.headingUp(carContext))
                .setOnClickListener { CarPrefs.setNorthUp(carContext, !north); nav.optionsChanged(); invalidate() }.build())
        }
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
