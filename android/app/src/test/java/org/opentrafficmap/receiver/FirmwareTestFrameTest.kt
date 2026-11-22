package org.opentrafficmap.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareTestFrameTest {
    private fun frame(hex: String, type: ItsG5Decoder.MsgType = ItsG5Decoder.MsgType.UNKNOWN) =
        Frame(1, 0, 0, ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }, null, type, null,
            latLon = null, headingDeg = null, speedMps = null)

    @Test fun recognisedAsSentAndWithTheFirstByteLost() {
        assertTrue(frame("deadbeef0102030405060708090a0b0c").isFirmwareTestFrame)
        assertTrue(frame("adbeef0102030405060708090a0b0c43").isFirmwareTestFrame)    // seen in 2026-11-21 recordings
        assertTrue(frame("adbeef0102030405060708090a0b0c5b").isFirmwareTestFrame)
    }

    @Test fun otherFramesAreNot() {
        assertFalse(frame("deadbeef0102030405060708090a0b0d").isFirmwareTestFrame)
        assertFalse(frame("deadbeef0102030405060708090a0b").isFirmwareTestFrame)
        assertFalse(frame("deadbeef0102030405060708090a0b0c", ItsG5Decoder.MsgType.CAM).isFirmwareTestFrame)
    }
}
