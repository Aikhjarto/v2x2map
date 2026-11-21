package org.opentrafficmap.receiver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import org.opentrafficmap.receiver.car.CarPrefs
import org.opentrafficmap.receiver.car.PendingDestination
import org.opentrafficmap.receiver.nav.LatLon
import org.opentrafficmap.receiver.nav.Place
import org.opentrafficmap.receiver.nav.RoutingClient
import org.opentrafficmap.receiver.nav.ShareParser
import org.opentrafficmap.receiver.nav.ShareParser.Result
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Share target "Navigate with V2X2MAP": takes a place shared from Google Maps, OsmAnd,
 * contacts etc. (or a geo: link), resolves it to coordinates in the background and hands it
 * to the Android Auto screen via [PendingDestination]. Has no UI of its own — it finishes
 * immediately and reports the result as a toast.
 */
class ShareToCarActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            ).joinToString("\n")
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        finish()
        if (text.isNullOrBlank()) { toast(applicationContext, getString(R.string.share_dest_failed)); return }
        val app = applicationContext
        toast(app, getString(R.string.share_dest_resolving))
        Thread {
            val place = runCatching { resolve(app, text) }.getOrNull()
            val msg = when {
                place == null -> app.getString(R.string.share_dest_failed)
                PendingDestination.set(app, place) -> app.getString(R.string.share_dest_live, place.name)
                else -> app.getString(R.string.share_dest_saved, place.name)
            }
            Handler(Looper.getMainLooper()).post { toast(app, msg) }
        }.start()
    }

    companion object {
        private const val USER_AGENT = "V2X2MAP/1.0 (org.opentrafficmap.receiver; share target)"

        private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

        /** Blocking: parse, expand Google short links, or search the text with Photon. */
        fun resolve(ctx: Context, text: String): Place? {
            val parsed = ShareParser.parse(text)
            val r = if (parsed is Result.ShortLink) {
                val expanded = runCatching { expand(parsed.url) }.getOrNull()
                expanded?.let { ShareParser.parseUrl(it, parsed.name) }?.takeIf { it !is Result.ShortLink }
                    ?: ShareParser.textQuery(text)   // e.g. "Café Central\nhttps://maps.app.goo.gl/…"
            } else parsed
            return when (r) {
                is Result.Coords -> Place(r.name ?: "%.5f, %.5f".format(Locale.ROOT, r.pos.lat, r.pos.lon), "", r.pos)
                is Result.Query -> {
                    val lang = if (Locale.getDefault().language == "de") "de-DE" else "en-US"
                    val client = RoutingClient(CarPrefs.valhallaUrl(ctx), language = lang, userAgent = USER_AGENT)
                    val near = lastLocation(ctx)
                    r.candidates.firstNotNullOfOrNull { q ->
                        runCatching { client.search(q, near, limit = 1).firstOrNull() }.getOrNull()
                    }
                }
                else -> null
            }
        }

        /** Follows redirects of a short link until the URL carries coordinates (max 6 hops). */
        private fun expand(url: String): String {
            var cur = url
            repeat(6) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 8_000; c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                val loc = try { if (c.responseCode in 300..399) c.getHeaderField("Location") else null } finally { c.disconnect() }
                    ?: return cur
                cur = URL(URL(cur), loc).toString()
                if (ShareParser.parseUrl(cur, null) is Result.Coords) return cur
            }
            return cur
        }

        /** Last known phone position to bias the search, if location permission was granted. */
        private fun lastLocation(ctx: Context): LatLon? {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return null
            return try {
                val l = Tasks.await(LocationServices.getFusedLocationProviderClient(ctx).lastLocation, 3, TimeUnit.SECONDS)
                l?.let { LatLon(it.latitude, it.longitude) }
            } catch (_: Exception) { null }
        }
    }
}
