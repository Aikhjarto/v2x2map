package org.opentrafficmap.receiver.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.opentrafficmap.receiver.BtSession
import org.opentrafficmap.receiver.Prefs

/** Options on the car screen (gear button): V2X link, snap to road, north up, other vehicles, auto-zoom. */
class MapOptionsScreen(
    carContext: CarContext,
    private val nav: NavController,
    private val toggleV2x: () -> Unit,
) : Screen(carContext) {

    private val btListener = BtSession.StateListener { _, _ -> invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { BtSession.addStateListener(btListener) }
            override fun onDestroy(owner: LifecycleOwner) { BtSession.removeStateListener(btListener) }
        })
    }

    override fun onGetTemplate(): Template {
        val de = nav.text.german
        val list = ItemList.Builder()
            .addItem(toggleRow(if (de) "V2X-Empfänger (ESP32)" else "V2X receiver (ESP32)", nav.linkHint(),
                BtSession.isActive) { toggleV2x() })
            .addItem(toggleRow(if (de) "Auf Straße einrasten" else "Snap to road",
                if (de) "Pfeil auf die Straße statt auf die GPS-Position (auch Knopf oben)" else "Arrow on the road instead of the raw GPS fix (also the top button)",
                CarPrefs.roadSnap(carContext)) { CarPrefs.setRoadSnap(carContext, it) })
            .addItem(toggleRow(if (de) "Norden oben" else "North up",
                if (de) "Sonst zeigt die Karte in Fahrtrichtung" else "Otherwise the map turns with the driving direction",
                CarPrefs.northUp(carContext)) { CarPrefs.setNorthUp(carContext, it) })
            .addItem(toggleRow(if (de) "Fahrzeuge anzeigen" else "Show vehicles",
                if (de) "Andere Fahrzeuge, die V2X senden (CAM)" else "Other vehicles that send V2X (CAM)",
                CarPrefs.showVehicles(carContext)) { CarPrefs.setShowVehicles(carContext, it) })
            .addItem(toggleRow(if (de) "Auto-Zoom nach Geschwindigkeit" else "Auto-zoom by speed",
                if (de) "Auch ohne Route (mit Route immer)" else "Also without a route (always with one)",
                Prefs.autoZoom(carContext)) { Prefs.setAutoZoom(carContext, it) })
            .build()
        return ListTemplate.Builder()
            .setTitle(if (de) "Kartenoptionen" else "Map options")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }

    private fun toggleRow(title: String, text: String, checked: Boolean, set: (Boolean) -> Unit): Row =
        Row.Builder().setTitle(title).addText(text)
            .setToggle(Toggle.Builder { on -> set(on); nav.optionsChanged(); invalidate() }.setChecked(checked).build())
            .build()
}
