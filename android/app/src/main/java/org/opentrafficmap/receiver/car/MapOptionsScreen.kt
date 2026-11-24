package org.opentrafficmap.receiver.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import org.opentrafficmap.receiver.Prefs

/** Map options on the car screen (gear button): north up, other vehicles, auto-zoom. */
class MapOptionsScreen(carContext: CarContext, private val nav: NavController) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val de = nav.text.german
        val list = ItemList.Builder()
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
