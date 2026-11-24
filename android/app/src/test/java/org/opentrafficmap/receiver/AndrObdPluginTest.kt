package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opentrafficmap.receiver.obd.AndrObdPlugin

class AndrObdPluginTest {
    @Test fun parsesKeyValue() {
        assertEquals("VEHICLE_SPEED" to "52", AndrObdPlugin.parseData("VEHICLE_SPEED=52"))
        assertEquals("X" to "a=b", AndrObdPlugin.parseData("X=a=b"))   // only the first '=' splits
        assertNull(AndrObdPlugin.parseData("=5"))
        assertNull(AndrObdPlugin.parseData("novalue"))
        assertNull(AndrObdPlugin.parseData(null))
    }
}
