package org.opentrafficmap.receiver.asn1

import java.math.BigInteger

/**
 * Generic ASN.1 Octet Encoding Rules (X.696, canonical OER) decoder driven by an [AsnSchema].
 * Used for the IEEE 1609.2 / ETSI TS 103 097 security envelope (signatures, certificates).
 */
class OerDecoder(private val schema: AsnSchema) {

    fun decode(root: String, data: ByteArray): AsnValue {
        val tid = schema.roots[root] ?: throw AsnDecodeException("no type $root in ${schema.name}")
        return value(tid, Reader(data))
    }

    /** Like [decode] but also returns how many bytes the value occupied. */
    fun decodeWithLength(root: String, data: ByteArray): Pair<AsnValue, Int> {
        val r = Reader(data)
        val v = value(schema.roots[root] ?: throw AsnDecodeException("no type $root"), r)
        return v to r.pos
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int { if (pos >= b.size) throw AsnDecodeException("end of data at byte $pos"); return b[pos++].toInt() and 0xFF }
        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > b.size) throw AsnDecodeException("need $n bytes at $pos, have ${b.size - pos}")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    private fun length(r: Reader): Int {
        val first = r.u8()
        if (first < 0x80) return first
        val n = first and 0x7F
        if (n == 0 || n > 4) throw AsnDecodeException("bad length form $first")
        return BigInteger(1, r.bytes(n)).toInt()
    }

    private val U8 = BigInteger.valueOf(255); private val U16 = BigInteger.valueOf(65535)
    private val U32 = BigInteger.valueOf(4294967295L); private val U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    private fun fixedUnsignedBytes(ub: BigInteger): Int? = when {
        ub <= U8 -> 1; ub <= U16 -> 2; ub <= U32 -> 4; ub <= U64 -> 8; else -> null
    }

    private fun fixedSignedBytes(lb: BigInteger, ub: BigInteger): Int? {
        for (n in intArrayOf(1, 2, 4, 8)) {
            val max = BigInteger.ONE.shiftLeft(8 * n - 1)
            if (lb >= max.negate() && ub < max) return n
        }
        return null
    }

    private fun value(tid: Int, r: Reader): AsnValue = when (val n = schema.node(tid)) {
        is AsnSchema.Seq -> seq(n, r)
        is AsnSchema.Choice -> choice(n, r)
        is AsnSchema.IntT -> int(n, r)
        is AsnSchema.EnumT -> {
            val first = r.u8()
            val v = if (first < 0x80) first.toLong() else BigInteger(r.bytes(first and 0x7F)).toLong()
            val name = (n.root + n.extItems).firstOrNull { it.second == v }?.first ?: "_value_$v"
            AsnValue.Enum(name, n.tn)
        }
        is AsnSchema.BoolT -> AsnValue.Bool(r.u8() != 0, n.tn)
        is AsnSchema.NullT -> AsnValue.Null(n.tn)
        is AsnSchema.OpenT -> AsnValue.Raw(r.bytes(length(r)), n.tn)
        is AsnSchema.BitsT -> bits(n, r)
        is AsnSchema.OctT -> AsnValue.Bytes(
            if (!n.ext && n.lb != null && n.lb == n.ub) r.bytes(n.lb.toInt()) else r.bytes(length(r)), n.tn)
        is AsnSchema.StrT -> {
            val per = when (n.kind) { "BMP" -> 2; "Universal" -> 4; else -> 1 }
            val fixed = n.kind != "UTF8" && !n.ext && n.lb != null && n.lb == n.ub
            val raw = if (fixed) r.bytes(n.lb!!.toInt() * per) else r.bytes(length(r))
            AsnValue.Str(when (n.kind) {
                "BMP" -> String(raw, Charsets.UTF_16BE); "Universal" -> String(raw, charset("UTF-32BE"))
                "UTF8" -> String(raw, Charsets.UTF_8); else -> String(raw, Charsets.US_ASCII)
            }, n.tn)
        }
        is AsnSchema.SeqOfT -> {
            val qBytes = r.u8()
            val count = BigInteger(1, r.bytes(qBytes)).toInt()
            AsnValue.SeqOf(List(count) { value(n.elem, r) }, n.tn)
        }
    }

