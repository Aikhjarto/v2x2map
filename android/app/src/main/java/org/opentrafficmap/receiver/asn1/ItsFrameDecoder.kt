package org.opentrafficmap.receiver.asn1

import java.io.InputStream
import java.util.Locale

/**
 * Splits a raw 802.11 ITS-G5 frame into its layers and decodes each completely:
 * 802.11 / LLC → GeoNetworking basic header → IEEE 1609.2 envelope (OER, incl. certificate)
 * → GeoNetworking common + extended header → BTP → ITS message (UPER: CAM, DENM, SPATEM,
 * MAPEM, IVIM, SREM, SSEM, RTCMEM, VAM).
 *
 * @param open opens a generated type table by set name ("cam", "denm", "iso", "vam", "sec")
 */
class ItsFrameDecoder(private val open: (String) -> InputStream) {

    class Layer(val title: String, val rows: List<Pair<String, String>> = emptyList(), val value: AsnValue? = null,
                val error: String? = null)

    private val schemas = HashMap<String, AsnSchema>()
    private fun schema(set: String) = synchronized(schemas) {
        schemas.getOrPut(set) { open(set).use { AsnSchema.load(set, it) } }
    }

    fun decode(p: ByteArray): List<Layer> {
        val out = ArrayList<Layer>()
        val llc = (0 until p.size - 8).firstOrNull { i ->
            p[i] == 0xAA.toByte() && p[i + 1] == 0xAA.toByte() && p[i + 2] == 0x03.toByte() &&
                p[i + 6] == 0x89.toByte() && p[i + 7] == 0x47.toByte()
        } ?: return listOf(Layer("802.11", error = "no ITS-G5 (EtherType 0x8947) payload"))

        out += Layer("802.11", listOf(
            "frame control" to "%02x %02x".format(Locale.ROOT, u(p, 0), u(p, 1)),
            "receiver" to mac(p, 4), "transmitter" to mac(p, 10), "BSSID" to mac(p, 16),
            "header length" to "$llc B"))

        val gn = llc + 8
        val nh = u(p, gn) and 0x0F
        out += Layer("GeoNetworking basic header", listOf(
            "version" to (u(p, gn) shr 4).toString(),
            "next header" to when (nh) { 1 -> "1 (common header)"; 2 -> "2 (secured packet)"; else -> nh.toString() },
            "lifetime" to lifetime(u(p, gn + 2)),
            "remaining hops" to u(p, gn + 3).toString()))

        var inner = gn + 4
        var innerEnd = p.size
        if (nh == 2) {
            val env = p.copyOfRange(gn + 4, p.size)
            try {
                val (v, _) = OerDecoder(schema("sec")).decodeWithLength("Ieee1609Dot2Data", env)
                out += Layer("Security (IEEE 1609.2 / ETSI TS 103 097)", value = v)
                val payload = (v.at("content", "signedData", "tbsData", "payload", "data", "content", "unsecuredData")
                    ?: v.at("content", "unsecuredData")) as? AsnValue.Bytes
                    ?: return out.also { it += Layer("Payload", error = "no unsecured payload (encrypted?)") }
                val off = indexOf(p, payload.b, gn + 4)
                if (off < 0) return out
                inner = off; innerEnd = off + payload.b.size
            } catch (e: Exception) {
                out += Layer("Security (IEEE 1609.2)", error = "${e.javaClass.simpleName}: ${e.message}")
                return out
            }
        }

        if (innerEnd - inner < 8) return out
        val ht = u(p, inner + 1) shr 4
        val hst = u(p, inner + 1) and 0x0F
        val plen = (u(p, inner + 4) shl 8) or u(p, inner + 5)
        out += Layer("GeoNetworking common header", listOf(
            "next header" to when (u(p, inner) shr 4) { 1 -> "BTP-A"; 2 -> "BTP-B"; 3 -> "IPv6"; else -> "any" },
            "header type" to headerType(ht, hst),
            "traffic class" to "0x%02x".format(Locale.ROOT, u(p, inner + 2)),
            "flags" to "0x%02x".format(Locale.ROOT, u(p, inner + 3)),
            "payload length" to "$plen B",
            "max hop limit" to u(p, inner + 6).toString()))

        val ext = inner + 8
        val (extLen, rows) = extHeader(p, ext, ht, hst)
        if (rows.isNotEmpty()) out += Layer("GeoNetworking extended header", rows)
        val btp = ext + extLen
        if (btp + 4 > innerEnd) return out
        val port = (u(p, btp) shl 8) or u(p, btp + 1)
        val info = (u(p, btp + 2) shl 8) or u(p, btp + 3)
        out += Layer("BTP-B", listOf("destination port" to "$port (${portName(port) ?: "unknown"})",
            "destination port info" to info.toString()))

        val pdu = p.copyOfRange(btp + 4, innerEnd)
        val (set, root) = when (port) {
            2001 -> "cam" to "CAM"; 2002 -> "denm" to "DENM"; 2003 -> "iso" to "MAPEM"; 2004 -> "iso" to "SPATEM"
            2006 -> "iso" to "IVIM"; 2007 -> "iso" to "SREM"; 2008 -> "iso" to "SSEM"; 2013 -> "iso" to "RTCMEM"
            2018 -> "vam" to "VAM"; else -> return out
        }
        out += try {
            Layer("$root (ITS message, ${pdu.size} B)", value = UperDecoder(schema(set)).decode(root, pdu))
        } catch (e: Exception) {
            Layer(root, error = "${e.javaClass.simpleName}: ${e.message}")
        }
        return out
    }

