package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

/** Synthetic signed GeoBroadcast packet whose payload needs the 3-byte OER length (>= 256 B). */
class ItsG5DecoderSecuredTest {

    private fun frame(payloadLen: Int, lat: Double, lon: Double, btpPort: Int): ByteArray {
        val mac = byteArrayOf(0x88.toByte(), 0, 0, 0) + ByteArray(6) { 0xff.toByte() } +
            byteArrayOf(0, 0x0d, 0x41, 0xff.toByte(), 0x5c, 0xe7.toByte()) + ByteArray(6) { 0xff.toByte() } +
            byteArrayOf(0x10, 0x00, 0x25, 0x00)                                  // seq ctrl + QoS
        val llc = byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 3, 0, 0, 0, 0x89.toByte(), 0x47)
        val basic = byteArrayOf(0x12, 0x00, 0x1a, 0x0a)                           // NH=2: secured
        // inner: GN common header (8) + GBC extended header (44) + BTP-B (4) + ITS PDU
        val inner = ByteBuffer.allocate(payloadLen)
        inner.put(byteArrayOf(0x20, 0x40, 0x01, 0x00)).putShort((payloadLen - 8).toShort()).put(byteArrayOf(0x0a, 0x00))
        inner.putShort(0x1234).putShort(0)                                          // SN + reserved
        inner.put(byteArrayOf(0x3c, 0, 0, 0x0d, 0x41, 0xff.toByte(), 0x5c, 0xe7.toByte()))  // GN address (RSU)
        inner.putInt(0x2f42599b)                                                    // timestamp
        inner.putInt((lat * 1e7).toInt()).putInt((lon * 1e7).toInt())
        inner.putShort(0).putShort(0)                                               // speed, heading
        inner.put(ByteArray(16))                                                    // GeoArea (14) + reserved (2)
        inner.putShort(btpPort.toShort()).putShort(0)                               // BTP-B
        inner.put(byteArrayOf(2, 6))                                                // ITS PDU header start
        val sec = byteArrayOf(0x03, 0x81.toByte(), 0x00, 0x40, 0x03, 0x80.toByte(),
            0x82.toByte(), (payloadLen shr 8).toByte(), payloadLen.toByte())
        return mac + llc + basic + sec + inner.array() + ByteArray(80)              // + signature etc.
    }

    @Test fun largeSignedGeoBroadcastIsDecodedAtTheRightOffset() {
        val d = ItsG5Decoder.decodeFull(frame(343, 48.2345, 14.6123, 2006))
        assertEquals(ItsG5Decoder.MsgType.IVIM, d.msgType)
        val ll = d.latLon; assertNotNull(ll)
        assertEquals(48.2345, ll!!.first, 1e-6); assertEquals(14.6123, ll.second, 1e-6)
        assertEquals(0x3c0000_0d41ff5ce7L, d.stationId)
    }

    @Test fun shortSignedPacketStillWorks() {
        val f = frame(120, 48.1, 14.3, 2006)
        // rewrite the length as the 1-byte OER form
        val i = f.indexOfFirst { it == 0x82.toByte() }
        val short = f.copyOfRange(0, i) + byteArrayOf(120) + f.copyOfRange(i + 3, f.size)
        val d = ItsG5Decoder.decodeFull(short)
        assertEquals(ItsG5Decoder.MsgType.IVIM, d.msgType)
        assertEquals(48.1, d.latLon!!.first, 1e-6)
    }
}
