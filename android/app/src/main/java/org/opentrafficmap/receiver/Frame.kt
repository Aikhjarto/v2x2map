package org.opentrafficmap.receiver

/**
 * One ITS-G5 sniffer frame as it arrived from the ESP32-C5 over USB or BLE,
 * with whatever the lightweight [ItsG5Decoder] could pull out of the payload.
 */
data class Frame(
    val seq: Long,
    val sec: Long,
    val usec: Long,
    val payload: ByteArray,
    val etherType: Int?,
    val msgType: ItsG5Decoder.MsgType,
    val stationId: Long?,
    val stationType: Int? = null,   // ETSI StationType (5-bit: 0=unknown 5=passengerCar 15=RSU)
    val latLon: Pair<Double, Double>?,
    val headingDeg: Double?,
    val speedMps: Double?,
    val spatPhase: SpatTemParser.Phase? = null,
    val secured: Boolean? = null,   // GN Basic Header NH==2 → IEEE 1609.2 signed packet
    val denmCause: DenmParser.Cause? = null,
    val denmEvent: DenmParser.Event? = null,
    /** Phone clock time (epoch ms) when the frame was received; 0 = unknown (old recording). */
    val receivedMs: Long = System.currentTimeMillis(),
) {
    val len: Int get() = payload.size

    /**
     * The ESP32 firmware's boot test frame (payload DE AD BE EF 01 … 0C, usb_stream_send_test_frame):
     * no ITS content by design. Also the variant whose first byte was lost on the USB link at boot.
     */
    val isFirmwareTestFrame: Boolean get() = payload.size == 16 && msgType == ItsG5Decoder.MsgType.UNKNOWN &&
        (payload.contentEquals(TEST_PAYLOAD) || payload.copyOfRange(0, 15).contentEquals(TEST_PAYLOAD.copyOfRange(1, 16)))

    fun hexPreview(maxBytes: Int = 32): String {
        val n = minOf(payload.size, maxBytes)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = payload[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = seq.hashCode()

    companion object {
        private val TEST_PAYLOAD = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    }
}
