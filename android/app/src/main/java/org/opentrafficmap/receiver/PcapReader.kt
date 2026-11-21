package org.opentrafficmap.receiver

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads PCAP recordings (as written by [FrameRecorder], also by the Play Store V2X2MAP) back into
 * [Frame]s. Supports both byte orders, micro- and nanosecond timestamps, raw 802.11 (link type
 * 105) and radiotap-prefixed 802.11 (link type 127).
 */
object PcapReader {
    private const val LINK_IEEE80211 = 105
    private const val LINK_RADIOTAP = 127
    private const val MAX_PACKET = 65_535
    private const val MIN_EPOCH_SEC = 1_000_000_000L   // 2001-09-09: anything smaller is an uptime

    fun read(input: InputStream, maxFrames: Int = 200_000): List<Frame> {
        val data = input.readBytes()
        if (data.size < 24) throw IOException("not a pcap file (too short)")
        val magicLe = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val (order, nanos) = when (magicLe) {
            0xA1B2C3D4.toInt() -> ByteOrder.LITTLE_ENDIAN to false
            0xA1B23C4D.toInt() -> ByteOrder.LITTLE_ENDIAN to true
            0xD4C3B2A1.toInt() -> ByteOrder.BIG_ENDIAN to false
            0x4D3CB2A1 -> ByteOrder.BIG_ENDIAN to true
            else -> throw IOException("not a pcap file (magic %08x)".format(magicLe))
        }
        val bb = ByteBuffer.wrap(data).order(order)
        val link = bb.getInt(20)
        if (link != LINK_IEEE80211 && link != LINK_RADIOTAP) throw IOException("unsupported link type $link")
        bb.position(24)
        val out = ArrayList<Frame>()
        var seq = 0L
        while (bb.remaining() >= 16 && out.size < maxFrames) {
            val sec = bb.int.toLong() and 0xFFFFFFFFL
            val sub = bb.int.toLong() and 0xFFFFFFFFL
            val incl = bb.int
            bb.int                                            // orig_len
            if (incl < 0 || incl > MAX_PACKET || incl > bb.remaining()) break   // truncated file
            val pkt = ByteArray(incl).also { bb.get(it) }
            val payload = if (link == LINK_RADIOTAP) stripRadiotap(pkt) ?: continue else pkt
            val usec = if (nanos) sub / 1000 else sub
            // New recordings store the phone's clock time; old ones (and the Play Store app's)
            // store the ESP32's uptime, which is not a date → receive time unknown.
            val wallMs = if (sec >= MIN_EPOCH_SEC) sec * 1000 + usec / 1000 else 0L
            out += decodeFrame(++seq, sec, usec, payload, receivedMs = wallMs)
        }
        return out
    }

    private fun stripRadiotap(pkt: ByteArray): ByteArray? {
        if (pkt.size < 4) return null
        val len = (pkt[2].toInt() and 0xFF) or ((pkt[3].toInt() and 0xFF) shl 8)   // always little-endian
        return if (len in 4..pkt.size) pkt.copyOfRange(len, pkt.size) else null
    }
}
