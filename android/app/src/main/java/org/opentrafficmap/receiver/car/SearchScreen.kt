package org.opentrafficmap.receiver.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import org.opentrafficmap.receiver.nav.Geo
import org.opentrafficmap.receiver.nav.Place

/**
 * Destination search (Photon/OSM). Shows recent destinations until the user types.
 * Android Auto only allows the keyboard while parked; voice "Navigate to …" works while driving
 * via [NavController.handleIntent].
 */
class SearchScreen(carContext: CarContext, private val nav: NavController) : Screen(carContext) {

    private var results: List<Place> = CarPrefs.recents(carContext)
    private var showingRecents = true
    private var loading = false
    private var lastQuery = ""

    override fun onGetTemplate(): Template {
        val de = nav.text.german
        val list = ItemList.Builder()
        results.take(6).forEach { p ->
            val row = Row.Builder().setTitle(p.name)
            val dist = nav.pos?.let { nav.text.distance(Geo.distance(it, p.pos)) }
            val line = listOfNotNull(dist, p.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (line.isNotBlank()) row.addText(line)
            row.setOnClickListener { setResult(p); screenManager.pop() }
            list.addItem(row.build())
        }
        list.setNoItemsMessage(when {
            showingRecents -> if (de) "Ziel eingeben" else "Type a destination"
            else -> if (de) "Keine Treffer" else "No results"
        })
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(if (de) "Adresse oder Ort" else "Address or place")
            .setShowKeyboardByDefault(results.isEmpty())
            .setLoading(loading)
            .apply { if (!loading) setItemList(list.build()) }
            .build()
    }

    private fun runSearch(q: String) {
        val query = q.trim(); if (query.length < 2 || query == lastQuery && !showingRecents) return
        lastQuery = query; loading = true; invalidate()
        nav.search(query) { res ->
            results = res; showingRecents = false; loading = false; invalidate()
        }
    }
}
