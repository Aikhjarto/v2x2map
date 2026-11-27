package org.opentrafficmap.receiver.obd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: `adb shell am broadcast -n org.opentrafficmap.receiver/.obd.DebugLaunchReceiver`
 * runs the car screen's "Start AndrOBD" from the background, to test background activity launch rules
 * without a car.
 */
class DebugLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AndrObdLauncher", "debug launch: " + AndrObdLauncher.launch(context))
    }
}
