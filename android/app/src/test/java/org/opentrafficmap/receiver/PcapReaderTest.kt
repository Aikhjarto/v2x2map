package org.opentrafficmap.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapReaderTest {

    private fun pcap(order: ByteOrder, magic: Int, link: Int, packets: List<Triple<Int, Int, ByteArray>>): ByteArray {
        val size = 24 + packets.sumOf { 16 + it.third.size }
        val bb = ByteBuffer.allocate(size).order(order)
        bb.putInt(magic).putShort(2).putShort(4).putInt(0).putInt(0).putInt(65535).putInt(link)
        for ((sec, sub, p) in packets) bb.putInt(sec).putInt(sub).putInt(p.size).putInt(p.size).put(p)
        return bb.array()
    }

    private val a = byteArrayOf(0x88.toByte(), 0, 1, 2, 3)
    private val b = byteArrayOf(0x08, 0, 9, 8)

    @Test fun readsLittleEndianMicroseconds() {
        val f = PcapReader.read(ByteArrayInputStream(pcap(ByteOrder.LITTLE_ENDIAN, 0xA1B2C3D4.toInt(), 105,
            listOf(Triple(1_700_000_000, 250_000, a), Triple(1_700_000_001, 5, b)))))
        assertEquals(2, f.size)
        assertEquals(1_700_000_000L, f[0].sec); assertEquals(250_000L, f[0].usec)
        assertArrayEquals(a, f[0].payload); assertArrayEquals(b, f[1].payload)
        assertEquals(listOf(1L, 2L), f.map { it.seq })
        assertEquals(1_700_000_000_250L, f[0].receivedMs)         // epoch timestamp → real receive time
    }

    @Test fun readsBigEndianNanosecondsAndRadiotap() {
        val radiotap = byteArrayOf(0, 0, 8, 0, 0, 0, 0, 0) + a     // 8-byte radiotap header
        val f = PcapReader.read(ByteArrayInputStream(pcap(ByteOrder.BIG_ENDIAN, 0xA1B23C4D.toInt(), 127,
            listOf(Triple(10, 123_456_789, radiotap)))))
        assertEquals(1, f.size); assertEquals(123_456L, f[0].usec); assertArrayEquals(a, f[0].payload)
        assertEquals(0L, f[0].receivedMs)                           // 10 s = ESP32 uptime → unknown
    }

    @Test fun stopsAtTruncatedPacket() {
        val full = pcap(ByteOrder.LITTLE_ENDIAN, 0xA1B2C3D4.toInt(), 105, listOf(Triple(1, 0, a), Triple(2, 0, b)))
        val f = PcapReader.read(ByteArrayInputStream(full.copyOf(full.size - 2)))
        assertEquals(1, f.size)
    }

    @Test(expected = IOException::class) fun rejectsNonPcap() {
        PcapReader.read(ByteArrayInputStream(ByteArray(40) { 7 }))
    }
}
