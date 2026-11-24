package org.opentrafficmap.receiver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.receiver.obd.AndrObdStatus
import org.opentrafficmap.receiver.obd.AndrObdStatus.State

class AndrObdStatusTest {
    private var now = 1_000_000L
    init { AndrObdStatus.reset(); AndrObdStatus.clock = { now } }
    @After fun restore() { AndrObdStatus.clock = System::currentTimeMillis; AndrObdStatus.reset() }

    @Test fun noneThenWaitingThenLiveThenWaiting() {
        assertEquals(State.NONE, AndrObdStatus.snapshot().state)
        AndrObdStatus.onContact()
        assertEquals(State.WAITING, AndrObdStatus.snapshot().state)       // AndrOBD knows us, no values
        repeat(20) { i -> now += 250; AndrObdStatus.onData(if (i % 2 == 0) "SPEED" else "RPM") }
        val live = AndrObdStatus.snapshot()
        assertEquals(State.LIVE, live.state)
        assertEquals(2, live.values)
        assertEquals(2.0, live.perSecond, 1e-9)                            // 20 values in the 10 s window
        now += 6_000
        assertEquals(State.WAITING, AndrObdStatus.snapshot().state)       // values stopped
    }
}