    private fun bits(n: AsnSchema.BitsT, r: Reader): AsnValue {
        val s: String = if (!n.ext && n.lb != null && n.lb == n.ub) {
            val len = n.lb.toInt()
            val raw = r.bytes((len + 7) / 8)
            (0 until len).joinToString("") { i -> if ((raw[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) "1" else "0" }
        } else {
            val l = length(r)
            val unused = r.u8()
            val raw = r.bytes(l - 1)
            (0 until (l - 1) * 8 - unused).joinToString("") { i -> if ((raw[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) "1" else "0" }
        }
        return AsnValue.Bits(s, s.indices.filter { s[it] == '1' }.mapNotNull { n.named[it] }, n.tn)
    }

    private fun int(n: AsnSchema.IntT, r: Reader): AsnValue {
        val v: BigInteger = when {
            !n.ext && n.lb != null && n.lb.signum() >= 0 && n.ub != null && fixedUnsignedBytes(n.ub) != null ->
                BigInteger(1, r.bytes(fixedUnsignedBytes(n.ub)!!))
            !n.ext && n.lb != null && n.lb.signum() < 0 && n.ub != null && fixedSignedBytes(n.lb, n.ub) != null ->
                BigInteger(r.bytes(fixedSignedBytes(n.lb, n.ub)!!))
            !n.ext && n.lb != null && n.lb.signum() >= 0 -> BigInteger(1, r.bytes(length(r)))
            else -> BigInteger(r.bytes(length(r)))
        }
        return AsnValue.Int(v, n.named[v], n.tn)
    }

    private fun preamble(r: Reader, nbits: Int): List<Boolean> {
        if (nbits == 0) return emptyList()
        val raw = r.bytes((nbits + 7) / 8)
        return List(nbits) { i -> (raw[i / 8].toInt() shr (7 - i % 8)) and 1 == 1 }
    }

    private fun seq(n: AsnSchema.Seq, r: Reader): AsnValue.Seq {
        val pre = preamble(r, (if (n.ext) 1 else 0) + n.optCount)
        val extended = n.ext && pre[0]
        var oi = if (n.ext) 1 else 0
        val out = ArrayList<Pair<String, AsnValue>>()
        for (f in n.root) {
            if (f.optional && !pre[oi++]) continue
            out += f.name to value(f.tid, r)
        }
        if (extended) {
            val l = length(r)
            val unused = r.u8()
            val raw = r.bytes(l - 1)
            val count = (l - 1) * 8 - unused
            for (i in 0 until count) {
                if ((raw[i / 8].toInt() shr (7 - i % 8)) and 1 == 0) continue
                val bytes = r.bytes(length(r))
                when (val item = n.extNest.getOrNull(i)) {
                    is AsnSchema.ExtSingle -> out += item.name to sub(item.tid, bytes)
                    is AsnSchema.ExtGroup -> {
                        val gr = Reader(bytes)
                        val gp = preamble(gr, item.fields.count { it.optional })
                        var gi = 0
                        for (f in item.fields) { if (f.optional && !gp[gi++]) continue; out += f.name to value(f.tid, gr) }
                    }
                    null -> out += "_ext_$i" to AsnValue.Raw(bytes, "")
                }
            }
        }
        return AsnValue.Seq(out, n.tn)
    }

    private fun sub(tid: Int, bytes: ByteArray): AsnValue =
        try { value(tid, Reader(bytes)) } catch (e: AsnDecodeException) { AsnValue.Raw(bytes, schema.node(tid).tn) }

    private fun choice(n: AsnSchema.Choice, r: Reader): AsnValue {
        val t = r.u8()
        val cls = t ushr 6
        var num = t and 0x3F
        if (num == 0x3F) {                                    // tag number >= 63: base-128 continuation
            num = 0
            do { val b = r.u8(); num = (num shl 7) or (b and 0x7F) } while (b and 0x80 != 0)
        }
        n.root.firstOrNull { it.tagClass == cls && it.tagNum == num }?.let { return AsnValue.Choice(it.name, value(it.tid, r), n.tn) }
        val bytes = r.bytes(length(r))                        // extension alternative: open type
        val alt = n.extAlts.firstOrNull { it.tagClass == cls && it.tagNum == num }
            ?: return AsnValue.Choice("_ext_tag_$num", AsnValue.Raw(bytes, ""), n.tn)
        return AsnValue.Choice(alt.name, sub(alt.tid, bytes), n.tn)
    }
}
