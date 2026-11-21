package org.opentrafficmap.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ReceiverForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var tapIntent: PendingIntent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Stop" in the notification: end the link, recording and MQTT without opening the app
            FramePipeline.stopAll()
            BtSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_desc) }
        )

        tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // Android 14+ only allows a "connectedDevice" foreground service once Bluetooth permission
        // is granted or a USB device is attached with permission. Starting it earlier threw a
        // SecurityException and, with START_STICKY, crash-looped the whole app.
        if (!mayRunAsConnectedDevice(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(getString(R.string.notif_running)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: Exception) {   // SecurityException / ForegroundServiceStartNotAllowedException
            stopSelf(); return START_NOT_STICKY
        }

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2x2map:receiver")
                .also { it.acquire(MAX_SESSION_MS) }
        }

        instance = this
        handler.removeCallbacks(statsTick); handler.post(statsTick)
        // Not sticky: after the process dies there is no BT/USB connection left to keep alive.
        return START_NOT_STICKY
    }



    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            updateStats(FramePipeline.totalFrames.get().toInt(), FramePipeline.ratePerMinute())
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTick)
        instance = null
        try { wakeLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun updateStats(totalPackets: Int, ratePerMin: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(getString(R.string.notif_stats, totalPackets, ratePerMin)))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notif_stop), PendingIntent.getService(
                this, 1, Intent(this, ReceiverForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        private const val CHANNEL_ID     = "v2x2map_receiver"
        private const val NOTIF_ID       = 1
        private const val MAX_SESSION_MS = 8L * 60 * 60 * 1000
        private const val ACTION_STOP    = "org.opentrafficmap.receiver.STOP"

        @Volatile var instance: ReceiverForegroundService? = null

        /** Starts the service if Android allows it right now; silently does nothing otherwise. */
        fun start(ctx: Context) {
            if (!mayRunAsConnectedDevice(ctx)) return
            val intent = Intent(ctx, ReceiverForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { /* e.g. not allowed from the background — the link still works */ }
        }

        /** Whether Android allows the "connectedDevice" foreground service right now (Android 14+ rule). */
        fun mayRunAsConnectedDevice(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED) return true
            val usb = ctx.getSystemService(UsbManager::class.java)
            return usb?.deviceList?.values?.any { usb.hasPermission(it) } == true
        }
    }
}
