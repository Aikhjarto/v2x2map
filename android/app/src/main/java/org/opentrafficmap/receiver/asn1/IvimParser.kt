package org.opentrafficmap.receiver.asn1

import org.opentrafficmap.receiver.nav.IvimSpeedZone
import org.opentrafficmap.receiver.nav.LatLon

/**
 * Extracts speed-limit zones from a decoded IVIM (ISO TS 19321): ISO 14823 pictogram 557
 * ("maximum speed") with a speed attribute, applied to the GIV part's relevance zones, whose
 * geometry comes from the geographic location container (polylines relative to the reference
 * position, as sent by C-Roads gantries).
 */
object IvimParser {
    private const val ITS_EPOCH_MS = 1072915200000L       // 2004-01-01T00:00:00Z
    private const val PICTOGRAM_MAX_SPEED = 557

    class Result(val iviKey: String, val withdrawn: Boolean, val zones: List<IvimSpeedZone>)

    fun parse(ivim: AsnValue, now: Long): Result? {
        val man = ivim.at("ivi", "mandatory") as? AsnValue.Seq ?: return null
        val provider = (man.at("serviceProviderId", "providerIdentifier") as? AsnValue.Int)?.v ?: return null
        val country = (man.at("serviceProviderId", "countryCode") as? AsnValue.Bits)?.bits ?: ""
        val id = man["iviIdentificationNumber"]?.asLong() ?: return null
        val key = "$country:$provider:$id"
        val status = man["iviStatus"]?.asLong() ?: 0
        if (status == 2L || status == 3L) return Result(key, true, emptyList())   // cancellation / negation
        val validTo = man["validTo"]?.asLong()?.let { ITS_EPOCH_MS + it }

        val containers = (ivim.at("ivi", "optional") as? AsnValue.SeqOf)?.items?.filterIsInstance<AsnValue.Choice>() ?: emptyList()
        val lines = HashMap<Long, List<LatLon>>()
        for (glc in containers.filter { it.alt == "glc" }.map { it.value }) {
            val ref = glc.at("referencePosition") ?: continue
            val origin = latLon(ref) ?: continue
            for (part in (glc.at("parts") as? AsnValue.SeqOf)?.items ?: emptyList()) {
                val zoneId = part.at("zoneId")?.asLong() ?: continue
                val line = part.at("zone", "segment", "line") as? AsnValue.Choice ?: continue
                polyline(line, origin)?.let { lines[zoneId] = it }
            }
        }

        // relevance zone id -> limits (one per GIV part / lane)
        val limits = HashMap<Long, MutableSet<Int>>()
        for (giv in containers.filter { it.alt == "giv" }) {
            for (gp in (giv.value as? AsnValue.SeqOf)?.items ?: emptyList()) {
                val limit = speedLimit(gp) ?: continue
                val zones = (gp.at("relevanceZoneIds") as? AsnValue.SeqOf)?.items?.mapNotNull { it.asLong() } ?: continue
                zones.forEach { limits.getOrPut(it) { HashSet() } += limit }
            }
        }
        val zones = limits.mapNotNull { (zid, set) ->
            val line = lines[zid] ?: return@mapNotNull null
            IvimSpeedZone("$key:$zid", set.min(), set.size > 1, line, now, validTo)
        }
        return Result(key, false, zones)
    }

    private fun speedLimit(gicPart: AsnValue): Int? {
        for (rs in (gicPart.at("roadSignCodes") as? AsnValue.SeqOf)?.items ?: emptyList()) {
            val iso = rs.at("code", "iso14823") ?: continue
            val cat = iso.at("pictogramCode", "pictogramCategoryCode") ?: continue
            val code = (cat.at("nature")?.asLong() ?: 0) * 100 + (cat.at("serialNumber")?.asLong() ?: 0)
            if (code != PICTOGRAM_MAX_SPEED.toLong()) continue
            for (a in (iso.at("attributes") as? AsnValue.SeqOf)?.items ?: emptyList()) {
                val spe = a.at("spe") ?: continue
                val max = spe.at("speedLimitMax")?.asLong() ?: continue
                return when (spe.at("unit")?.asLong() ?: 0) {
                    0L -> max.toInt()                                  // km/h
                    1L -> Math.round(max * 1.609344).toInt()           // mph
                    else -> null
                }
            }
        }
        return null
    }

    private fun latLon(v: AsnValue): LatLon? {
        val lat = v.at("latitude")?.asLong() ?: return null
        val lon = v.at("longitude")?.asLong() ?: return null
        if (lat == 900000001L || lon == 1800000001L) return null
        return LatLon(lat / 1e7, lon / 1e7)
    }

    /** Delta positions are cumulative from the reference position; absolute positions are taken as is. */
    private fun polyline(line: AsnValue.Choice, origin: LatLon): List<LatLon>? {
        val pts = (line.value as? AsnValue.SeqOf)?.items ?: return null
        return when (line.alt) {
            "deltaPositions", "deltaPositionsWithAltitude" -> {
                var lat = origin.lat; var lon = origin.lon
                listOf(origin) + pts.mapNotNull { d ->
                    lat += (d.at("deltaLatitude")?.asLong() ?: return@mapNotNull null) / 1e7
                    lon += (d.at("deltaLongitude")?.asLong() ?: return@mapNotNull null) / 1e7
                    LatLon(lat, lon)
                }
            }
            "absolutePositions", "absolutePositionsWithAltitude" -> pts.mapNotNull { latLon(it) }
            else -> null
        }?.takeIf { it.size >= 2 }
    }
}
