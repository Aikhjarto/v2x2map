package org.opentrafficmap.receiver

import org.opentrafficmap.receiver.asn1.AsnFormat
import org.opentrafficmap.receiver.asn1.AsnValue
import org.opentrafficmap.receiver.asn1.ItsFrameDecoder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object FrameDetailSheet {

    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun show(context: Context, f: Frame) {
        val sheet = BottomSheetDialog(context)
        val view  = LayoutInflater.from(context).inflate(R.layout.dialog_frame_detail, null)
        sheet.setContentView(view)

        view.findViewById<View>(R.id.detailDot)
            .backgroundTintList = android.content.res.ColorStateList.valueOf(f.msgType.color)
        view.findViewById<TextView>(R.id.detailTitle).text = titleFor(context, f)
        view.findViewById<TextView>(R.id.detailSize).text  = "${f.len} B"
        view.findViewById<TextView>(R.id.detailMeta).text  = buildMeta(context, f)
        view.findViewById<TextView>(R.id.detailHex).text   = hexDump(f.payload)
        view.findViewById<TextView>(R.id.detailDecoded).text = decodedText(context, f)

        sheet.show()
    }

    private fun titleFor(ctx: Context, f: Frame): String = when (f.msgType) {
        ItsG5Decoder.MsgType.CAM     -> ctx.getString(R.string.detail_type_cam)
        ItsG5Decoder.MsgType.DENM    -> f.denmCause
            ?.let { "DENM – ${it.label()}" }
            ?: ctx.getString(R.string.detail_type_denm)
        ItsG5Decoder.MsgType.MAPEM   -> ctx.getString(R.string.detail_type_mapem)
        ItsG5Decoder.MsgType.SPATEM  -> ctx.getString(R.string.detail_type_spatem)
        ItsG5Decoder.MsgType.IVIM    -> ctx.getString(R.string.detail_type_ivim)
        ItsG5Decoder.MsgType.SREM    -> ctx.getString(R.string.detail_type_srem)
        ItsG5Decoder.MsgType.SSEM    -> ctx.getString(R.string.detail_type_ssem)
        ItsG5Decoder.MsgType.TLM     -> ctx.getString(R.string.detail_type_tlm)
        ItsG5Decoder.MsgType.RTCMEM  -> ctx.getString(R.string.detail_type_rtcmem)
        ItsG5Decoder.MsgType.UNKNOWN -> when (f.etherType) {
            null   -> ctx.getString(R.string.detail_type_unknown)
            0x8947 -> ctx.getString(R.string.detail_type_itsg5_unknown)
            else   -> "EtherType 0x%04x".format(f.etherType)
        }
    }

    private fun buildMeta(ctx: Context, f: Frame): String {
        val sb = StringBuilder()
        fun row(label: String, value: String) = sb.append("%-14s  %s\n".format(label, value))

        row(ctx.getString(R.string.detail_label_frame), "#${f.seq}")
        // Receive time from the frame itself (the old code showed the time the sheet was opened).
        val received = if (f.receivedMs > 0) dateTimeFmt.format(Date(f.receivedMs))
                       else ctx.getString(R.string.detail_time_unknown)
        val radioTs = if (f.sec < 1_000_000_000L) "  (ESP32 ${f.sec}.%03d s)".format(f.usec / 1000) else ""
        row(ctx.getString(R.string.detail_label_time), received + radioTs)
        row(ctx.getString(R.string.detail_label_ethertype), when (f.etherType) {
            null   -> "—"
            0x8947 -> "0x8947  ITS-G5"
            else   -> "0x%04x".format(f.etherType)
        })

        if (f.stationId != null)
            row(ctx.getString(R.string.detail_label_station), "%016x".format(f.stationId))

        val ll = f.latLon
        if (ll != null) {
            val latStr = "%.6f° %s".format(abs(ll.first),  if (ll.first  >= 0) "N" else "S")
            val lonStr = "%.6f° %s".format(abs(ll.second), if (ll.second >= 0) "E" else "W")
            row(ctx.getString(R.string.detail_label_position), "$latStr   $lonStr")
        } else {
            row(ctx.getString(R.string.detail_label_position), "—")
        }

        if (f.speedMps != null && f.speedMps > 0.0)
            row(ctx.getString(R.string.detail_label_speed), "%.1f km/h".format(f.speedMps * 3.6))
        if (f.headingDeg != null)
            row(ctx.getString(R.string.detail_label_heading),
                "%.1f°  %s".format(f.headingDeg, compassPoint(f.headingDeg)))
        if (f.denmCause != null) {
            val sub = f.denmCause.sublabel()
            val causeStr = "${f.denmCause.causeCode} – ${f.denmCause.label()}" +
                (if (sub.isNotEmpty()) "  $sub" else "") +
                " / ${f.denmCause.subCauseCode}"
            row(ctx.getString(R.string.detail_label_cause), causeStr)
        }

        return sb.toString().trimEnd()
    }

    @Volatile private var decoder: ItsFrameDecoder? = null

    private fun decoder(ctx: Context): ItsFrameDecoder = decoder ?: synchronized(this) {
        decoder ?: ItsFrameDecoder { set -> ctx.applicationContext.assets.open("its_asn1/$set.json") }.also { decoder = it }
    }

    /** All layers of the frame, fully decoded (see ItsFrameDecoder). */
    private fun decodedText(ctx: Context, f: Frame): String = try {
        val sb = StringBuilder()
        for (layer in decoder(ctx).decode(f.payload)) {
            sb.append("── ").append(layer.title).append('\n')
            layer.rows.forEach { (k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n') }
            layer.value?.let { v ->
                v.let { root -> if (root is AsnValue.Seq) root.fields.forEach { (k, x) -> AsnFormat.tree(x, k, sb, 1) }
                                else AsnFormat.tree(root, layer.title, sb, 1) }
            }
            layer.error?.let { sb.append("  ⚠ ").append(it).append('\n') }
            sb.append('\n')
        }
        sb.toString().trimEnd()
    } catch (e: Exception) {
        "⚠ ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun hexDump(data: ByteArray): String {
        val sb = StringBuilder()
        var off = 0
        while (off < data.size) {
            sb.append("%04x  ".format(off))
            // 8 bytes per line so offset, hex and ASCII fit on a phone screen without wrapping
            val end = minOf(off + 8, data.size)
            for (i in off until end) sb.append("%02x ".format(data[i].toInt() and 0xFF))
            repeat(8 - (end - off)) { sb.append("   ") }
            sb.append(" |")
            for (i in off until end) {
                val c = data[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            off = end
        }
        return sb.toString().trimEnd()
    }

    private fun compassPoint(deg: Double): String {
        val idx = (((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
        return arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[idx]
    }
}
