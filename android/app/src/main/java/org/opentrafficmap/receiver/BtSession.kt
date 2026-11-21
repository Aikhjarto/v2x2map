package org.opentrafficmap.receiver

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide Bluetooth link to the ESP32-C5, shared by the phone UI and the Android Auto
 * screen. Each side "holds" the link while it needs it; it stops when the last holder releases
 * it or when either side's button disconnects explicitly. Received frames go to [FramePipeline]
 * (car screen, recording, MQTT, phone log/map).
 */
object BtSession {
    const val HOLDER_PHONE = "phone"
    const val HOLDER_CAR = "car"

    fun interface StateListener { fun onState(state: BluetoothController.State, info: String?) }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reader = FrameReader()
    private val holders = HashSet<String>()
    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private var controller: BluetoothController? = null

    @Volatile var state = BluetoothController.State.IDLE; private set
    @Volatile var info: String? = null; private set
    val isActive: Boolean get() = controller != null

    fun hasPermissions(ctx: Context) = BluetoothController.runtimePermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts the link (if not running) and registers [holder]. Main thread. */
    fun acquire(ctx: Context, holder: String) {
        holders += holder
        if (controller != null) return
        val app = ctx.applicationContext
        ReceiverForegroundService.start(app)
        reader = FrameReader()
        controller = BluetoothController(
            context = app,
            onBytes = ::onBytes,
            onState = { s, i -> main.post { setState(s, i) } },
            onConfig = { SettingsBus.liveCycleConfig = it },
        ).also { it.start(); SettingsBus.liveBtController = it }
    }

    /** Unregisters [holder]; stops the link when nobody needs it any more. Main thread. */
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) stop()
    }

    /** Explicit disconnect from either screen: stops the link for everyone. Main thread. */
    fun stop() {
        holders.clear()
        val c = controller ?: return
        controller = null
        SettingsBus.liveBtController = null
        try { c.stop() } catch (_: Exception) {}
        setState(BluetoothController.State.IDLE, null)
        FramePipeline.onLinkStopped()
    }

    fun writeConfig(c: BluetoothController.CycleConfig): Boolean = controller?.writeConfig(c) ?: false

    fun addStateListener(l: StateListener) { stateListeners += l; l.onState(state, info) }
    fun removeStateListener(l: StateListener) { stateListeners -= l }

    private fun setState(s: BluetoothController.State, i: String?) {
        if (controller == null && s != BluetoothController.State.IDLE) return   // late callback after stop()
        state = s; info = i
        stateListeners.forEach { it.onState(s, i) }
    }

    private fun onBytes(chunk: ByteArray) {
        val r = reader
        val frames = synchronized(r) { r.feed(chunk) }
        FramePipeline.onFrames(frames)
    }
}
