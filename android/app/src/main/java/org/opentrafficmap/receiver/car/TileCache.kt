package org.opentrafficmap.receiver.car

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import org.opentrafficmap.receiver.NightTiles
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Small raster tile cache for the car map: memory LRU → disk (app cache dir) → network.
 * As an offline fallback it also reads tiles the phone app already downloaded into
 * osmdroid's SQLite cache ("Download visible map" in V2X2MAP settings).
 */
class TileCache(private val ctx: Context, private val onTileLoaded: () -> Unit) {

    /**
     * @param id           memory-cache key prefix (differs per look)
     * @param source       disk-cache directory (shared by styles that download the same tiles)
     * @param osmdroidName provider name in the phone app's osmdroid cache, for the offline fallback
     * @param night        darken the tiles (invert + hue-rotate) after decoding
     */
    data class Style(val id: String, val source: String, val urls: List<String>,
                     val osmdroidName: String?, val night: Boolean)

    companion object {
        // OpenStreetMap standard tiles (same as the phone app's default layer). CARTO basemaps
        // used to be keyless but now only return an "API KEY REQUIRED" placeholder tile.
        private val OSM_URLS = listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        val DAY = Style("osm", "osm", OSM_URLS, "Mapnik", night = false)
        val NIGHT = Style("osm-night", "osm", OSM_URLS, "Mapnik", night = true)
        private const val USER_AGENT = "V2X2MAP-Auto/1.0 (org.opentrafficmap.receiver; Android Auto navigation)"

        private fun toNight(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = NightTiles.filter })
            return out
        }
    }

    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    private val diskDir = File(ctx.cacheDir, "car_tiles").apply { mkdirs() }

    private val osmdroidDb: SQLiteDatabase? by lazy {
        try {
            val base = org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
            val f = File(base, "cache.db")
            if (f.exists()) SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) else null
        } catch (_: Throwable) { null }
    }

    /** Returns the tile if available now; otherwise starts loading it and returns null. */
    fun get(style: Style, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${style.id}/$z/$x/$y"
        mem.get(key)?.let { return it }
        val failedAt = failed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < 30_000) return null
        if (inFlight.add(key)) pool.execute { load(style, z, x, y, key) }
        return null
    }

    /** Memory-only lookup (used for parent-tile fallback while zooming). */
    fun peek(style: Style, z: Int, x: Int, y: Int): Bitmap? = mem.get("${style.id}/$z/$x/$y")

    private fun load(style: Style, z: Int, x: Int, y: Int, key: String) {
        try {
            val file = File(diskDir, "${style.source}/$z/$x/$y.png")
            var bmp: Bitmap? = null
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600_000)
                bmp = BitmapFactory.decodeFile(file.path)
            if (bmp == null) bmp = download(style, z, x, y)?.also { bytes ->
                file.parentFile?.mkdirs(); file.writeBytes(bytes)
            }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp == null && file.exists()) bmp = BitmapFactory.decodeFile(file.path) // stale but better than nothing
            if (bmp == null) bmp = fromOsmdroid(style, z, x, y)
            if (bmp != null && style.night) bmp = toNight(bmp)
            if (bmp != null) { mem.put(key, bmp); failed.remove(key); onTileLoaded() }
            else failed[key] = System.currentTimeMillis()
        } catch (_: Throwable) {
            failed[key] = System.currentTimeMillis()
        } finally { inFlight.remove(key) }
    }

    private fun download(style: Style, z: Int, x: Int, y: Int): ByteArray? = try {
        val tmpl = style.urls[(x + y) % style.urls.size]
        val url = tmpl.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    /** osmdroid SqlTileWriter layout: table tiles(key, provider, tile, expires), key = ((z<<z)+x<<z)+y. */
    private fun fromOsmdroid(style: Style, z: Int, x: Int, y: Int): Bitmap? {
        val db = osmdroidDb ?: return null
        val name = style.osmdroidName ?: return null
        val index = (((z.toLong() shl z) + x) shl z) + y
        return try {
            db.rawQuery("SELECT tile FROM tiles WHERE key=? AND provider=?", arrayOf(index.toString(), name)).use { c ->
                if (c.moveToFirst()) c.getBlob(0)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
            }
        } catch (_: Throwable) { null }
    }

    fun shutdown() { pool.shutdownNow(); try { osmdroidDb?.close() } catch (_: Throwable) {} }
}
