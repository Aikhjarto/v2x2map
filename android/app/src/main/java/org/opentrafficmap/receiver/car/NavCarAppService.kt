@file:Suppress("DEPRECATION")

package org.opentrafficmap.receiver.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import android.preference.PreferenceManager
import org.opentrafficmap.receiver.R

/** Entry point Android Auto binds to. Declared in the manifest with category NAVIGATION. */
class NavCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = NavSession()
}

class NavSession : Session() {
    private var screen: NavScreen? = null

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onCreate(owner: androidx.lifecycle.LifecycleOwner) { org.opentrafficmap.receiver.CarLink.carScreenActive = true }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { org.opentrafficmap.receiver.CarLink.carScreenActive = false }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // osmdroid config is needed for reading the phone app's offline tile cache
        try {
            org.osmdroid.config.Configuration.getInstance()
                .load(carContext.applicationContext, PreferenceManager.getDefaultSharedPreferences(carContext))
        } catch (_: Throwable) {}
        return NavScreen(carContext).also { s ->
            screen = s
            s.nav.handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) { screen?.nav?.handleIntent(intent) }
}

/**
 * Keeps location updates alive during active guidance even if the phone screen is off
 * (Android requires a location-type foreground service for that).
 */
class NavForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Navigation", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Navigation · V2X")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "v2x2map_nav"
        private const val ID = 2
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, NavForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { /* background-start restrictions: guidance still works while AA is projecting */ }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, NavForegroundService::class.java)) } catch (_: Exception) {} }
    }
}
