package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Builds UPER-encoded SPATEMs by hand (ISO TS 19091 layout) and checks the decoded phase. */
class SpatTemParserTest {

    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v ushr i) and 1 }
        fun ia5(s: String) { put(s.length - 1, 6); s.forEach { put(it.code, 7) } }   // SIZE(1..63)
        fun bytes(): ByteArray = ByteArray((bits.size + 7) / 8) { i ->
            var b = 0; for (k in 0 until 8) b = (b shl 1) or (bits.getOrElse(i * 8 + k) { 0 }); b.toByte() }
    }

    /** BTP-B header (port 2004) + ItsPduHeader + SPAT with one intersection / movement / event. */
    private fun spatem(eventState: Int, withTimestamp: Boolean, withName: Boolean): ByteArray {
        val w = BitWriter()
        w.put(0, 1)                                                     // SPAT extension bit
        w.put(if (withTimestamp) 1 else 0, 1); w.put(if (withName) 1 else 0, 1); w.put(0, 1)   // ts, name, regional
        if (withTimestamp) w.put(123456, 20)
        if (withName) w.ia5("Linz Hbf")
        w.put(0, 5)                                                     // IntersectionStateList count-1 (1..32)
        w.put(0, 1); w.put(0b000000, 6)                                 // IntersectionState ext + 6 optional bits
        w.put(0, 1); w.put(4711, 16)                                    // IntersectionReferenceID: no region, id
        w.put(3, 7); w.put(0, 16)                                       // revision, status
        w.put(0, 8)                                                     // MovementList count-1 (1..255)
        w.put(0, 1); w.put(0b000, 3); w.put(2, 8)                       // MovementState ext, opts, signalGroup
        w.put(0, 4)                                                     // MovementEventList count-1 (1..16)
        w.put(0, 1); w.put(0b000, 3); w.put(eventState, 4)              // MovementEvent ext, opts, eventState
        val btp = byteArrayOf(0x07, 0xD4.toByte(), 0, 0)
        val hdr = byteArrayOf(2, 4, 0, 0, 0x12, 0x34)                   // protocolVersion 2, SPATEM, stationID
        return btp + hdr + w.bytes()
    }

    @Test fun decodesEveryPhaseWithAndWithoutOptionalFields() {
        val expected = mapOf(3 to SpatTemParser.Phase.RED, 4 to SpatTemParser.Phase.YELLOW,
            5 to SpatTemParser.Phase.GREEN, 6 to SpatTemParser.Phase.GREEN,
            7 to SpatTemParser.Phase.YELLOW, 8 to SpatTemParser.Phase.YELLOW,
            0 to SpatTemParser.Phase.UNKNOWN)
        for ((state, phase) in expected) for (ts in listOf(false, true)) for (name in listOf(false, true))
            assertEquals("state=$state ts=$ts name=$name", phase, SpatTemParser.extractPhase(spatem(state, ts, name), 0))
    }
}
