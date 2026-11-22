package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opentrafficmap.receiver.asn1.AsnSchema
import org.opentrafficmap.receiver.asn1.SpatemParser
import org.opentrafficmap.receiver.asn1.UperDecoder
import org.opentrafficmap.receiver.nav.SignalPhase
import org.opentrafficmap.receiver.nav.V2xSignal
import org.opentrafficmap.receiver.nav.LatLon
import java.io.File

class SpatemTimingTest {
    private val hourStart = 1_795_300_000_000L / 3_600_000L * 3_600_000L     // some full UTC hour

    @Test fun timeMarkToAbsoluteTime() {
        // 10:00.0 into the hour, light changes at TimeMark 6123 (= 612.3 s) -> 12.3 s later
        assertEquals(hourStart + 612_300, SpatemParser.endTime(6123, hourStart + 600_000))
        // mark already passed by 2 s (clock skew) -> "now"
        assertEquals(hourStart + 600_000, SpatemParser.endTime(5980, hourStart + 600_000))
        // mark far in the past -> next hour (59:50 now, change at 00:05)
        assertEquals(hourStart + 3_605_000, SpatemParser.endTime(50, hourStart + 3_590_000))
        assertNull(SpatemParser.endTime(36001, hourStart))                      // unknown
    }

    @Test fun firstMovementOfSyntheticSpatem() {
        val line = javaClass.classLoader!!.getResource("its_vectors.tsv")!!.readText().lines().first { it.startsWith("SPATEM\t") }
        val hex = line.split('\t')[1]
        val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val v = UperDecoder(AsnSchema.load("iso", File("src/main/assets/its_asn1/iso.json").inputStream())).decode("SPATEM", bytes)
        val now = hourStart + 1_230_000                                           // 20:30.0 into the hour
        val r = SpatemParser.parse(v, now)!!
        assertEquals(SignalPhase.RED, r.phase)                                    // group 2, not group 5
        assertEquals(hourStart + 1_234_500, r.endsAtMs)                           // likelyTime 12345
        assertEquals(5, V2xSignal(1, LatLon(0.0, 0.0), r.phase, now, endsAtMs = r.endsAtMs).remainingS(now))
    }

    @Test fun demoCycle() {
        val t0 = 1_000_000_000L / 21_000L * 21_000L                               // start of a demo cycle
        assertEquals(SignalPhase.RED to t0 + 8_000, DemoV2x.phaseAt(t0 + 500))
        assertEquals(SignalPhase.GREEN to t0 + 18_000, DemoV2x.phaseAt(t0 + 12_000))
        assertEquals(SignalPhase.YELLOW to t0 + 21_000, DemoV2x.phaseAt(t0 + 20_000))
    }
}
