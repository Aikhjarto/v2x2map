package org.opentrafficmap.receiver.asn1

import java.math.BigInteger

/**
 * Generic ASN.1 Unaligned PER (X.691, UPER) decoder driven by an [AsnSchema]. Used for the ETSI
 * and ISO ITS messages (CAM, DENM, SPATEM, MAPEM, IVIM, SREM, SSEM, RTCMEM, VAM).
 */
class UperDecoder(private val schema: AsnSchema) {

    fun decode(root: String, data: ByteArray): AsnValue {
        val tid = schema.roots[root] ?: throw AsnDecodeException("no type $root in ${schema.name}")
        return value(tid, BitReader(data))
    }

    private class BitReader(val b: ByteArray) {
        var pos = 0
        fun bit(): Int {
            if (pos >= b.size * 8) throw AsnDecodeException("end of data at bit $pos")
            val v = (b[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++; return v
        }
        fun bits(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or bit().toLong() }; return v }
        fun bigBits(n: Int): BigInteger {
            if (n <= 62) return BigInteger.valueOf(bits(n))
            var v = BigInteger.ZERO; repeat(n) { v = v.shiftLeft(1).or(BigInteger.valueOf(bit().toLong())) }; return v
        }
        fun bytes(n: Int): ByteArray = ByteArray(n) { bits(8).toByte() }
    }

    // ---- primitives -------------------------------------------------------------------------

    /** General length determinant (X.691 11.9, unconstrained). Fragmented lengths are rejected. */
    private fun length(r: BitReader): Int = when {
        r.bit() == 0 -> r.bits(7).toInt()
        r.bit() == 0 -> r.bits(14).toInt()
        else -> throw AsnDecodeException("fragmented length not supported")
    }

    private fun normallySmall(r: BitReader): Int = if (r.bit() == 0) r.bits(6).toInt() else {
        val n = length(r); BigInteger(1, r.bytes(n)).toInt()
    }

    private fun constrained(r: BitReader, lb: BigInteger, ub: BigInteger): BigInteger {
        val range = ub.subtract(lb)
        return if (range.signum() == 0) lb else lb.add(r.bigBits(range.bitLength()))
    }

    /** Length of a size-constrained item (strings, bit/octet strings, SEQUENCE OF). */
    private fun sizedLength(r: BitReader, lb: Long?, ub: Long?, ext: Boolean): Int {
        if (ext && r.bit() == 1) return length(r)
        if (ub != null && ub < 65536) {
            val lo = lb ?: 0
            return if (lo == ub) ub.toInt() else (lo + r.bits((ub - lo).toBigInteger().bitLength())).toInt()
        }
        return length(r)
    }

    private fun openType(r: BitReader): ByteArray = r.bytes(length(r))

    private fun Long.toBigInteger(): BigInteger = BigInteger.valueOf(this)

    // ---- values -----------------------------------------------------------------------------

    private fun value(tid: Int, r: BitReader): AsnValue = when (val n = schema.node(tid)) {
        is AsnSchema.Seq -> seq(n, r)
        is AsnSchema.Choice -> choice(n, r)
        is AsnSchema.IntT -> int(n, r)
        is AsnSchema.EnumT -> enumV(n, r)
        is AsnSchema.BoolT -> AsnValue.Bool(r.bit() == 1, n.tn)
        is AsnSchema.NullT -> AsnValue.Null(n.tn)
        is AsnSchema.OpenT -> AsnValue.Raw(openType(r), n.tn)
        is AsnSchema.BitsT -> {
            val len = sizedLength(r, n.lb, n.ub, n.ext)
            val sb = StringBuilder(len); repeat(len) { sb.append(if (r.bit() == 1) '1' else '0') }
            AsnValue.Bits(sb.toString(), sb.indices.filter { sb[it] == '1' }.mapNotNull { n.named[it] }, n.tn)
        }
        is AsnSchema.OctT -> AsnValue.Bytes(r.bytes(sizedLength(r, n.lb, n.ub, n.ext)), n.tn)
        is AsnSchema.StrT -> str(n, r)
        is AsnSchema.SeqOfT -> {
            val count = sizedLength(r, n.lb, n.ub, n.ext)
            AsnValue.SeqOf(List(count) { value(n.elem, r) }, n.tn)
        }
    }

