package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.receiver.asn1.AsnJson
import org.opentrafficmap.receiver.asn1.AsnSchema
import org.opentrafficmap.receiver.asn1.OerDecoder
import org.opentrafficmap.receiver.asn1.UperDecoder
import java.io.File

/**
 * Compares the generic decoder with pycrate on real recordings. Cases are produced by
 * tools/asn1gen/compare_pycrate.py (they contain real positions — never commit them):
 *     PYCRATE_CASES=/path/cases.tsv ./gradlew testDebugUnitTest --tests '*DecoderVsPycrate*'
 * Without the variable the test does nothing.
 */
class DecoderVsPycrateTest {

    @Test fun matchesPycrate() {
        val cases = File(System.getenv("PYCRATE_CASES") ?: return)
        val assets = File("src/main/assets/its_asn1")
        val schemas = listOf("cam", "denm", "iso", "vam", "sec").associateWith { AsnSchema.load(it, File(assets, "$it.json").inputStream()) }
        val setOf = mapOf("CAM" to "cam", "DENM" to "denm", "VAM" to "vam", "Ieee1609Dot2Data" to "sec")
        var ok = 0
        val failures = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        cases.forEachLine { line ->
            val (root, hex, expected) = line.split('\t', limit = 3)
            val data = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            val schema = schemas.getValue(setOf[root] ?: "iso")
            val got = try {
                val v = if (root == "Ieee1609Dot2Data") OerDecoder(schema).decode(root, data) else UperDecoder(schema).decode(root, data)
                // pycrate represents an ASN.1 NULL value as 0
                AsnJson.toJson(v).replace(":null", ":0").replace("[null", "[0").replace(",null", ",0")
            } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
            if (got == expected) ok++ else {
                failures[root] = (failures[root] ?: 0) + 1
                val at = expected.indices.firstOrNull { it >= got.length || got[it] != expected[it] } ?: expected.length
                val from = maxOf(0, at - 250)
                if (examples.size < 3) examples += "$root at char $at\n  expected …${expected.substring(from, minOf(expected.length, at + 250))}\n  got      …${got.substring(minOf(from, got.length), minOf(got.length, at + 250))}"
            }
        }
        println("DECODER-VS-PYCRATE ok=$ok failures=$failures")
        examples.forEach { println("DIFF $it") }
        assertEquals("mismatches: $failures", 0, failures.values.sum())
    }
}
