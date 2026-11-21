package org.opentrafficmap.receiver.car

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.*
import org.opentrafficmap.receiver.nav.*
import java.util.TimeZone

/** Converts our route/progress into Car App Library model objects. */
object TripBuilder {

    fun distance(m: Double): Distance = when {
        m >= 10_000 -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS)
        m >= 1_000 -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create((Math.round(m / 50.0) * 50).toDouble(), Distance.UNIT_METERS)
        else -> Distance.create((Math.round(m / 10.0) * 10).toDouble().coerceAtLeast(0.0), Distance.UNIT_METERS)
    }

    fun step(m: RouteManeuver): Step {
        val b = Step.Builder(m.instruction.ifBlank { " " }).setManeuver(CarIcons.toCarManeuver(m))
        m.streetNames.firstOrNull()?.let { b.setRoad(it) }
        return b.build()
    }

    fun destinationEstimate(st: NavState): TravelEstimate {
        val arrival = System.currentTimeMillis() + (st.remainingS * 1000).toLong()
        return TravelEstimate.Builder(distance(st.remainingM), DateTimeWithZone.create(arrival, TimeZone.getDefault()))
            .setRemainingTimeSeconds(st.remainingS.toLong().coerceAtLeast(0))
            .build()
    }

    fun stepEstimate(st: NavState, speedMps: Double): TravelEstimate {
        val secs = (st.distToNextM / speedMps.coerceAtLeast(5.0)).toLong()
        return TravelEstimate.Builder(distance(st.distToNextM),
            DateTimeWithZone.create(System.currentTimeMillis() + secs * 1000, TimeZone.getDefault()))
            .setRemainingTimeSeconds(secs).build()
    }

    fun routingInfo(r: Route, st: NavState): RoutingInfo {
        val b = RoutingInfo.Builder().setCurrentStep(step(r.maneuvers[st.nextManeuver]), distance(st.distToNextM))
        r.maneuvers.getOrNull(st.nextManeuver + 1)?.let { b.setNextStep(step(it)) }
        return b.build()
    }

    fun trip(ctx: Context, nav: NavController, r: Route, st: NavState): Trip {
        val dest = nav.destination
        val d = Destination.Builder().setName(dest?.name ?: "Ziel").apply {
            dest?.detail?.takeIf { it.isNotBlank() }?.let { setAddress(it) }
        }.build()
        val b = Trip.Builder()
            .addStep(step(r.maneuvers[st.nextManeuver]), stepEstimate(st, nav.speedMps))
            .addDestination(d, destinationEstimate(st))
        r.maneuvers.getOrNull((st.nextManeuver - 1).coerceAtLeast(0))?.streetNames?.firstOrNull()?.let { b.setCurrentRoad(it) }
        return b.build()
    }
}

/** Small preference store for the car part (recents, mute, routing server). */
object CarPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("car_nav", Context.MODE_PRIVATE)

    fun muted(ctx: Context) = p(ctx).getBoolean("muted", false)
    fun setMuted(ctx: Context, m: Boolean) = p(ctx).edit().putBoolean("muted", m).apply()

    /** Routing server. Defaults to the public FOSSGIS Valhalla; set "valhalla_url" to use your own. */
    fun valhallaUrl(ctx: Context): String = p(ctx).getString("valhalla_url", null) ?: RoutingClient.DEFAULT_VALHALLA

    fun recents(ctx: Context): List<Place> =
        (p(ctx).getString("recents", "") ?: "").lines().mapNotNull { line ->
            val f = line.split('\t'); if (f.size < 4) return@mapNotNull null
            val lat = f[2].toDoubleOrNull() ?: return@mapNotNull null; val lon = f[3].toDoubleOrNull() ?: return@mapNotNull null
            Place(f[0], f[1], LatLon(lat, lon))
        }

    fun addRecent(ctx: Context, pl: Place) {
        val list = (listOf(pl) + recents(ctx).filter { Geo.distance(it.pos, pl.pos) > 30 }).take(8)
        p(ctx).edit().putString("recents", list.joinToString("\n") {
            "${it.name.replace('\t', ' ')}\t${it.detail.replace('\t', ' ')}\t${it.pos.lat}\t${it.pos.lon}" }).apply()
    }
}