    private fun seq(n: AsnSchema.Seq, r: BitReader): AsnValue.Seq {
        val extended = n.ext && r.bit() == 1
        val out = ArrayList<Pair<String, AsnValue>>()
        fields(n.root, n.optCount, r, out)
        if (extended) {
            val count = normallySmall(r) + 1
            val present = List(count) { r.bit() == 1 }
            for (i in 0 until count) {
                if (!present[i]) continue
                val bytes = openType(r)
                when (val item = n.extNest.getOrNull(i)) {
                    is AsnSchema.ExtSingle -> out += item.name to sub(item.tid, bytes)
                    is AsnSchema.ExtGroup -> out += subGroup(item, bytes)
                    null -> out += "_ext_$i" to AsnValue.Raw(bytes, "")
                }
            }
        }
        return AsnValue.Seq(out, n.tn)
    }

    private fun fields(root: List<AsnSchema.Field>, optCount: Int, r: BitReader, out: MutableList<Pair<String, AsnValue>>) {
        val present = List(optCount) { r.bit() == 1 }
        var oi = 0
        for (f in root) {
            if (f.optional && !present[oi++]) continue
            out += f.name to value(f.tid, r)
        }
    }

    private fun sub(tid: Int, bytes: ByteArray): AsnValue =
        try { value(tid, BitReader(bytes)) } catch (e: AsnDecodeException) { AsnValue.Raw(bytes, schema.node(tid).tn) }

    private fun subGroup(g: AsnSchema.ExtGroup, bytes: ByteArray): List<Pair<String, AsnValue>> {
        val out = ArrayList<Pair<String, AsnValue>>()
        fields(g.fields, g.fields.count { it.optional }, BitReader(bytes), out)
        return out
    }

    private fun choice(n: AsnSchema.Choice, r: BitReader): AsnValue {
        if (n.ext && r.bit() == 1) {
            val idx = normallySmall(r)
            val bytes = openType(r)
            val alt = n.extAlts.getOrNull(idx) ?: return AsnValue.Choice("_ext_$idx", AsnValue.Raw(bytes, ""), n.tn)
            return AsnValue.Choice(alt.name, sub(alt.tid, bytes), n.tn)
        }
        val idx = if (n.root.size == 1) 0 else r.bits((n.root.size - 1).toBigInteger().bitLength()).toInt()
        val alt = n.root.getOrNull(idx) ?: throw AsnDecodeException("${n.tn}: invalid choice index $idx")
        return AsnValue.Choice(alt.name, value(alt.tid, r), n.tn)
    }

    private fun Int.toBigInteger() = BigInteger.valueOf(this.toLong())

    private fun int(n: AsnSchema.IntT, r: BitReader): AsnValue {
        val v: BigInteger = when {
            n.ext && r.bit() == 1 -> BigInteger(r.bytes(length(r)))                 // outside the root range
            n.lb != null && n.ub != null -> constrained(r, n.lb, n.ub)
            n.lb != null -> n.lb.add(BigInteger(1, r.bytes(length(r))))               // semi-constrained
            else -> BigInteger(r.bytes(length(r)))                                     // unconstrained, 2's complement
        }
        return AsnValue.Int(v, n.named[v], n.tn)
    }

    private fun enumV(n: AsnSchema.EnumT, r: BitReader): AsnValue {
        if (n.ext && r.bit() == 1) {
            val idx = normallySmall(r)
            return AsnValue.Enum(n.extItems.getOrNull(idx)?.first ?: "_ext_$idx", n.tn)
        }
        val idx = if (n.root.size <= 1) 0 else r.bits((n.root.size - 1).toBigInteger().bitLength()).toInt()
        return AsnValue.Enum(n.root.getOrNull(idx)?.first ?: throw AsnDecodeException("${n.tn}: enum index $idx"), n.tn)
    }

    private fun str(n: AsnSchema.StrT, r: BitReader): AsnValue {
        if (n.kind == "UTF8") return AsnValue.Str(String(r.bytes(length(r)), Charsets.UTF_8), n.tn)
        val len = sizedLength(r, n.lb, n.ub, n.ext)
        val sb = StringBuilder(len)
        repeat(len) {
            sb.append(when (n.kind) {
                "Numeric" -> " 0123456789".getOrElse(r.bits(4).toInt()) { '?' }
                "BMP" -> r.bits(16).toInt().toChar()
                "Universal" -> String(Character.toChars(r.bits(32).toInt()))
                else -> r.bits(7).toInt().toChar()                                       // IA5 / Visible / Printable
            })
        }
        return AsnValue.Str(sb.toString(), n.tn)
    }
}
