package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.asn1.AsnFormat
import org.opentrafficmap.receiver.asn1.AsnJson
import org.opentrafficmap.receiver.asn1.AsnSchema
import org.opentrafficmap.receiver.asn1.ItsFrameDecoder
import org.opentrafficmap.receiver.asn1.UperDecoder
import java.io.File

/**
 * its_vectors.tsv: one CAM, DENM and IVIM from real recordings with every position and station
 * ID replaced (re-encoded and decoded by pycrate, tools/asn1gen). The generic decoder must give
 * exactly pycrate's result.
 */
class ItsDecoderTest {

    private val assets = File("src/main/assets/its_asn1")
    private fun open(set: String) = File(assets, "$set.json").inputStream()
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val vectors = javaClass.classLoader!!.getResource("its_vectors.tsv")!!.readText().lines()
        .filter { it.isNotBlank() }.map { it.split('\t', limit = 3) }.associate { (r, h, j) -> r to (hex(h) to j) }

    @Test fun vectorsMatchPycrate() {
        val sets = mapOf("CAM" to "cam", "DENM" to "denm", "IVIM" to "iso", "SPATEM" to "iso")
        for ((root, v) in vectors) {
            val decoded = UperDecoder(AsnSchema.load(sets.getValue(root), open(sets.getValue(root)))).decode(root, v.first)
            assertEquals(root, v.second, AsnJson.toJson(decoded))
        }
    }

    @Test fun fullFrameLayersAndUnits() {
        val cam = vectors.getValue("CAM").first
        // unsigned SHB frame: 802.11 QoS header, LLC/SNAP, GN basic + common + SHB header, BTP-B 2001
        val frame = byteArrayOf(0x88.toByte(), 0, 0, 0) + ByteArray(18) { 0xff.toByte() } + byteArrayOf(0x10, 0, 0, 0) +
            byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 3, 0, 0, 0, 0x89.toByte(), 0x47) +
            byteArrayOf(0x11, 0x00, 0x1a, 0x01) +                                      // basic: NH=1 (common)
            byteArrayOf(0x20, 0x50, 0x02, 0x00, 0, (cam.size + 4).toByte(), 0x01, 0) + // common: BTP-B, SHB
            byteArrayOf(0x14, 0, 0, 0, 0, 0, 0, 1) + byteArrayOf(0, 0, 0, 0) +         // GN address, timestamp
            java.nio.ByteBuffer.allocate(12).putInt(482000000).putInt(143000000).putShort(1000).putShort(900).array() +
            ByteArray(4) +                                                             // SHB reserved
            byteArrayOf(0x07, 0xd1.toByte(), 0, 0) + cam                               // BTP-B 2001 + CAM
        val layers = ItsFrameDecoder(::open).decode(frame)
        assertEquals(listOf("802.11", "GeoNetworking basic header", "GeoNetworking common header",
            "GeoNetworking extended header", "BTP-B", "CAM (ITS message, ${cam.size} B)"), layers.map { it.title })
        layers.forEach { assertNull(it.title, it.error) }
        val ext = layers[3].rows.toMap()
        assertEquals("48.2000000°, 14.3000000°", ext["source position"])
        assertEquals("5 (passenger car)", ext["source station type"])

        val text = AsnFormat.tree(layers.last().value!!, "CAM").toString()
        assertTrue(text, text.contains("latitude: 482000000 (48.2000000°)"))
        assertTrue(text, text.contains("stationType: "))
    }
}
