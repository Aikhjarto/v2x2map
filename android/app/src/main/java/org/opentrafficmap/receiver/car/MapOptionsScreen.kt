package org.opentrafficmap.receiver.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.opentrafficmap.receiver.BtSession
import org.opentrafficmap.receiver.Prefs
import org.opentrafficmap.receiver.R
import org.opentrafficmap.receiver.obd.AndrObdStatus

/** Options on the car screen (gear button): V2X link, AndrOBD, snap to road, north up, other vehicles, auto-zoom. */
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
            .addItem(androbdRow(de))
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

    /**
     * Opens AndrOBD on the phone; with its "connect to the last ELM adapter" setting it then connects by
     * itself. Only while parked (Android Auto rule for opening phone apps). Closing it is not possible:
     * Android lets no app stop another one, and AndrOBD has no disconnect request for plugins.
     */
    private fun androbdRow(de: Boolean): Row {
        val status = when (AndrObdStatus.snapshot().state) {
            AndrObdStatus.State.LIVE -> if (de) "Verbunden, Daten kommen an" else "Connected, data arriving"
            AndrObdStatus.State.WAITING -> if (de) "Keine Daten — AndrOBD nicht mit dem ELM verbunden?" else "No data — AndrOBD not connected to the ELM?"
            AndrObdStatus.State.NONE -> if (de) "Öffnet AndrOBD am Handy (nur im Stand)" else "Opens AndrOBD on the phone (parked only)"
        }
        return Row.Builder().setTitle(if (de) "AndrOBD starten" else "Start AndrOBD").addText(status)
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                val i = carContext.packageManager.getLaunchIntentForPackage(ANDROBD_PACKAGE)
                if (i == null) nav.toast(carContext.getString(R.string.obd_androbd_missing))
                else {
                    carContext.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    nav.toast(if (de) "AndrOBD wird am Handy geöffnet" else "Opening AndrOBD on the phone")
                }
            })
            .build()
    }

    private fun toggleRow(title: String, text: String, checked: Boolean, set: (Boolean) -> Unit): Row =
        Row.Builder().setTitle(title).addText(text)
            .setToggle(Toggle.Builder { on -> set(on); nav.optionsChanged(); invalidate() }.setChecked(checked).build())
            .build()

    private companion object { const val ANDROBD_PACKAGE = "com.fr3ts0n.ecu.gui.androbd" }
}
