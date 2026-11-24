package org.opentrafficmap.receiver.obd

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2X2MAP as an AndrOBD plugin (protocol of github.com/fr3ts0n/AndrOBD-libplugin, implemented here from
 * its message names — the GPL library itself is not used). For now it only logs what AndrOBD sends, to
 * find out which car values arrive, how often, and whether they keep coming with AndrOBD in the background.
 *
 *  - AndrOBD broadcasts IDENTIFY (category REQUEST) to plugin receivers; [AndrObdPluginReceiver] answers
 *    with IDENTIFY (category RESPONSE) and this plugin's info (features: receives data).
 *  - AndrOBD then binds [AndrObdPluginService] and sends it, with explicit intents, DATALIST
 *    ("key;description;value;units" lines) and DATA ("key=value", one value per intent).
 *
 * Log files, next to the frame recordings (Android/data/org.opentrafficmap.receiver/files/):
 *   androbd-<time>.csv          time_ms,key,value — written and flushed line by line
 *   androbd-<time>-datalist.txt the latest DATALIST, plus the identify info of the host
 */
object AndrObdPlugin {
    const val TAG = "V2xAndrObd"

    private const val P = "com.fr3ts0n.androbd.plugin"
    const val IDENTIFY = "$P.IDENTIFY"
    const val CONFIGURE = "$P.CONFIGURE"
    const val ACTION = "$P.ACTION"
    const val DATALIST = "$P.DATALIST"
    const val DATA = "$P.DATA"
    const val CATEGORY_REQUEST = "$P.REQUEST"
    const val CATEGORY_RESPONSE = "$P.RESPONSE"
    const val EXTRA_DATA = "$P.extra.DATA"

    /** PluginInfo.Field names, used as extra keys of IDENTIFY. */
    private const val F_NAME = "NAME"; private const val F_CLASS = "CLASS"; private const val F_PACKAGE = "PACKAGE"
    private const val F_FEATURES = "FEATURES"; private const val F_DESCRIPTION = "DESCRIPTION"
    private const val F_COPYRIGHT = "COPYRIGHT"; private const val F_LICENSE = "LICENSE"; private const val F_URL = "URL"

    /** PluginInfo.FEATURE_DATA (1 << 2): the host sends DATALIST and DATA to this plugin. */
    private const val FEATURE_DATA = 1 shl 2

    /** Answer to AndrOBD's IDENTIFY request. Addressed to the requesting host's package only. */
    fun identifyResponse(ctx: Context, request: Intent): Intent = Intent(IDENTIFY).apply {
        addCategory(CATEGORY_RESPONSE)
        request.getStringExtra(F_PACKAGE)?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
        putExtra(F_NAME, "V2X2MAP")
        putExtra(F_CLASS, AndrObdPluginService::class.java.name)
        putExtra(F_PACKAGE, ctx.packageName)
        putExtra(F_FEATURES, FEATURE_DATA)
        putExtra(F_DESCRIPTION, "Receives the car data for V2X2MAP (currently: logs it to a file)")
        putExtra(F_COPYRIGHT, "V2X2MAP contributors")
        putExtra(F_LICENSE, "see V2X2MAP")
        putExtra(F_URL, "https://github.com/Aikhjarto/v2x2map")
    }

    fun hostInfo(request: Intent): String =
        listOf(F_NAME, F_PACKAGE, F_CLASS, F_FEATURES).joinToString(", ") { k -> "$k=${request.extras?.get(k)}" }

    /** "key=value" → pair; only the first '=' splits (AndrOBD's own parser would cut values containing '='). */
    fun parseData(s: String?): Pair<String, String>? {
        val i = s?.indexOf('=') ?: return null
        if (i <= 0) return null
        return s.substring(0, i).trim() to s.substring(i + 1).trim()
    }
}

/** Receives AndrOBD's IDENTIFY broadcast and answers it directly (no background service start needed). */
class AndrObdPluginReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != AndrObdPlugin.IDENTIFY) return
        Log.i(AndrObdPlugin.TAG, "identify from ${AndrObdPlugin.hostInfo(intent)}")
        AndrObdLog.get(ctx).note("identify request: ${AndrObdPlugin.hostInfo(intent)}")
        ctx.sendBroadcast(AndrObdPlugin.identifyResponse(ctx, intent))
    }
}

/**
 * Target of AndrOBD's explicit intents (bind on enable, then DATALIST / DATA via startService).
 * onBind returns null like AndrOBD's own plugins; the binding only keeps this process alive.
 */
class AndrObdPluginService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        AndrObdLog.get(this).note("bound by AndrOBD (${intent?.action})")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val log = AndrObdLog.get(this)
        when (intent?.action) {
            AndrObdPlugin.DATA -> AndrObdPlugin.parseData(intent.getStringExtra(AndrObdPlugin.EXTRA_DATA))
                ?.let { (k, v) -> log.value(k, v) }
            AndrObdPlugin.DATALIST -> log.dataList(intent.getStringExtra(AndrObdPlugin.EXTRA_DATA).orEmpty())
            AndrObdPlugin.IDENTIFY -> sendBroadcast(AndrObdPlugin.identifyResponse(this, intent))
            AndrObdPlugin.CONFIGURE, AndrObdPlugin.ACTION -> log.note("${intent.action} (nothing to configure yet)")
        }
        return START_NOT_STICKY
    }
}

/**
 * Append-only log of what AndrOBD sends. A new file starts after 5 minutes without data, so one file
 * is about one drive; every line is flushed at once (nothing lost if the app is killed).
 */
class AndrObdLog private constructor(private val dir: File) {
    private var csv: Writer? = null
    private var stem = ""
    private var lastMs = 0L
    private var bytes = 0L

    @Synchronized fun value(key: String, value: String) {
        val now = System.currentTimeMillis()
        val w = writer(now) ?: return
        w.write("$now,${csvField(key)},${csvField(value)}\n"); w.flush()
        bytes += key.length + value.length + 16
    }

    @Synchronized fun dataList(list: String) {
        val now = System.currentTimeMillis()
        writer(now)
        File(dir, "$stem-datalist.txt").appendText("# DATALIST ${Date(now)}\n$list\n")
    }

    @Synchronized fun note(text: String) {
        val now = System.currentTimeMillis()
        writer(now)
        File(dir, "$stem-datalist.txt").appendText("# ${Date(now)}: $text\n")
    }

    private fun writer(now: Long): Writer? {
        if (csv == null || now - lastMs > NEW_FILE_AFTER_MS) {
            try { csv?.close() } catch (_: Exception) {}
            stem = "androbd-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
            csv = File(dir, "$stem.csv").bufferedWriter().also { it.write("time_ms,key,value\n"); it.flush() }
            bytes = 0
        }
        lastMs = now
        return csv.takeIf { bytes < MAX_FILE_BYTES }   // guard against a misbehaving sender
    }

    private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s

    companion object {
        private const val NEW_FILE_AFTER_MS = 5 * 60_000L
        private const val MAX_FILE_BYTES = 200L * 1024 * 1024
        @Volatile private var instance: AndrObdLog? = null
        fun get(ctx: Context): AndrObdLog = instance ?: synchronized(this) {
            instance ?: AndrObdLog((ctx.getExternalFilesDir(null) ?: ctx.filesDir).apply { mkdirs() }).also { instance = it }
        }
    }
}