    // ---- GeoNetworking extended headers (EN 302 636-4-1) --------------------------------------

    private fun extHeader(p: ByteArray, e: Int, ht: Int, hst: Int): Pair<Int, List<Pair<String, String>>> = when (ht) {
        5 -> if (hst == 0) 28 to lpv(p, e, "source") + listOf("reserved" to hex(p, e + 24, 4))      // SHB
             else 28 to listOf("sequence number" to u16(p, e).toString()) + lpv(p, e + 4, "source")   // TSB
        4, 3 -> 44 to listOf("sequence number" to u16(p, e).toString()) + lpv(p, e + 4, "source") + listOf(
            "area centre" to latLon(p, e + 28),
            "area distance a / b" to "${u16(p, e + 36)} m / ${u16(p, e + 38)} m",
            "area angle" to "${u16(p, e + 40)}°",
            "area shape" to when (hst) { 0 -> "circle"; 1 -> "rectangle"; 2 -> "ellipse"; else -> hst.toString() })
        2 -> 48 to listOf("sequence number" to u16(p, e).toString()) + lpv(p, e + 4, "source")         // GUC
        1 -> 24 to lpv(p, e, "source")                                                                   // beacon
        else -> 0 to emptyList()
    }

    /** Long position vector: GN address, timestamp, position, speed, heading. */
    private fun lpv(p: ByteArray, o: Int, who: String): List<Pair<String, String>> {
        if (o + 24 > p.size) return emptyList()
        val st = (u(p, o) shr 2) and 0x1F
        val speedRaw = ((u(p, o + 20) and 0x7F) shl 8) or u(p, o + 21)
        val speed = (if (speedRaw and 0x4000 != 0) speedRaw - 0x8000 else speedRaw) / 100.0
        return listOf(
            "$who GN address" to "%016x".format(Locale.ROOT, java.math.BigInteger(1, p.copyOfRange(o, o + 8))),
            "$who station type" to "$st (${STATION_TYPES[st] ?: "?"})",
            "$who timestamp" to "${(u(p, o + 8).toLong() shl 24 or (u(p, o + 9).toLong() shl 16) or (u(p, o + 10).toLong() shl 8) or u(p, o + 11).toLong())} ms (mod 2^32)",
            "$who position" to latLon(p, o + 12),
            "$who position accurate" to if (u(p, o + 20) and 0x80 != 0) "yes" else "no",
            "$who speed" to "%.2f m/s (%.1f km/h)".format(Locale.ROOT, speed, speed * 3.6),
            "$who heading" to "%.1f°".format(Locale.ROOT, u16(p, o + 22) / 10.0))
    }

    private fun latLon(p: ByteArray, o: Int): String {
        val lat = java.nio.ByteBuffer.wrap(p, o, 4).int / 1e7
        val lon = java.nio.ByteBuffer.wrap(p, o + 4, 4).int / 1e7
        return "%.7f°, %.7f°".format(Locale.ROOT, lat, lon)
    }

    private fun headerType(ht: Int, hst: Int) = when (ht) {
        1 -> "1 beacon"; 2 -> "2 GeoUnicast"
        3 -> "3 GeoAnycast (${listOf("circle", "rectangle", "ellipse").getOrElse(hst) { "$hst" }})"
        4 -> "4 GeoBroadcast (${listOf("circle", "rectangle", "ellipse").getOrElse(hst) { "$hst" }})"
        5 -> if (hst == 0) "5/0 single-hop broadcast (SHB)" else "5/$hst topologically-scoped broadcast (TSB)"
        6 -> "6 location service"; else -> "$ht/$hst"
    }

    private fun lifetime(b: Int): String {
        val mult = b shr 2
        val base = when (b and 3) { 0 -> 0.05; 1 -> 1.0; 2 -> 10.0; else -> 100.0 }
        return "%.2f s".format(Locale.ROOT, mult * base)
    }

    companion object {
        val STATION_TYPES = mapOf(0 to "unknown", 1 to "pedestrian", 2 to "cyclist", 3 to "moped", 4 to "motorcycle",
            5 to "passenger car", 6 to "bus", 7 to "light truck", 8 to "heavy truck", 9 to "trailer",
            10 to "special vehicle", 11 to "tram", 15 to "roadside unit")

        fun portName(port: Int) = mapOf(2001 to "CAM", 2002 to "DENM", 2003 to "MAPEM", 2004 to "SPATEM",
            2005 to "SAEM", 2006 to "IVIM", 2007 to "SREM", 2008 to "SSEM", 2009 to "CPM", 2010 to "EVCSN POI",
            2011 to "TPG", 2012 to "Charging", 2013 to "RTCMEM", 2014 to "CTL", 2015 to "CRL", 2016 to "EC/AT request",
            2017 to "MCDM", 2018 to "VAM", 2019 to "IMZM")[port]

        private fun u(p: ByteArray, i: Int) = if (i < p.size) p[i].toInt() and 0xFF else 0
        private fun u16(p: ByteArray, i: Int) = (u(p, i) shl 8) or u(p, i + 1)
        private fun mac(p: ByteArray, o: Int) = (o until o + 6).joinToString(":") { "%02x".format(Locale.ROOT, u(p, it)) }
        private fun hex(p: ByteArray, o: Int, n: Int) = (o until o + n).joinToString("") { "%02x".format(Locale.ROOT, u(p, it)) }

        private fun indexOf(p: ByteArray, needle: ByteArray, from: Int): Int {
            if (needle.isEmpty()) return -1
            outer@ for (i in from..p.size - needle.size) {
                for (j in needle.indices) if (p[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
