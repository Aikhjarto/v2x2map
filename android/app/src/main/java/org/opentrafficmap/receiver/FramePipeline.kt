package org.opentrafficmap.receiver

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide frame processing, independent of the phone UI: every received frame (Bluetooth
 * via [BtSession], USB via MainActivity) goes to the Android Auto screen ([V2xHub]), the PCAP
 * [recorder], the MQTT bridges and the notification statistics. The phone UI only subscribes
 * for its log/map. So recording and MQTT keep running with the screen off, with the app in the
 * background, and — while Android Auto holds the link — after the app was swiped away.
 */
object FramePipeline {
    fun interface Listener { fun onFrames(frames: List<Frame>) }

    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<Listener>()
    private val mqttBridges = CopyOnWriteArrayList<MqttBridge>()
    private var app: Context? = null
    private var recorderRef: FrameRecorder? = null

    val totalFrames = AtomicLong()
    private val rateWindow = ArrayDeque<Long>()

    /** Phone UI instances currently alive (MainActivity). */
    @Volatile var uiAlive = 0; private set

    fun recorder(ctx: Context): FrameRecorder {
        init(ctx)
        return recorderRef ?: FrameRecorder(ctx.applicationContext).also { recorderRef = it }
    }

    val isRecording: Boolean get() = recorderRef?.isRecording == true
    val mqttRunning: Boolean get() = mqttBridges.isNotEmpty()

    private fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    // ---- UI ---------------------------------------------------------------------------

    fun attachUi(ctx: Context, l: Listener) {
        init(ctx); uiAlive++; uiListeners += l
        startMqttIfEnabled(ctx)
    }

    fun detachUi(l: Listener) {
        uiListeners -= l; uiAlive = (uiAlive - 1).coerceAtLeast(0)
        if (uiAlive == 0 && !BtSession.isActive) stopAll()
    }

    // ---- frames (any thread) ----------------------------------------------------------

    fun onFrames(frames: List<Frame>) {
        if (frames.isEmpty()) return
        val now = System.currentTimeMillis()
        val ctx = app
        // Persistent recording setting: open a new file with the first frame of a session.
        if (ctx != null && Prefs.recordingEnabled(ctx)) recorder(ctx).let { if (!it.isRecording) it.start() }
        val rec = recorderRef
        val filter = if (mqttBridges.isEmpty()) emptySet() else app?.let { Prefs.mqttFilterTypes(it) } ?: emptySet()
        for (f in frames) {
            V2xHub.ingest(f)
            if (rec != null && rec.isRecording) rec.append(f)
            if (mqttBridges.isNotEmpty() && f.msgType.name in filter) mqttBridges.forEach { it.publish(f.payload) }
        }
        totalFrames.addAndGet(frames.size.toLong())
        synchronized(rateWindow) { repeat(frames.size) { rateWindow.addLast(now) } }
        if (uiListeners.isNotEmpty()) main.post { uiListeners.forEach { it.onFrames(frames) } }
    }

    /** Frames in the last minute. */
    fun ratePerMinute(): Int = synchronized(rateWindow) {
        val cutoff = System.currentTimeMillis() - 60_000
        while (rateWindow.isNotEmpty() && rateWindow.first < cutoff) rateWindow.removeFirst()
        rateWindow.size
    }

    // ---- recording / MQTT -------------------------------------------------------------

    /**
     * Toggles the (persistent) recording setting; returns a user-facing status message.
     * On: a file starts with the next received frame. Off: the current file is closed.
     */
    fun toggleRecording(ctx: Context): String {
        val rec = recorder(ctx)
        val on = !Prefs.recordingEnabled(ctx)
        Prefs.setRecordingEnabled(ctx, on)
        return if (on) {
            ctx.getString(R.string.rec_armed)
        } else {
            val stopped = rec.stop()
            if (stopped != null) ctx.getString(R.string.rec_stopped, rec.frameCount, stopped.absolutePath)
            else ctx.getString(R.string.rec_status_idle)
        }
    }

    fun startMqttIfEnabled(ctx: Context) {
        if (mqttBridges.isEmpty() && Prefs.mqttEnabled(ctx) && Prefs.mqttBrokerList(ctx).isNotEmpty()) startMqtt(ctx)
    }

    fun startMqtt(ctx: Context) {
        stopMqtt()
        init(ctx)
        val nodeId = Prefs.nodeId(ctx).trim().ifEmpty { ctx.getString(R.string.default_node_id) }
        Prefs.mqttBrokerList(ctx).filter { it.isNotBlank() }.forEach { url ->
            MqttBridge(nodeId, normaliseBroker(url.trim())).also { it.start(); mqttBridges += it }
        }
    }

    fun stopMqtt() {
        val old = mqttBridges.toList(); mqttBridges.clear()
        old.forEach { try { it.stop() } catch (_: Exception) {} }
    }

    /** Stops recording (closing the file cleanly) and MQTT. */
    fun stopAll() {
        try { recorderRef?.stop() } catch (_: Exception) {}
        stopMqtt()
    }

    /**
     * Called when the Bluetooth link has stopped. Without a phone UI there is nothing left to
     * record or forward: close everything and end the foreground service.
     */
    fun onLinkStopped() {
        if (uiAlive > 0) return
        stopAll()
        app?.let { it.stopService(Intent(it, ReceiverForegroundService::class.java)) }
    }

    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://") -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://") || s.startsWith("tcp://") -> s
        else -> "ssl://$s"
    }
}
