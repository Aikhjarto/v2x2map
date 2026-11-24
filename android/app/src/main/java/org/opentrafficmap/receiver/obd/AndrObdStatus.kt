package org.opentrafficmap.receiver.obd

/**
 * Whether car data from AndrOBD arrives, for the status shown on the phone (OBD button) and on the
 * car screen (OBD chip) — the counterpart of the ESP32 link status. Thread-safe; no Android deps.
 */
object AndrObdStatus {
    enum class State {
        /** AndrOBD has not contacted V2X2MAP since the app started. */
        NONE,
        /** AndrOBD knows the plugin, but no values arrived in the last [LIVE_MS]. */
        WAITING,
        /** Values arrive. */
        LIVE,
    }

    data class Snapshot(val state: State, val perSecond: Double, val values: Int, val lastDataMs: Long)

    const val LIVE_MS = 5_000L
    private const val WINDOW_MS = 10_000L

    private val lock = Any()
    private var lastContactMs = 0L
    private var lastDataMs = 0L
    private val recent = ArrayDeque<Pair<Long, String>>()   // (time, key) of the last WINDOW_MS

    var clock: () -> Long = System::currentTimeMillis

    fun onContact() = synchronized(lock) { lastContactMs = clock() }

    fun onData(key: String) = synchronized(lock) {
        val now = clock()
        lastContactMs = now; lastDataMs = now
        recent.addLast(now to key)
        trim(now)
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val now = clock()
        trim(now)
        val state = when {
            lastDataMs > 0 && now - lastDataMs < LIVE_MS -> State.LIVE
            lastContactMs > 0 -> State.WAITING
            else -> State.NONE
        }
        Snapshot(state, recent.size * 1000.0 / WINDOW_MS, recent.map { it.second }.toSet().size, lastDataMs)
    }

    fun reset() = synchronized(lock) { lastContactMs = 0; lastDataMs = 0; recent.clear() }

    private fun trim(now: Long) { while (recent.isNotEmpty() && now - recent.first().first > WINDOW_MS) recent.removeFirst() }
}
