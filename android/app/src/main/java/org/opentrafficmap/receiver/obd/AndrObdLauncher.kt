package org.opentrafficmap.receiver.obd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.opentrafficmap.receiver.R

/**
 * Opens AndrOBD on the phone from a place without a visible phone window (the car screen).
 *
 * Android blocks activity starts from the background; the car screen counts as background for the phone.
 * Allowed are: an app with "Display over other apps" (SYSTEM_ALERT_WINDOW), or a tap on a notification.
 * So: with the permission AndrOBD opens directly, otherwise a notification "Tap to open AndrOBD" is posted.
 */
object AndrObdLauncher {
    const val PACKAGE = "com.fr3ts0n.ecu.gui.androbd"
    private const val CHANNEL_ID = "androbd_start"
    private const val NOTIF_ID = 4711

    enum class Result { NOT_INSTALLED, STARTED, NOTIFICATION }

    fun launch(ctx: Context): Result {
        val intent = ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Result.NOT_INSTALLED
        if (Settings.canDrawOverlays(ctx)) {
            try { ctx.startActivity(intent); return Result.STARTED } catch (_: Exception) { /* fall back below */ }
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.obd_start_channel),
                NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.obd_start_notif_title))
            .setContentText(ctx.getString(R.string.obd_start_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi).setAutoCancel(true)
            .build())
        return Result.NOTIFICATION
    }
}
