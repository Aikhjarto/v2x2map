package org.opentrafficmap.receiver.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.Place

/**
 * Hands a destination shared on the phone ("Navigate with V2X2MAP") to the Android Auto
 * screen. If a car session is running it gets the destination immediately; otherwise it is
 * stored and picked up the next time V2X2MAP opens in Android Auto (within [MAX_AGE_MS]).
 */
object PendingDestination {
    private const val MAX_AGE_MS = 6 * 3600_000L
    private val main = Handler(Looper.getMainLooper())

    /** Set by [NavController] while a car session exists. Called on the main thread. */
    @Volatile var listener: ((Place) -> Unit)? = null

    private fun p(ctx: Context) = ctx.getSharedPreferences("car_nav", Context.MODE_PRIVATE)

    /** Stores [place] and delivers it to a running car session. Returns true if one was running. */
    fun set(ctx: Context, place: Place): Boolean {
        p(ctx).edit()
            .putString("pending_name", place.name).putString("pending_detail", place.detail)
            .putString("pending_lat", place.pos.lat.toString()).putString("pending_lon", place.pos.lon.toString())
            .putLong("pending_time", System.currentTimeMillis())
            .apply()
        CarPrefs.addRecent(ctx, place)
        val l = listener ?: return false
        main.post { l(place) }
        return true
    }

    /** Returns the stored destination if it is recent enough, and removes it. */
    fun take(ctx: Context): Place? {
        val prefs = p(ctx)
        val time = prefs.getLong("pending_time", 0L)
        if (time == 0L) return null
        clear(ctx)
        if (System.currentTimeMillis() - time > MAX_AGE_MS) return null
        val lat = prefs.getString("pending_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("pending_lon", null)?.toDoubleOrNull() ?: return null
        return Place(prefs.getString("pending_name", null) ?: "$lat, $lon",
            prefs.getString("pending_detail", null) ?: "", LatLon(lat, lon))
    }

    fun clear(ctx: Context) {
        p(ctx).edit().remove("pending_name").remove("pending_detail").remove("pending_lat")
            .remove("pending_lon").remove("pending_time").apply()
    }
}
