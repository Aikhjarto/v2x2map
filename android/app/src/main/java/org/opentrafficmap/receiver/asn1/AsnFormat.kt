package org.opentrafficmap.receiver.asn1

import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Renders decoded values as an indented, human-readable tree with units for common ITS types. */
object AsnFormat {

    fun tree(v: AsnValue, name: String, sb: StringBuilder = StringBuilder(), depth: Int = 0): StringBuilder {
        val ind = "  ".repeat(depth)
        when (v) {
            is AsnValue.Seq -> {
                sb.append(ind).append(name).append('\n')
                v.fields.forEach { (k, x) -> tree(x, k, sb, depth + 1) }
            }
            is AsnValue.Choice -> {
                if (isLeaf(v.value)) sb.append(ind).append(name).append(": ").append(v.alt).append(" = ").append(leaf(v.value)).append('\n')
                else tree(v.value, "$name: ${v.alt}", sb, depth)
            }
            is AsnValue.SeqOf -> {
                sb.append(ind).append(name).append(" [").append(v.items.size).append("]\n")
                v.items.forEachIndexed { i, x -> tree(x, "#${i + 1}", sb, depth + 1) }
            }
            else -> sb.append(ind).append(name).append(": ").append(leaf(v)).append('\n')
        }
        return sb
    }

    private fun isLeaf(v: AsnValue) = v !is AsnValue.Seq && v !is AsnValue.SeqOf && v !is AsnValue.Choice

    fun leaf(v: AsnValue): String = when (v) {
        is AsnValue.Int -> int(v)
        is AsnValue.Enum -> v.name
        is AsnValue.Bool -> v.v.toString()
        is AsnValue.Null -> "—"
        is AsnValue.Bits -> v.bits + if (v.set.isNotEmpty()) "  (" + v.set.joinToString(", ") + ")" else ""
        is AsnValue.Bytes -> bytes(v.b)
        is AsnValue.Raw -> "undecoded " + bytes(v.b)
        is AsnValue.Str -> "\"${v.s}\""
        else -> ""
    }

    private fun bytes(b: ByteArray): String {
        val shown = b.take(24).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
        return if (b.size > 24) "$shown… (${b.size} B)" else "$shown (${b.size} B)"
    }

    private val ITS_EPOCH_MS = 1072915200000L   // 2004-01-01T00:00:00Z
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun int(v: AsnValue.Int): String {
        val raw = v.v.toString()
        v.named?.let { return "$raw ($it)" }
        val x = v.v
        val unit = when (v.tn) {
            "Latitude", "Longitude", "DeltaLatitude", "DeltaLongitude", "OffsetLL-B12", "OffsetLL-B14",
            "OffsetLL-B16", "OffsetLL-B18", "OffsetLL-B22", "OffsetLL-B24" -> "%.7f°".format(Locale.ROOT, x.toDouble() / 1e7)
            "SpeedValue", "SpeedLimit" -> "%.2f m/s = %.1f km/h".format(Locale.ROOT, x.toDouble() / 100, x.toDouble() * 0.036)
            "Velocity" -> "%.2f m/s = %.1f km/h".format(Locale.ROOT, x.toDouble() * 0.02, x.toDouble() * 0.072)
            "HeadingValue", "WGS84AngleValue", "Wgs84AngleValue", "Angle" -> "%.1f°".format(Locale.ROOT, x.toDouble() / 10)
            "AltitudeValue", "SemiAxisLength", "SemiRangeLength", "DeltaAltitude", "Elevation" -> "%.2f m".format(Locale.ROOT, x.toDouble() / 100)
            "VehicleLengthValue", "VehicleWidth", "VehicleHeight", "PosConfidenceEllipse" -> "%.1f m".format(Locale.ROOT, x.toDouble() / 10)
            "LongitudinalAccelerationValue", "LateralAccelerationValue", "VerticalAccelerationValue",
            "AccelerationValue" -> "%.1f m/s²".format(Locale.ROOT, x.toDouble() / 10)
            "YawRateValue" -> "%.2f °/s".format(Locale.ROOT, x.toDouble() / 100)
            "GenerationDeltaTime", "DSecond", "PathDeltaTime" -> "ms" + if (v.tn == "PathDeltaTime") " ×10" else ""
            "TimestampIts" -> dateFmt.format(Date(ITS_EPOCH_MS + x.toLong()))
            "Time64" -> dateFmt.format(Date(ITS_EPOCH_MS + x.divide(BigInteger.valueOf(1000)).toLong())) + " (TAI)"
            "Time32" -> dateFmt.format(Date(ITS_EPOCH_MS + x.toLong() * 1000)) + " (TAI)"
            "ValidityDuration" -> "s"
            "TimeMark" -> "%.1f s in the hour".format(Locale.ROOT, x.toDouble() / 10)
            "MinuteOfTheYear" -> "minute of the year"
            "StationID", "StationId", "IntersectionID", "RoadSegmentID" -> null
            "Psid" -> PSIDS[x.toInt()]
            else -> null
        } ?: return raw
        return if (unit.firstOrNull()?.isLetter() == true && !unit.contains(' ')) "$raw $unit" else "$raw ($unit)"
    }

    private val PSIDS = mapOf(36 to "CA basic service (CAM)", 37 to "DEN basic service (DENM)", 137 to "TLM (SPATEM)",
        138 to "RLT (MAPEM)", 139 to "IVI (IVIM)", 140 to "TLC request (SREM)", 141 to "GeoNetworking management",
        540801 to "CRL", 541 to "SA (SAEM)", 638 to "VRU (VAM)", 639 to "CP (CPM)", 623 to "secure certificate request")
}
