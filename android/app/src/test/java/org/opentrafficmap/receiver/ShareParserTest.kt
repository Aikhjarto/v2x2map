package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.ShareParser
import org.opentrafficmap.receiver.nav.ShareParser.Result

class ShareParserTest {

    private fun coords(r: Result?): Result.Coords {
        assertTrue("expected Coords, got $r", r is Result.Coords)
        return r as Result.Coords
    }

    private fun near(c: Result.Coords, lat: Double, lon: Double) {
        assertEquals(lat, c.pos.lat, 1e-6); assertEquals(lon, c.pos.lon, 1e-6)
    }

    @Test fun geoUriWithCoords() {
        val c = coords(ShareParser.parse("geo:48.2085,16.3731?z=17"))
        near(c, 48.2085, 16.3731)
    }

    @Test fun geoUriWithLabelInQuery() {
        val c = coords(ShareParser.parse("geo:0,0?q=48.2085,16.3731(Stephansdom)"))
        near(c, 48.2085, 16.3731); assertEquals("Stephansdom", c.name)
    }

    @Test fun geoUriWithAddressQuery() {
        val r = ShareParser.parse("geo:0,0?q=Stephansplatz+3%2C+Wien")
        assertEquals(Result.Query(listOf("Stephansplatz 3, Wien")), r)
    }

    @Test fun googlePlaceUrlPrefersExactPinOverViewport() {
        val c = coords(ShareParser.parse("https://www.google.com/maps/place/Stephansdom/@48.2080,16.3700,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d48.2085!4d16.3731"))
        near(c, 48.2085, 16.3731); assertEquals("Stephansdom", c.name)
    }

    @Test fun googleShareTextWithShortLink() {
        val r = ShareParser.parse("Stephansdom\nhttps://maps.app.goo.gl/AbCdEf123")
        assertEquals(Result.ShortLink("https://maps.app.goo.gl/AbCdEf123", "Stephansdom"), r)
    }

    @Test fun consentRedirectIsUnwrapped() {
        val c = coords(ShareParser.parseUrl(
            "https://consent.google.com/m?continue=https://www.google.com/maps/place/Prater/%4048.21,16.40,15z/data%3D!3d48.2167!4d16.3958&gl=AT", null))
        near(c, 48.2167, 16.3958); assertEquals("Prater", c.name)
    }

    @Test fun googleQueryAddressBecomesSearch() {
        assertEquals(Result.Query(listOf("Mariahilfer Straße 1, Wien")),
            ShareParser.parse("https://maps.google.com/?q=Mariahilfer+Stra%C3%9Fe+1,+Wien"))
    }

    @Test fun openStreetMapMarkerAndFragment() {
        near(coords(ShareParser.parse("https://www.openstreetmap.org/?mlat=48.2085&mlon=16.3731#map=17/48.2/16.3")), 48.2085, 16.3731)
        near(coords(ShareParser.parse("https://www.openstreetmap.org/#map=17/48.20850/16.37310")), 48.2085, 16.3731)
    }

    @Test fun osmandShareText() {
        val c = coords(ShareParser.parse("Wiener Riesenrad\nhttps://osmand.net/map?pin=48.21661,16.39590#16/48.2166/16.3959"))
        near(c, 48.21661, 16.39590); assertEquals("Wiener Riesenrad", c.name)
        near(coords(ShareParser.parse("https://osmand.net/go?lat=48.2166&lon=16.3959&z=16")), 48.2166, 16.3959)
        near(coords(ShareParser.parse("https://osmand.net/map#16/48.2166/16.3959")), 48.2166, 16.3959)
    }

    @Test fun plainCoordinates() {
        near(coords(ShareParser.parse("Treffpunkt: 48.20850, 16.37310")), 48.2085, 16.3731)
    }

    @Test fun addressTextFallsBackToSearchCandidates() {
        val r = ShareParser.parse("Café Central\nHerrengasse 14, 1010 Wien")
        assertEquals(Result.Query(listOf("Café Central, Herrengasse 14, 1010 Wien", "Herrengasse 14, 1010 Wien", "Café Central")), r)
    }

    @Test fun emptyAndInvalid() {
        assertNull(ShareParser.parse("   "))
        assertNull(ShareParser.parseUrl("https://example.com/page", null))
        assertTrue(ShareParser.parse("geo:0,0") !is Result.Coords)
    }
}
