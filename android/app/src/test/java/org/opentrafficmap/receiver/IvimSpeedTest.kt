package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.asn1.AsnSchema
import org.opentrafficmap.receiver.asn1.IvimParser
import org.opentrafficmap.receiver.asn1.UperDecoder
import org.opentrafficmap.receiver.nav.IvimSpeed
import org.opentrafficmap.receiver.nav.IvimSpeedZone
import org.opentrafficmap.receiver.nav.LatLon
import java.io.File

class IvimSpeedTest {

    // relevance zone heading west (bearing ~270°), 1 km long
    private val westward = listOf(LatLon(48.2, 14.30), LatLon(48.2, 14.293), LatLon(48.2, 14.2865))
    private fun zone(limit: Int, differ: Boolean = false, line: List<LatLon> = westward) =
        IvimSpeedZone("AT:1:1:$limit", limit, differ, line, 0L, null)

    @Test fun insideZoneDrivingInItsDirection() {
        val hit = IvimSpeed.limitAt(listOf(zone(80)), LatLon(48.20005, 14.296), 268.0)
        assertNotNull(hit); assertEquals(80, hit!!.limitKph); assertFalse(hit.lanesDiffer)
    }

    @Test fun otherCarriagewayAndOutsideAreIgnored() {
        assertNull("opposite direction", IvimSpeed.limitAt(listOf(zone(80)), LatLon(48.2, 14.296), 90.0))
        assertNull("40 m beside the zone", IvimSpeed.limitAt(listOf(zone(80)), LatLon(48.20036, 14.296), 270.0))
        assertNull("before the gantry", IvimSpeed.limitAt(listOf(zone(80)), LatLon(48.2, 14.31), 270.0))
        assertNull("no heading", IvimSpeed.limitAt(listOf(zone(80)), LatLon(48.2, 14.296), null))
    }

    @Test fun overlappingZonesGiveLowestAndFlagDifference() {
        val hit = IvimSpeed.limitAt(listOf(zone(80), zone(60)), LatLon(48.2, 14.296), 270.0)!!
        assertEquals(60, hit.limitKph); assertTrue(hit.lanesDiffer)
        assertTrue(IvimSpeed.limitAt(listOf(zone(100, differ = true)), LatLon(48.2, 14.296), 270.0)!!.lanesDiffer)
    }

    @Test fun parsesSpeedZonesFromRealIvimStructure() {
        val line = javaClass.classLoader!!.getResource("its_vectors.tsv")!!.readText().lines().first { it.startsWith("IVIM\t") }
        val hex = line.split('\t')[1]
        val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val schema = AsnSchema.load("iso", File("src/main/assets/its_asn1/iso.json").inputStream())
        val r = IvimParser.parse(UperDecoder(schema).decode("IVIM", bytes), now = 1000L)
        assertNotNull(r); assertFalse(r!!.withdrawn)
        assertTrue("zones: ${r.zones}", r.zones.isNotEmpty())
        r.zones.forEach { z ->
            assertTrue("limit ${z.limitKph}", z.limitKph in setOf(60, 80, 100))
            assertTrue(z.key.startsWith(r.iviKey + ":"))
            assertEquals(48.2, z.line.first().lat, 1e-9)                // anonymised reference position
        }
    }
}
